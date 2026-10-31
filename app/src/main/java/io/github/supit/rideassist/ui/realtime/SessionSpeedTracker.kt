package io.github.supit.rideassist.ui.realtime

import android.util.Log
import androidx.lifecycle.ViewModel
import java.util.ArrayDeque

/**
 * 会话级数据记录器：**除非退出 APP，否则不清空**。
 *
 * ## 为什么必须放 ViewModel
 * 用户明确要求：断连、切页、屏幕旋转都**不能**清空速度/功率/电流/压差的记录。
 * Fragment 自身字段在重建时会丢失，所以：
 * - 最高速度用 [androidx.fragment.app.activityViewModels] 挂在 Activity 上
 * - 曲线采样点也一并存在这里（三个队列同时保存）
 *
 * 生命周期 = APP 会话，进程被杀后自然重置，正好符合「本次会话」语义。
 */
class SessionSpeedTracker : ViewModel() {

    /** 本次会话最高速度（km/h，整数） */
    /**
     * 本次会话最高速度（km/h）。
     *
     * ⭐ **清零规则（用户要求）**：只有**断开保护板连接超过 3 分钟**才清零，
     * 这样短时间关闭 APP（或切页面、旋转屏幕）不会丢失历史峰值。
     * 实现：记录末次「有数据」的时刻，重启时若距今 <3 分钟则恢复旧值。
     */
    var maxSpeed: Int = 0

    // ===== 曲线采样点（180 ≈ 3 分钟 @1Hz）=====
    // ⭐ 断连时不clear()，所以曲线保留断连前的走势，重连后继续接着画

    val powerPoints = ArrayDeque<Float>(MAX_POINTS)
    val currentPoints = ArrayDeque<Float>(MAX_POINTS)
    val diffPoints = ArrayDeque<Float>(MAX_POINTS)

    /**
     * GPS 速度曲线采样点（km/h，**整数**，与页面大字显示口径一致）。
     *
     * ⚠️ 必须存**显示值**（已做 5km/h 阈值归零 + 取整），不能存原始 GPS 值。
     * 否则曲线会出现 0~4km/h 的锯齿，而大字恒显示 0，两者对不上。
     * GPS 没速度时（`Location.hasSpeed()==false`）追加 0，保持曲线连续。
     */
    val speedPoints = ArrayDeque<Float>(MAX_POINTS)

    /** 当前值（用于曲线卡上的大字叠加显示） */
    var powerNow: Float = 0f
    var currentNow: Float = 0f
    var diffNow: Float = 0f

    /** 当前 GPS 速度（km/h，整数，与 tvGpsSpeed 一致） */
    var speedNow: Float = 0f

    // ==================== 峰值快照（骑行页与实时页共用） ====================

    /**
     * 一次「峰值快照」：出现该指标最大值的**那一刻**的数据。
     *
     * ⭐ 记录必须与峰值**同一帧**抓取 —— 电流、单体电压都要对应那一刻，
     *    事后去取会错位。
     *
     * ⭐⭐ 放在 Tracker 而不是 RealtimeFragment：RealtimeFragment 的字段
     * 是 Fragment 实例级的，切页重建就丢。**骑行页必须能读到实时页记下的峰值**，
     * 所以状态必须挂在 Activity 作用域。
     */
    data class PeakRecord(
        val value: Float,
        val clock: String,
        // 以下仅压差卡用到，其余卡为 0
        val current: Float = 0f,
        val maxCellIndex: Int = 0,
        val maxCellVolt: Float = 0f,
        val minCellIndex: Int = 0,
        val minCellVolt: Float = 0f,
        /**
         * ⭐ **偏离整体均值最多的电芯序号**（1-based，0 = 未计算）。
         *
         * 与 maxCellIndex/minCellIndex 的区别：那两个是「最高」和「最低」，
         * 描述的是两个极端；这个描述的是**谁最不像一伙的**。
         * 多数情况下它会与 min 或 max 重合，但当存在「一枝独秀」的偏高单体时，
         * 能直接指出问题电芯。
         */
        val outlierCellIndex: Int = 0,
        /** 该电芯相对其余电芯均值的偏离量（V，带符号：正=偏高，负=偏低） */
        val outlierDeviation: Float = 0f,
    )

    /** 四项峰值记录，下标与 [CARD_COUNT] 对齐；null = 尚无记录 */
    val peakRecords = arrayOfNulls<PeakRecord>(CARD_COUNT)

    /**
     * 峰值更新计数 —— 骑行页用它判断「要不要重绘」。
     *
     * ⭐ 用计数而不是把 [peakRecords] 包成 StateFlow：
     * 数组是**原地修改**的，StateFlow 靠 equals 比较数组引用，
     * 永远不会 emit（踩过这个坑：ArrayDeque 同理）。
     * 计数变化是明确的信号，简单可靠。
     */
    @Volatile
    var peakRevision: Long = 0L
        private set

    // ==================== 骑行统计（骑行页） ====================

    /** 首次检测到「在动」的墙钟时刻（ms）；0 = 本次会话还没开始骑行 */
    @Volatile
    var rideStartWallMs: Long = 0L
        private set

    /**
     * 行驶累计时长（ms）—— 用 `elapsedRealtime` 累加。
     *
     * ⭐ 不能用 `now - rideStartWallMs`：中途停车静止的那段时间不该算进骑行时长。
     * 这里只在「在动」的帧上累加 dt，静止帧只更新 lastTick。
     */
    @Volatile
    var rideMovingMs: Long = 0L
        private set

    /** 上次统计累加的时刻（elapsedRealtime） */
    @Volatile
    var rideLastTickMs: Long = 0L
        private set

    // ⭐ 三个派生指标（均速 / 百公里耗能 / 里程）全部从 distanceKm + energyWh + 各自时长算出，
//    不再维护 sumSpeed / movingSamples 这类「样本算术平均」累加器 ——
//    样本数不等于时长，算术平均在 GPS 间隔不均时会偏（详见 distanceKm 的说明）。

    /**
     * ⭐ **累计里程（km）—— 逐段梯形积分**，不是「均速 × 时长」。
     *
     * UI 上以「**预估里程**」独占一行显示。之所以叫「预估」而不直接报数：
     * GPS 测速本身有误差、爬坡时只测**水平分量**，精度约 ±10%，
     * 措辞上如实反映不确定性。
     *
     * 它同时是两个均速的分子：
     * ```
     * 骑行均速 = distanceKm ÷ 骑行用时
     * 行程均速 = distanceKm ÷ 行程用时
     * ```
     * 均速是**比值**，不依赖里程绝对值是否精确。
     *
     * ## 为什么必须逐段积分（方法论，别退回）
     * 「均速 × 时长 = 距离」只在**匀速**时成立。
     * 若先对所有样本求算术平均再乘总时长，权重是**样本数**而非时长——
     * GPS 刷新间隔不固定，密集区样本被过度加权。
     *
     * ## 累加的必须是**真实速度**，不能是显示值
     * 页面显示值做了「<5km/h 归零 + 取整」，用那种值积分会**系统性低估**
     * （爬坡/等红灯的真实速度被抹成 0）。
     * 所以 [accumulateRideStats] 的 `speedKmhReal` 必须是原始浮点速度。
     */
    @Volatile var distanceKm: Double = 0.0; private set

    /**
     * ⭐ **累计耗能（Wh）** —— 逐段 `功率 × 该帧时长` 积分。
     *
     * 用功率而不是「电压 × 电流」逐帧算再累加，是为了让读数与页面功率显示一致；
     * 两者物理上等价（P = U × I）。
     */
    @Volatile var energyWh: Double = 0.0; private set

    /** 上一次积分用的速度（km/h），用于梯形积分的「后值」 */
    private var lastSpeedKmh: Double = 0.0

    // ==================== ⭐ 派生指标 ====================

    /**
     * ⭐⭐ **回收电量**（Wh）—— 功率为**负**（充电方向）时的能量积分。
     *
     * ## 为什么要单独统计
     * 之前只累加了放电（`powerW > 0`）的 `energyWh`，回馈能量被静默丢弃。
     * 但电动车在长下坡/松开助力时回馈占比可以相当可观，
     * 把它算进「净耗能」才符合用户实际付的电费口径。
     *
     * ⚠️ 同样受 `moving` 门控 —— 插着充电器时不算「骑行中的回收」。
     */
    @Volatile var regenWh: Double = 0.0; private set

    /** 有效（净）耗能 = 放电能量 − 回收能量 */
    val netEnergyWh: Double get() = energyWh - regenWh

    /**
     * ⭐ **压差趋势斜率**（mV / 分钟）—— 对最近若干次压差采样做一元线性回归。
     *
     * ## 为什么看斜率而不是当前压差
     * 压差从 5mV 涨到 30mV 是**衰减的早期信号**，等到绝对值超标已经晚了。
     * 单次压差只是噪声，斜率才反映趋势。
     *
     * 返回 mV/min：正=压差在扩大（变差），负=在收敛（均衡生效/充放电均衡）。
     * 样本不足（<3）时返回 0。
     */
    val diffTrendMvPerMin: Float
        get() {
            // ⚠️ ArrayDeque 不支持按下标随机访问，必须先转 List。
            // 这里只在 getter 内转换（诊断面板每秒才读一次），代价可忽略。
            val q = diffPoints.toList()
            val n = q.size
            if (n < 3) return 0f
            // 取最近 60 个点（约 1 分钟 @1Hz）
            val from = maxOf(0, n - 60)
            val cnt = n - from
            if (cnt < 3) return 0f
            // 一元线性回归斜率： Σ(x-x̄)(y-ȳ) / Σ(x-x̄)²
            val xMean = (from + n - 1) / 2.0
            var sxy = 0.0
            var sxx = 0.0
            var yMean = 0.0
            for (i in from until n) yMean += q[i]
            yMean /= cnt
            for (i in from until n) {
                val dx = (i - xMean)
                sxy += dx * (q[i] - yMean)
                sxx += dx * dx
            }
            if (sxx <= 0.0) return 0f
            // 每点间隔约 1 秒 → mV/point·(1000/60) = mV/min
            return ((sxy / sxx) * 60.0).toFloat()
        }

    /**
     * ⭐⭐ **实测有效容量**（Ah）—— 用「SOC 下降了多少」反推真实可用容量。
     *
     * ## 目的：校验官方公式
     * 本项目的 `remainingCapacity` = `额定 × SOC%`，是官方口径但**从未被实测校验过**。
     * 用「SOC 每掉 1% 实际少多少 Ah」可以直接量出真实可用容量：
     * ```
     * 实测有效容量 = (起点容量 − 当前容量) ÷ SOC下降百分比 × 100
     * ```
     * 与额定容量对比即得 SOH 的**实测值**，比读设备上报字段可信
     * （设备根本没上报 soh，那个字段永远是 0）。
     *
     * ⚠️ 需 SOC 下降跨度 ≥ [MIN_SOC_SPAN_FOR_CAPACITY]（30%）才有参考意义，
     * 否则返回 0，调用方显示「样本不足」而不是给个假数字。
     */
    val measuredCapacityAh: Float
        get() {
            if (socStartSoc <= 0f || socStartAh <= 0f) return 0f
            val socSpan = socStartSoc - lastSocSeen
            if (socSpan < MIN_SOC_SPAN_FOR_CAPACITY) return 0f
            val ahUsed = socStartAh - latestRemainingAh
            if (ahUsed <= 0.05f) return 0f
            return ahUsed / socSpan * 100f
        }

    /** 实测 SOH（%）：实测有效容量 ÷ 额定容量；样本不足或无额定容量时返回 0 */
    val measuredSoh: Float
        get() {
            val cap = measuredCapacityAh
            if (cap <= 0f || ratedCapacitySnapshot <= 0f) return 0f
            return (cap / ratedCapacitySnapshot * 100f).coerceIn(0f, 150f)
        }

    /** SOC 跟踪已覆盖的下降跨度（%），诊断面板用它显示「还差多少才够算容量」 */
    val socSpanTracked: Float get() = if (socStartSoc > 0f) socStartSoc - lastSocSeen else 0f

    // ---------- 派生指标所需的内部快照 ----------

    /** SOC 下降跟踪的起点容量（Ah） */
    @Volatile private var socStartAh: Float = 0f
    @Volatile private var socStartSoc: Float = 0f
    @Volatile private var ratedCapacitySnapshot: Float = 0f
    /** 上一帧 SOC，用于检测下降/上升方向 */
    @Volatile private var lastSocSeen: Float = 0f

    /**
     * ⭐ 每帧写入电池快照时**顺带**更新派生指标所需的 SOC 跟踪。
     *
     * 与 [updateBatterySnapshot] 分开是为了让「快照」保持纯粹（只存值），
     * 这里做的是**状态机推进**（跨帧逻辑）。
     *
     * @param soc 当前 SOC（%），<=0 表示未读到（忽略本帧）
     * @param remainingAh 当前剩余容量（Ah）
     * @param ratedAh 额定容量（Ah），仅首次记录
     */
    fun updateSocTrack(soc: Float, remainingAh: Float, ratedAh: Float) {
        if (soc <= 0f || remainingAh <= 0f) return
        if (ratedCapacitySnapshot <= 0f && ratedAh > 0f) ratedCapacitySnapshot = ratedAh

        if (socStartSoc == 0f) {
            // 首次：建立起点锚点
            socStartSoc = soc
            socStartAh = remainingAh
            lastSocSeen = soc
            return
        }
        if (soc > lastSocSeen) {
            // ⭐ 回到更高 SOC（插上充电器了）→ **必须重置起点**。
            // 否则会把「充电补回的部分」也算进放电跨度，
            // 算出的「每 1% 耗多少 Ah」会被严重稀释。
            socStartSoc = soc
            socStartAh = remainingAh
            lastSocSeen = soc
        } else {
            // 下降（或持平）：只更新当前值，起点不动
            lastSocSeen = soc
        }
    }

    // ==================== 不变性断言 ====================

    /**
     * ⭐⭐⭐ **物理不变性断言** —— 只打日志，绝不崩溃。
     *
     * ## 为什么需要
     * 本项目出现过「停车时预计可行驶里程不降反升」这类 bug，
     * 其本质是**派生量违反了物理单调性**。这类 bug 靠看截图发现不了，
     * 必须靠断言把「违反的那一刻」抓出来。
     *
     * 覆盖的不变性：
     * | # | 不变式 |
     * |---|---|
     * | 1 | 静止时 `distanceKm` **必须冻结**（GPS 静态漂移也不许算进去）|
     * | 2 | 静止且功率≈0 时 `energyWh` **必须冻结** |
     * | 3 | 电流严格为 0 时 `power` 必为 0（派生量一致性）|
     * | 4 | `regenWh` 只在功率为负时增长 |
     *
     * 违反时打一条含上下文的 error log；正常时**不产生任何输出**，
     * 所以可以放心每帧调用。
     *
     * @param prevDistance 累加前的里程
     * @param prevEnergy 累加前的耗能
     * @param prevRegen 累加前的回收量
     */
    private fun assertInvariants(
        amps: Float,
        power: Float,
        moving: Boolean,
        prevDistance: Double,
        prevEnergy: Double,
        prevRegen: Double,
    ) {
        // 1. 静止时里程必须冻结
        //    只在「确实在放电」时断言 —— 滑行/停车时功率≈0，里程冻结本就是正常的
        if (!moving && amps > 0.05f && power > 1f && distanceKm > prevDistance + 1e-6) {
            Log.w(
                INVARIANT_TAG,
                "❌ 不变1违反 moving=false 但电流=$amps A " +
                    "功率=${power.toInt()}W，里程仍在增长 " +
                    "${"%.6f".format(prevDistance)}→${"%.6f".format(distanceKm)}km"
            )
        }
        // 2. 静止且功率≈0 时耗能必须冻结
        if (!moving && power <= 1f && energyWh > prevEnergy + 1e-6) {
            Log.w(
                INVARIANT_TAG,
                "❌ 不变2违反 moving=false 且功率≈0，耗能却增长 " +
                    "${"%.4f".format(prevEnergy)}→${"%.4f".format(energyWh)}Wh"
            )
        }
        // 3. 派生量一致性：电流严格为 0 时功率必为 0
        if (amps == 0f && power != 0f) {
            Log.w(INVARIANT_TAG, "❌ 不变3违反 电流=0 但功率=${power}W（派生量不一致）")
        }
        // 4. 回收量只能因负功率增长
        if (power >= 0f && regenWh > prevRegen + 1e-6) {
            Log.w(
                INVARIANT_TAG,
                "❌ 不变4违反 功率=${power.toInt()}W（非负）但回收量增长 " +
                    "${"%.4f".format(prevRegen)}→${"%.4f".format(regenWh)}Wh"
            )
        }
    }

    // ==================== 剩余电量（用于「预计可行驶里程」）====================

    /**
     * 电池剩余容量快照（Ah）与标称总压（V）。
     *
     * ⭐ 骑行页**不订阅**电池数据流（避免与实时页重复采样），
     * 所以由实时页每帧写进来，骑行页被动读取 —— 与峰值记录同一套路子。
     *
     * ⚠️⚠️ **刻意不存「实时总压」**：它随负载抖动（放电压降、停车回弹），
     * 拿它当能量基准会出现「停车时剩余能量反而变大」的荒谬现象。
     */
    @Volatile var latestRemainingAh: Float = 0f
    @Volatile var latestNominalVolt: Float = 0f

    /** 写入电量快照（由实时页每帧调用） */
    fun updateBatterySnapshot(remainingAh: Float, nominalVolt: Float) {
        if (remainingAh > 0f) latestRemainingAh = remainingAh
        if (nominalVolt > 0f) latestNominalVolt = nominalVolt
    }

    /**
     * 预计可行驶里程（km）= **电池剩余容量折算的能量 ÷ 本次骑行的单位电耗**。
     *
     * ```
     * 剩余能量 Wh = 电池剩余容量(Ah) × 标称总压(V)
     * 单位电耗 Wh/km = energyPer100Km × 1000 ÷ 100
     * 预计里程 = 剩余能量 ÷ 单位电耗
     * ```
     *
     * ## 为什么这个形式是稳的
     * - **分子**只依赖电池的剩余容量与标称电压，**完全不碰 GPS**。
     *   停车掉电 → 剩余容量降 → 里程降；停车不掉电 → 里程持平不动。
     *   ⭐ 它**只会降或持平，绝不会涨**。
     * - **分母**是本次骑行实测的百公里电耗，已排除静止期的 GPS 漂移
     *   （见 `accumulateRideStats` 里的 `segSpeed` 门控），
     *   所以停车时分母冻结，不会因漂移而变小。
     *
     * ⚠️ 里程没跑够（<10m）时返回 0，由调用方显示占位符 ——
     * 不用一个建立在噪声上的数字误导用户。
     */
    val estimatedRangeKm: Float
        get() {
            val per100 = energyPer100Km
            if (per100 <= 0f) return 0f
            if (latestRemainingAh <= 0f || latestNominalVolt <= 0f) return 0f
            val remainWh = latestRemainingAh * latestNominalVolt
            if (remainWh <= 0f) return 0f
            val whPerKm = per100 * 1000f / 100f
            return (remainWh / whPerKm).toFloat()
        }

    /**
     * ⭐⭐⭐ **本次会话的完整快照** —— 供诊断面板直接渲染。
     *
     * ## 为什么要单独做这个
     * 本项目多次出现「改了但无法验证」的困境：无有效数据时 UI 元素被
     * 显隐逻辑藏起来，**看截图无法区分「改对了」和「根本没数据」**。
     * 所以这里把**原始累加器**与**派生量**并排返回，诊断面板两者同屏显示。
     *
     * 所有字段都是**瞬时读取**，不做任何缓存，保证面板显示的就是当前真值。
     */
    fun debugSnapshot(): DebugSnapshot {
        val remainWh = latestRemainingAh * latestNominalVolt
        val per100 = energyPer100Km
        val whPerKm = per100 * 1000f / 100f
        return DebugSnapshot(
            isRecording = isRecording,
            distanceKm = distanceKm,
            energyWh = energyWh,
            regenWh = regenWh,
            netEnergyWh = netEnergyWh,
            rideMovingMs = rideMovingMs,
            tripElapsedMs = elapsedSinceStartMs(),
            remainingAh = latestRemainingAh,
            nominalVolt = latestNominalVolt,
            remainWh = remainWh,
            per100Km = per100,
            whPerKm = whPerKm,
            estimatedRangeKm = estimatedRangeKm,
            diffTrendMvPerMin = diffTrendMvPerMin,
            measuredCapacityAh = measuredCapacityAh,
            measuredSoh = measuredSoh,
            socSpanTracked = socSpanTracked,
            speedPoints = speedPoints.size,
            powerPoints = powerPoints.size,
            currentPoints = currentPoints.size,
            diffPoints = diffPoints.size,
        )
    }

    /** [debugSnapshot] 的返回结构 —— 诊断面板逐字段渲染 */
    data class DebugSnapshot(
        val isRecording: Boolean,
        val distanceKm: Double,
        val energyWh: Double,
        val regenWh: Double,
        val netEnergyWh: Double,
        val rideMovingMs: Long,
        val tripElapsedMs: Long,
        // 预计里程的分子分母 —— **并排显示**是本面板的核心价值
        val remainingAh: Float,
        val nominalVolt: Float,
        val remainWh: Float,
        val per100Km: Float,
        val whPerKm: Float,
        val estimatedRangeKm: Float,
        // 派生指标
        val diffTrendMvPerMin: Float,
        val measuredCapacityAh: Float,
        val measuredSoh: Float,
        val socSpanTracked: Float,
        // 采样点数量（判断采样是否正常）
        val speedPoints: Int,
        val powerPoints: Int,
        val currentPoints: Int,
        val diffPoints: Int,
    )

    /**
     * 里程时长（ms）= 当前时间 - 本次记录的开始时间。
     *
     * 与 [rideMovingMs]（骑行时长）的区别：
     * - 骑行时长 = 只累加「在动」的时间，**停车不计入**
     * - 里程时长 = 从开始记录到现在的**墙钟总跨度**，停车也计入
     */
    fun elapsedSinceStartMs(): Long {
        val start = rideStartWallMs
        if (start == 0L) return 0L
        return (System.currentTimeMillis() - start).coerceAtLeast(0L)
    }

    /**
     * 平均速度（km/h），无里程时返回 0 —— 即「骑行均速」。
     *
     * ⭐ **时间加权**：总距离 ÷ 骑行总时长（[rideMovingMs]）。
     * 不是「样本算术平均」—— 后者在 GPS 间隔不均时会偏（见 [distanceKm]）。
     */
    val avgSpeed: Float
        get() = if (rideMovingMs <= 0L) 0f
        else (distanceKm / (rideMovingMs / 3_600_000.0)).toFloat()

    /**
     * 百公里耗能（kWh/100km）= 总耗能(Wh) ÷ 总里程(km) × 100。
     *
     * 这是电耗车最关心的指标：跑100 公里要多少度电。
     * ⭐ 比「百公里加速到100km/h耗时」更贴近日常换算 ——
     * 用户可直接乘以单次里程得到补能成本。
     *
     * 里程过小时不做除法（噪声大），返回 0 由调用方显示占位符。
     */
    val energyPer100Km: Float
        get() {
            if (distanceKm < 0.01) return 0f      // 不到 10 米不计算
            // ⭐ 用**净耗能**（放电 − 回收）而不是放电总量。
            // 用户实际付的电费是净值；把回馈丢掉会让长下坡路段的电耗虚高。
            return (netEnergyWh / distanceKm * 100f / 1000f).toFloat()  // Wh→kWh
        }

    /**
     * 里程均速（km/h）= 累计里程 ÷ 里程时长。
     *
     * ⚠️ 与 [avgSpeed]（骑行均速）的区别：分母是**墙钟总耗时**（含停车），
     * 所以停车时间会真实拉低这个值 —— 这正是「里程均速」的意义。
     */
    val tripAvgSpeed: Float
        get() {
            val ms = elapsedSinceStartMs()
            if (ms <= 0L) return 0f
            return (distanceKm / (ms / 3_600_000.0)).toFloat()
        }

    // ==================== 记录状态机（启动条件 / 停止条件） ====================

    /**
     * **本次记录是否已启动**。
     *
     * ⭐⭐ 这是本项目的核心状态机，用户需求原话：
     * 「电流大于 1A 是启动记录的条件，启动记录后**持续记录**，
     * 直到出现停止记录的条件（断开保护板连接多久），
     * 而不是电流小于 1A 就不记录，而且把曲线隐藏了。」
     *
     * ## 为什么必须是「锁存」而不是「瞬时判断」
     * 之前直接用 `current >= 1A` 当「是否记录」，导致：
     * 1. 骑行中短暂松开油门/滑行（电流掉到 0.8A）→ 曲线**整段消失**
     * 2. 峰值记录跟着停止 → 明明还在骑却记不到更高的值
     * 真实骑行里电流是**锯齿状**的（加速冲高、滑行掉零），
     * 瞬时阈值等于不停地在「记录/停止记录」之间跳变。
     *
     * ## 状态迁移
     * - **启动**：放电电流 >= 1A（只认放电，充电不算）
     * - **停止**：断开保护板连接超过 [STOP_AFTER_DISCONNECT_MS]（3 分钟）
     *
     * 中途电流掉到 1A 以下**不停止**，只是不累加时长/平均值。
     */
    @Volatile
    var isRecording: Boolean = false
        private set

    /** 末次收到有效设备数据的墙钟时刻（ms）；0 = 从未收到过 */
    @Volatile
    var lastDataWallMs: Long = 0L
        private set

    /**
     * 末次「放电电流 ≥ 启动阈值」的墙钟时刻（ms）；0 = 从未。
     * ⭐ 供充电 ETA 判定「最近是否在骑行放电」——
     * 骑行结束插充电器的场景里 isRecording 锁存要等断连 3 分钟才翻 false
     * （BMS 还连着），不能用它；用「最近有没有放电帧」则两边都对：
     * 骑行中必有放电帧（ETA 隐藏），插充电器后 60s 内最后一波放电过期（ETA 显示）。
     */
    @Volatile
    var lastDischargeWallMs: Long = 0L
        private set

    /** 每帧调用：放电电流达到启动阈值时刷新 [lastDischargeWallMs] */
    fun noteDischarge(amps: Float) {
        if (amps >= 1f) lastDischargeWallMs = System.currentTimeMillis()
    }

    /**
     * 每帧调用：驱动记录状态机。
     *
     * @param dischargingNow 当前是否放电 >= 阈值
     * @param hasDeviceData 本帧是否有真实设备数据（断连时为 false）
     * @return true = 状态发生了迁移（调用方需要重绘 UI）
     */
    fun updateRecordingState(dischargingNow: Boolean, hasDeviceData: Boolean): Boolean {
        val before = isRecording
        if (hasDeviceData) lastDataWallMs = System.currentTimeMillis()

        if (dischargingNow) {
            // 启动条件：放电 >= 阈值。**一旦启动就不再因电流回落而停止。**
            isRecording = true
        } else if (isRecording) {
            // 停止条件：**只有断连超时**才停止。
            // 用「距末次有效数据」判断，而不是「本次电流为 0」——
            // 后者在滑行/等红灯时会误停。
            val last = lastDataWallMs
            val disconnectedTooLong = last == 0L ||
                System.currentTimeMillis() - last >= STOP_AFTER_DISCONNECT_MS
            if (disconnectedTooLong) isRecording = false
        }
        return isRecording != before
    }

    /**
     * ⭐⭐⭐ **门控契约见 [io.github.supit.rideassist.core.gate.MoveGate]** —— 判据只有那一处。
     *
     * @param speedKmhReal **真实速度**（km/h），未经取整/归零 —— 用于积分。
     * @param powerW 当前功率（W），正=放电、负=回收
     * @param amps 当前电流**绝对值**（A），仅供不变性断言使用
     * @param moving [MoveGate.evaluate] 的结论。全部分子分母**共用**这一个门控。
     */
    fun accumulateRideStats(speedKmhReal: Float, powerW: Float, amps: Float, moving: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = rideLastTickMs
        val dtSec = if (last != 0L && now > last) (now - last) / 1000.0 else 0.0
        rideLastTickMs = now
        if (!isRecording) return          // 未启动记录 → 完全不统计

        if (rideStartWallMs == 0L) rideStartWallMs = System.currentTimeMillis()

        val prevDistance = distanceKm
        val prevEnergy = energyWh
        val prevRegen = regenWh

        // ⭐⭐⭐ **静止时必须把速度当成 0**，不能把原始 GPS 漂移值喂进积分。
        //
        // ## 为什么（这是「停车时可行驶里程反而上涨」的真凶，与电压无关）
        // GPS 静止时仍有 1~3 km/h 的漂移读数。若直接积分：
        //   - `distanceKm`（百公里电耗的**分母**）停车后仍在缓慢增长
        //   - 而 `energyWh` 只在 `powerW > 0` 时累加，停车时功率≈0 → **分子冻结**
        // 分子冻结 + 分母增长 → `energyPer100Km = energyWh / distanceKm` 持续变小
        // → 单位电耗下降 → 「预计可行驶里程 = 剩余能量 ÷ 单位电耗」**不降反升**。
        //
        // 实测算例（20Ah/89%/17串三元，停车 10 分钟、GPS 漂移 2.5km/h）：
        //   7.0km → 7.1 → 7.2 → 7.3 → 7.5 → 8.0km   ⭐一路上涨
        //
        // ⚠️ 骑行均速同样以 `distanceKm` 为分子，漂移会系统性地把它算低，
        // 所以这里一并修正。
        val segSpeed = if (moving) speedKmhReal else 0f

        // ⭐ 梯形积分：用前后两帧速度的平均值 × 本帧时长
        // （静止时后值为 0，会自然减速，比矩形积分更贴近真实运动）
        val avgSeg = (lastSpeedKmh + segSpeed.toDouble()) / 2.0
        if (avgSeg > 0) distanceKm += avgSeg * (dtSec / 3600.0)      // km/h × h = km
        lastSpeedKmh = segSpeed.toDouble()

        // ⭐⭐⭐ 耗能积分：**正功率放电、负功率回收，分开累加到两个累加器**。
        //
        // 之前回收能量被静默丢弃；用户实际付的电费是**净值**。
        // `netEnergyWh`（放电−回收）才是真实用电量，
        // 电耗与预计里程都已改用净耗能。
        if (powerW > 0f) energyWh += powerW * (dtSec / 3600.0)        // W × h = Wh
        else if (powerW < 0f) regenWh += -powerW * (dtSec / 3600.0)  // 回收为正量

        if (moving) {
            // ⭐ 只有「在动」时才累加**骑行时长** —— 停车等待不该算进骑行时长。
            // 里程/耗能则无论是否「在动」都要累加（它们按墙钟时间积分）。
            if (dtSec > 0) rideMovingMs += (now - last)
        }

        // ⭐ 不变性断言：每帧检查，正常时零开销零输出
        assertInvariants(
            amps = amps,
            power = powerW,
            moving = moving,
            prevDistance = prevDistance,
            prevEnergy = prevEnergy,
            prevRegen = prevRegen,
        )
    }

    /**
     * 记录一次峰值快照；只有**超过历史最大值**时才覆盖。
     *
     * @return true = 刷新了记录（调用方需要重绘）
     */
    fun updatePeak(index: Int, value: Float, d: io.github.supit.rideassist.core.protocol.model.BmsRealtimeData): Boolean {
        val cur = peakRecords[index]
        if (cur != null && value <= cur.value) return false    // 没超过历史最大

        val clock = CLOCK_FORMAT.format(java.util.Date())
        peakRecords[index] = if (index == CARD_DIFF) {
            // 压差：额外记录电流 + 最高/最低单体（含序号）
            val v = d.cellVoltages
            // ⚠️ 单体数 < 2 时没有"压差"可言，跳过
            if (d.cellCount < 2 || v.size < 2) return false
            var maxIdx = 0
            var minIdx = 0
            for (i in 1 until v.size) {
                if (v[i] > v[maxIdx]) maxIdx = i
                if (v[i] < v[minIdx]) minIdx = i
            }
            // ⭐ 找出**偏离其余电芯均值最多**的那一颗（用户需求）
            val outlier = findOutlierCell(v)
            PeakRecord(
                value = value,
                clock = clock,
                current = d.totalCurrent,
                maxCellIndex = maxIdx + 1,          // 1-based 展示
                maxCellVolt = v[maxIdx],
                minCellIndex = minIdx + 1,
                minCellVolt = v[minIdx],
                outlierCellIndex = outlier.first + 1,        // 1-based
                outlierDeviation = outlier.second,
            )
        } else {
            PeakRecord(value = value, clock = clock)
        }
        peakRevision++
        return true
    }

    // ==================== 异常电芯识别 ====================

    /**
     * ⭐ 找出**偏离其余电芯均值最多**的那一颗（用户需求）。
     *
     * ## 算法（用户给定的口径）
     * 对每个电芯 `i`：
     * ```
     * avgOfOthers = (Σ 全部 − v[i]) ÷ (n − 1)
     * deviation_i = v[i] − avgOfOthers          // 带符号：正偏高、负偏低
     * ```
     * 取 `|deviation|` 最大的那个即「偏离最多」。
     *
     * ## 为什么不能用「最高」或「最低」代替
     * 最高/最低描述的是**两个极端**，而「偏离最多」描述的是
     * **谁最不像一伙的** —— 这是一致性诊断要的答案。
     * 典型场景：一颗单体虚高（或虚低）20mV，其余都在 3.55~3.57 之间。
     * 此时它多半**同时是最高也是最低压差的主角**，但用户真正想知道的是
     * 「是哪一颗坏了」，这需要显式指出来。
     *
     * ## 实现要点
     * - 用**总和 + 均值**两遍法，避免 O(n²) 重算（n 可达 30+）
     * - `|v[i] − avgOfOthers|` 与「离整体均值最远」严格单调等价
     * - 少于 2 颗电芯无「均值」可言，返回 (-1, 0f)
     *
     * @return (下标 0-based, 偏离量 V 带符号)；无法判断时 (-1, 0f)
     */
    private fun findOutlierCell(v: List<Float>): Pair<Int, Float> {
        val n = v.size
        if (n < 2) return -1 to 0f
        val sum = v.sum()
        var bestIdx = -1
        var bestDev = 0f
        var bestAbs = -1f
        for (i in 0 until n) {
            val avgOfOthers = (sum - v[i]) / (n - 1)
            val dev = v[i] - avgOfOthers
            val absDev = kotlin.math.abs(dev)
            if (absDev > bestAbs) {
                bestAbs = absDev
                bestIdx = i
                bestDev = dev
            }
        }
        return bestIdx to bestDev
    }

    // ==================== Max/Min 持久化（断连 3 分钟才清零） ====================
    /**
     * 记录「末次有数据」的时刻并持久化 [maxSpeed]。
     *
     * ⭐ 用户需求："各种 Max Min 值的清零时间，改为断开保护板连接超过 3 分钟，
     * 这样短时间关闭 APP 也不会丢失，可能需要本地记录末次连接时间。"
     *
     * 每次收到有效数据就调用一次（写SP 约 1KB，开销可忽略）。
     */
    fun persistMaxSpeed(context: android.content.Context) {
        val sp = context.getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE)
        sp.edit()
            .putInt(KEY_MAX_SPEED, maxSpeed)
            .putLong(KEY_LAST_ACTIVE, System.currentTimeMillis())
            .apply()
    }

    /**
     * 恢复上次会话的 [maxSpeed]（仅当距上次活跃**不足** [MAX_KEEP_MS] 时）。
     *
     * @return true = 已恢复（沿用旧值）；false = 间隔太久已重置为 0
     */
    fun restoreMaxSpeed(context: android.content.Context): Boolean {
        val sp = context.getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE)
        val last = sp.getLong(KEY_LAST_ACTIVE, 0L)
        val saved = sp.getInt(KEY_MAX_SPEED, 0)
        val elapsed = System.currentTimeMillis() - last
        // ⚠️ last=0 说明从未记录过 → 清零
        if (last == 0L || elapsed >= MAX_KEEP_MS) {
            maxSpeed = 0
            return false
        }
        maxSpeed = saved
        return true
    }

    // ==================== ⭐ 会话持久化（跨进程被杀） ====================

    /**
     * ⭐⭐⭐ 把整个会话**落盘**。
     *
     * ## 为什么需要（不只是「曲线续上」）
     * 之前会话数据全在 Activity 作用域的 ViewModel 里，进程被系统回收
     * （息屏、切后台、内存紧张）就**全丢**。用户会认为「我明明在骑，
     * 怎么打开 APP 里程归零了」。
     *
     * 落盘内容：全部累加器 + 峰值记录 + 电量快照 + SOC 跟踪起点。
     * ⚠️ 曲线采样点**不落盘** —— 1800×4 个 Float 的 JSON 有 30KB+，
     * 每秒写一次 SP 完全不可行；而曲线丢一段对用户没有感知（曲线是趋势看的）。
     *
     * @param throttleMs 距上次落盘的最小间隔。默认 30 秒 —— 每秒写 SP 会
     *   造成明显 I/O，30 秒的粒度对「进程被杀」的场景足够（最多丢 30 秒）。
     */
    fun persistSession(context: android.content.Context, throttleMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < throttleMs) return
        lastPersistMs = now

        val sp = context.getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE)
        val peaks = StringBuilder()
        peakRecords.forEachIndexed { i, r ->
            if (i > 0) peaks.append(';')
            if (r == null) {
                peaks.append('-')
            } else {
                // 逗号分隔：值,时刻,电流,最高芯,最高压,最低芯,最低压,异常芯,偏离
                peaks.append(
                    listOf(
                        r.value, r.clock, r.current,
                        r.maxCellIndex, r.maxCellVolt,
                        r.minCellIndex, r.minCellVolt,
                        r.outlierCellIndex, r.outlierDeviation,
                    ).joinToString(",")
                )
            }
        }
        sp.edit()
            .putInt(KEY_MAX_SPEED, maxSpeed)
            .putLong(KEY_LAST_ACTIVE, now)
            .putLong(KEY_RIDE_START, rideStartWallMs)
            .putLong(KEY_RIDE_MOVING, rideMovingMs)
            .putLong(KEY_LAST_TICK, rideLastTickMs)
            .putLong(KEY_LAST_DATA, lastDataWallMs)
            .putFloat(KEY_DISTANCE, distanceKm.toFloat())
            .putFloat(KEY_ENERGY, energyWh.toFloat())
            .putFloat(KEY_REGEN, regenWh.toFloat())
            .putFloat(KEY_REMAIN_AH, latestRemainingAh)
            .putFloat(KEY_NOMINAL_V, latestNominalVolt)
            .putFloat(KEY_SOC_START, socStartSoc)
            .putFloat(KEY_SOC_AH, socStartAh)
            .putFloat(KEY_SOC_LAST, lastSocSeen)
            .putFloat(KEY_RATED, ratedCapacitySnapshot)
            .putString(KEY_PEAKS, peaks.toString())
            .apply()
    }

    /**
     * 从上次落盘恢复会话。
     *
     * ⚠️ **只恢复「距上次落盘不足 [MAX_KEEP_MS]（3 分钟）」的会话** ——
     * 与 [restoreMaxSpeed] 同口径：短暂息屏/切后台要续上，
     * 但隔天再打开时应该是一次全新的记录，否则新旧记录会混成一团。
     *
     * ⚠️ [rideLastTickMs] 用的是 `elapsedRealtime`（开机计时），
     * **不能跨进程恢复** —— 恢复后必须置 0，
     * 否则下一次积分的 `dtSec` 会算出一个巨大的荒谬值（跨了多久就算多久）。
     */
    fun restoreSession(context: android.content.Context): Boolean {
        val sp = context.getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE)
        val last = sp.getLong(KEY_LAST_ACTIVE, 0L)
        if (last == 0L || System.currentTimeMillis() - last >= MAX_KEEP_MS) return false

        rideStartWallMs = sp.getLong(KEY_RIDE_START, 0L)
        rideMovingMs = sp.getLong(KEY_RIDE_MOVING, 0L)
        lastDataWallMs = sp.getLong(KEY_LAST_DATA, 0L)
        distanceKm = sp.getFloat(KEY_DISTANCE, 0f).toDouble()
        energyWh = sp.getFloat(KEY_ENERGY, 0f).toDouble()
        regenWh = sp.getFloat(KEY_REGEN, 0f).toDouble()
        latestRemainingAh = sp.getFloat(KEY_REMAIN_AH, 0f)
        latestNominalVolt = sp.getFloat(KEY_NOMINAL_V, 0f)
        socStartSoc = sp.getFloat(KEY_SOC_START, 0f)
        socStartAh = sp.getFloat(KEY_SOC_AH, 0f)
        lastSocSeen = sp.getFloat(KEY_SOC_LAST, 0f)
        ratedCapacitySnapshot = sp.getFloat(KEY_RATED, 0f)

        // ⭐⚠️ elapsedRealtime 不能跨进程 —— 必须清零，
        // 否则下一次积分会把「从关机到现在的全部时间」当成一帧 dt。
        rideLastTickMs = 0L
        lastSpeedKmh = 0.0

        restorePeaks(sp.getString(KEY_PEAKS, "") ?: "")
        return true
    }

    /** 解析落盘的峰值串（格式见 [persistSession]），解析失败整体丢弃 */
    private fun restorePeaks(raw: String) {
        if (raw.isBlank()) return
        val parts = raw.split(';')
        parts.forEachIndexed { i, seg ->
            if (i >= CARD_COUNT) return@forEachIndexed
            val rec = if (seg == "-") null else seg.split(",").let { f ->
                if (f.size < 9) return@let null
                PeakRecord(
                    value = f[0].toFloatOrNull() ?: return@let null,
                    clock = f[1],
                    current = f[2].toFloatOrNull() ?: 0f,
                    maxCellIndex = f[3].toFloatOrNull()?.toInt() ?: 0,
                    maxCellVolt = f[4].toFloatOrNull() ?: 0f,
                    minCellIndex = f[5].toFloatOrNull()?.toInt() ?: 0,
                    minCellVolt = f[6].toFloatOrNull() ?: 0f,
                    outlierCellIndex = f[7].toFloatOrNull()?.toInt() ?: 0,
                    outlierDeviation = f[8].toFloatOrNull() ?: 0f,
                )
            }
            peakRecords[i] = rec
        }
    }

    /** 上次落盘时刻（ms），用于节流 */
    private var lastPersistMs: Long = 0L

    /**
     * 手动清空全部会话数据（诊断面板的「清空本次会话」按钮用）。
     *
     * 曲线队列也一并清掉 —— 否则会出现「累加器归零但曲线还在」的自相矛盾。
     */
    fun resetSession() {
        maxSpeed = 0
        rideStartWallMs = 0
        rideMovingMs = 0
        rideLastTickMs = 0
        lastDataWallMs = 0
        isRecording = false
        distanceKm = 0.0
        energyWh = 0.0
        regenWh = 0.0
        lastSpeedKmh = 0.0
        latestRemainingAh = 0f
        latestNominalVolt = 0f
        socStartSoc = 0f
        socStartAh = 0f
        lastSocSeen = 0f
        ratedCapacitySnapshot = 0f
        lastPersistMs = 0L
        speedPoints.clear()
        powerPoints.clear()
        currentPoints.clear()
        lastDischargeWallMs = 0L
        diffPoints.clear()
        java.util.Arrays.fill(peakRecords, null)
        peakRevision++
    }

    companion object {
        /** 不变性断言的 log tag —— 排查时直接 `logcat -s YybmsInvariant` */
        const val INVARIANT_TAG = "YybmsInvariant"

        /**
         * ⭐ 计算实测有效容量所需的**最小 SOC 下降跨度**（%）。
         *
         * ## 为什么必须是 30 而不是 5
         * `soc` 是 **u8 粒度**（1% 一跳），且是 BMS 自己按电压估算的，本身就有
         * ±2~3% 的误差。跨度太小 → 「(容量差) ÷ (SOC差)」的分母接近噪声，
         * 算出来的容量能偏到 40% 以上，比不给还糟。
         *
         * 30% 跨度下相对误差降到 10% 以内，作为「粗略但可信」的参考。
         */
        const val MIN_SOC_SPAN_FOR_CAPACITY = 30f

        /**
         * ⭐⭐ **采样点上限：**1800 点。
         *
         * ⭐ 从 180 提到 1800 的原因：X 轴改为「固定像素间距 + 从左裁剪」后，
         * 队列里的历史数据即使画到屏幕外也**不会丢失**，
         * 骑行 30 分钟（1800s @1Hz）的完整走势都留着，
         * 横向滚动/回看时都能取到。1800 个 Float 仅 7.2KB，可忽略。
         */
        const val MAX_POINTS = 1800

        // ==================== Max/Min 持久化 ====================

        private const val SP_NAME = "yybms_session_peak"
        private const val KEY_MAX_SPEED = "max_speed"
        private const val KEY_LAST_ACTIVE = "last_active_ms"

        // ==================== 会话持久化 ====================
        private const val KEY_RIDE_START = "ride_start_ms"
        private const val KEY_RIDE_MOVING = "ride_moving_ms"
        private const val KEY_LAST_TICK = "last_tick_ms"
        private const val KEY_LAST_DATA = "last_data_ms"
        private const val KEY_DISTANCE = "distance_km"
        private const val KEY_ENERGY = "energy_wh"
        private const val KEY_REGEN = "regen_wh"
        private const val KEY_REMAIN_AH = "remain_ah"
        private const val KEY_NOMINAL_V = "nominal_v"
        private const val KEY_SOC_START = "soc_start"
        private const val KEY_SOC_AH = "soc_start_ah"
        private const val KEY_SOC_LAST = "soc_last"
        private const val KEY_RATED = "rated_ah"
        private const val KEY_PEAKS = "peaks"

        /**
         * Max/Min 的保留时长：**3 分钟**（用户指定）。
         *
         * 断开保护板超过这么久才清零；短时间关闭 APP 重新打开仍保留峰值。
         */
        const val MAX_KEEP_MS = 3 * 60 * 1000L

        /**
         * **停止记录**的判据：断开保护板连接超过这么久。
         *
         * 与 [MAX_KEEP_MS] 同为 3 分钟（用户指定）。
         * ⭐ 这是**唯一**的停止条件 —— 电流掉到 1A 以下**不停止**。
         */
        const val STOP_AFTER_DISCONNECT_MS = 3 * 60 * 1000L

        // ==================== 峰值快照：四项索引 ====================

        /** 指标数量：速度 / 功率 / 电流 / 压差（与 peakRecords 下标对齐） */
        const val CARD_COUNT = 4

        /** 速度项索引 */
        const val CARD_SPEED = 0

        /** 功率项索引 */
        const val CARD_POWER = 1

        /** 电流项索引 */
        const val CARD_CURRENT = 2

        /** 压差项索引 */
        const val CARD_DIFF = 3

        /** 峰值记录的时刻格式化（HH:mm） */
        private val CLOCK_FORMAT =
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    }
}
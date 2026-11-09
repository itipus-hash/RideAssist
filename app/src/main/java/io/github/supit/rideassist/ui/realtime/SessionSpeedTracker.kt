package io.github.supit.rideassist.ui.realtime

import android.util.Log
import androidx.lifecycle.ViewModel
import io.github.supit.rideassist.core.gate.MoveGate
import io.github.supit.rideassist.util.SpeedDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        private set   // ⭐ 唯一写入口是 [noteSpeed]，禁止外部再直接赋值

    private val _speedRevision = MutableStateFlow(0L)

    /** 极速一刷新就通知悬浮窗重渲（退后台时悬浮窗没有别的刷新源） */
    val speedRevisionFlow: StateFlow<Long> = _speedRevision.asStateFlow()

    /**
     * ⭐⭐ 末次「有电流证据」的墙钟时刻（ms）。
     *
     * 静止时 GPS 漂移 1~5 km/h 不该被记进会话极速，电流是「起步」的可靠证据。
     * 这份状态**只在这里**（Application 级单例）维护一份。
     */
    @Volatile
    var lastCurrentEvidenceMs: Long = 0L
        private set

    /**
     * ⭐⭐⭐ **运动锁存器：速度门控的唯一一份状态**。
     *
     * 实时页与悬浮前台服务两条 GPS 订阅都调 [noteGpsSpeed] 喂数，
     * 读 [speedAllowed] 取结论；绝不各自持有锁存状态。
     * 判据实现在 [io.github.supit.rideassist.util.MotionLatch]。
     */
    private val motionLatch = io.github.supit.rideassist.util.MotionLatch()

    /**
     * 每帧 BMS 数据喂一次。只有「有设备数据 且 电流达到 [MoveGate.TH_CURRENT_A]」
     * 才算证据 —— 阈值复用全项目唯一判据，禁止另写 `abs(I) >= X`。
     * 断连帧不刷新也不清零，交给宽限期自然过期。
     */
    fun noteCurrentEvidence(absAmps: Float, hasDeviceData: Boolean) {
        if (hasDeviceData && absAmps >= MoveGate.TH_CURRENT_A) {
            lastCurrentEvidenceMs = System.currentTimeMillis()
        }
    }

    /** 「近期有电流」只作起步证据，不再直接决定速度是否归零 */
    private val hasCurrentEvidence: Boolean
        get() = System.currentTimeMillis() - lastCurrentEvidenceMs <=
            SpeedDisplay.CURRENT_EVIDENCE_GRACE_MS

    /**
     * ⭐⭐ **GPS 帧的唯一喂数点**（实时页 / 悬浮前台服务 / GPS 丢失推算都走这里）。
     * @return 本帧是否允许把 GPS 速度当真，直接传给 `SpeedDisplay.Filter.push()`
     */
    fun noteGpsSpeed(rawKmh: Float, nowMs: Long = System.currentTimeMillis()): Boolean =
        motionLatch.update(rawKmh, hasCurrentEvidence, nowMs)

    /** 不喂数、只查询：GPS 丢失推算 tick 与「是否跟踪 GPS 丢失」判据用 */
    val speedAllowed: Boolean get() = motionLatch.isAllowed(System.currentTimeMillis())

    /** 诊断面板用：当前是否处于「运动已锁存」 */
    val motionLatched: Boolean get() = motionLatch.isLatched()

    /** 手动复位运动锁存（清空会话 / 明确无 GPS 数据源时用） */
    fun resetMotionLatch() = motionLatch.reset()

    /**
     * ⭐⭐ **会话极速的唯一写入口**。
     * 实时页与悬浮前台服务两条 GPS 订阅都调它，口径由 SpeedDisplay.Filter 统一。
     * ⚠️ 传入的 shownKmh 必须已过 [speedAllowed] 门控，
     *    静止漂移应在 Filter 里就被压成 0。
     * @return true = 刷新了记录（触发订阅方重渲）
     */
    fun noteSpeed(shownKmh: Int): Boolean {
        if (shownKmh <= maxSpeed) return false
        maxSpeed = shownKmh
        _speedRevision.update { it + 1 }
        return true
    }

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

    /**
     * 一次**行程**里最大压差那一刻，偏离整体均值最多的那颗单体的快照。
     *
     * ⭐ 口径（用户 2026-11-09 修正）：**单次行程只记录一次**，
     * 且该行程压差峰值必须超过 [MIN_DIFF_HISTORY_MV] 才记。
     * 同一行程里压差再创新高，是**替换**这一条（换成更大压差那帧的快照），
     * 不是追加 —— 列表里一条 = 一次行程。
     *
     * @param tripId      所属行程序号（内部判定用，不显示）
     * @param diffMv      该行程当时的峰值压差（mV，内部判定用，不显示）
     * @param date        MM-dd（如 11-09）
     * @param cellIndex   单体序号，1-based（与峰值记录卡同口径）
     * @param cellVolt    该单体当时的电压（V，3 位小数显示）
     * @param deviationMv 相对其余电芯均值的偏离量绝对值（mV，1 位小数显示）
     */
    data class DiffHistoryEntry(
        val tripId: Long,
        val diffMv: Float,
        val date: String,
        val cellIndex: Int,
        val cellVolt: Float,
        val deviationMv: Float,
    )

    /**
     * ⭐ 最近 [MAX_DIFF_HISTORY] **次行程**的最大压差单体，**最新在最前**。
     *
     * ⚠️ 写入方是应用级采集协程，读取方是实时页主线程，
     * 所以用「不可变 List 整体替换」而不是原地修改 MutableList ——
     * 避免遍历渲染时被并发修改。
     *
     * ⭐⭐ 生命周期**与会话其它数据不同**：用户 2026-11-09 明确要求
     * 「重开 APP 也要一直能显示」。所以这块单独落盘（独立 SP 文件 `diff_history`），
     * 冷启动时由 YybmsApp 调 [restoreDiffHistory] 恢复，每次变化由
     * [diffHistorySink] 写回。曲线 / 峰值等仍遵守「退出 APP = 全新会话」，
     * 不受影响。[resetSession] 会把落盘一并清空（用户显式清空会话）。
     */
    @Volatile
    var diffHistory: List<DiffHistoryEntry> = emptyList()
        private set

    /**
     * ⭐ 历史变化时的落盘回调，参数是 [encodeDiffHistory] 的结果。
     *
     * 由 YybmsApp 注入；纯 JVM 单测里为 null，Tracker 本身不依赖 Android。
     * 只在「历史真的变了」时触发（不是每帧），所以 SP 写入很轻。
     */
    @Volatile
    var diffHistorySink: ((String) -> Unit)? = null

    /**
     * ⭐ 当前行程序号：记录状态机每完成一次「未记录 → 记录中」迁移就 +1。
     * 停车等红灯不会结束行程（[isRecording] 是锁存的），所以一次骑行只占一条。
     *
     * ⚠️ 恢复历史后会被抬到已有记录的最大 tripId，保证新行程编号不撞车
     *    （否则新进程又从 1 开始，会「替换」掉上次 APP 运行留下的第 1 条）。
     */
    @Volatile
    private var tripSeq: Long = 0L

    /** 同行程替换、新行程追加；列表始终最多 [MAX_DIFF_HISTORY] 条 */
    private fun appendDiffHistory(entry: DiffHistoryEntry) {
        val previous = diffHistory
        diffHistory = if (previous.firstOrNull()?.tripId == entry.tripId) {
            listOf(entry) + previous.drop(1)
        } else {
            listOf(entry) + previous.take(MAX_DIFF_HISTORY - 1)
        }
        persistDiffHistory()
    }

    // ==================== ⭐ 跨 APP 重启的落盘 / 恢复 ====================

    /**
     * 纯文本行格式：每行一条，`|` 分隔 6 个字段
     * `tripId|diffMv|date|cellIndex|cellVolt|deviationMv`。
     * 字段都是数字 / MM-dd，不含分隔符，无需转义；损坏行在恢复时跳过。
     */
    fun encodeDiffHistory(): String = diffHistory.joinToString("\n") { e ->
        listOf(
            e.tripId.toString(), e.diffMv.toString(), e.date,
            e.cellIndex.toString(), e.cellVolt.toString(), e.deviationMv.toString(),
        ).joinToString("|")
    }

    /**
     * 冷启动恢复（YybmsApp 创建 Application 级单例时调用一次）。
     *
     * - 只保留能完整解析的行，任何字段坏掉就跳过那一行，不让一条脏数据毁掉整页；
     * - 按 tripId 倒序（最新在最上），最多取 [MAX_DIFF_HISTORY] 条；
     * - [tripSeq] 抬到已有最大 tripId，下一次行程才会用全新编号追加，
     *   不会把上一次 APP 运行的记录当成「同一行程」替换掉。
     */
    fun restoreDiffHistory(raw: String) {
        if (raw.isBlank()) return
        val rows = raw.lineSequence().mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 6) return@mapNotNull null
            val tripId = p[0].toLongOrNull() ?: return@mapNotNull null
            val diffMv = p[1].toFloatOrNull() ?: return@mapNotNull null
            val cellIndex = p[3].toIntOrNull() ?: return@mapNotNull null
            val cellVolt = p[4].toFloatOrNull() ?: return@mapNotNull null
            val deviationMv = p[5].toFloatOrNull() ?: return@mapNotNull null
            DiffHistoryEntry(
                tripId = tripId, diffMv = diffMv, date = p[2],
                cellIndex = cellIndex, cellVolt = cellVolt, deviationMv = deviationMv,
            )
        }
            // 按行程序号倒序 = 最新在最上；即使落盘串的行序被写乱也能自愈
            .sortedByDescending { it.tripId }
            .take(MAX_DIFF_HISTORY)
            .toList()
        diffHistory = rows
        // ⭐ 两份历史共用 tripSeq，只能往上抬（详见 [bumpTripSeq]）
        bumpTripSeq(rows.map { it.tripId })
    }

    /** 只在真正变化时落盘；没有注入回调（纯 JVM 单测）时是 no-op */
    private fun persistDiffHistory() {
        diffHistorySink?.invoke(encodeDiffHistory())
    }

    // ==================== ⭐ 极速历史（用户 2026-11-10） ====================

    /**
     * 一次行程里**超过阈值的最大速度**那一刻的快照。
     * 显示格式（@string/top_speed_history_row）：`11-10 极速 55Km/h @ 11:34`
     *
     * @param tripId   所属行程序号（与压差历史共用一套编号，不显示）
     * @param speedKmh  该行程当时的峰值速度（整数，与页面显示口径一致）
     * @param date      MM-dd
     * @param clock     HH:mm，达到该速度的那一刻
     */
    data class TopSpeedHistoryEntry(
        val tripId: Long,
        val speedKmh: Int,
        val date: String,
        val clock: String,
    )

    /**
     * 两项记录阈值。**由 YybmsApp 注入「实时读 AppPrefs」的 provider**：
     * 用户在连接设置页改完数字，下一帧就生效，不需要重建 tracker；
     * 纯 JVM 单测不注入时用 [MIN_DIFF_HISTORY_MV] / [MIN_TOP_SPEED_HISTORY_KMH]。
     *
     * ⚠️ 阈值只决定「记不记历史」，不影响会话峰值卡与曲线。
     */
    data class RecordThresholds(val diffMv: Float, val topSpeedKmh: Float)

    @Volatile
    var thresholdsProvider: (() -> RecordThresholds)? = null

    private fun thresholds(): RecordThresholds = thresholdsProvider?.invoke()
        ?: RecordThresholds(MIN_DIFF_HISTORY_MV, MIN_TOP_SPEED_HISTORY_KMH)

    /** 当前生效的阈值（实时页两张历史卡的空态文案用） */
    val currentThresholds: RecordThresholds get() = thresholds()

    /**
     * ⭐ 最近 [MAX_TOP_SPEED_HISTORY] 次行程的极速，**最新在最前**。
     * 与 [diffHistory] 同口径：一条 = 一次行程、独立 SP 落盘、跨 APP 重启保留。
     */
    @Volatile
    var topSpeedHistory: List<TopSpeedHistoryEntry> = emptyList()
        private set

    /** 历史变化时的落盘回调（参数是 [encodeTopSpeedHistory] 的结果） */
    @Volatile
    var topSpeedHistorySink: ((String) -> Unit)? = null

    private fun appendTopSpeedHistory(entry: TopSpeedHistoryEntry) {
        val previous = topSpeedHistory
        topSpeedHistory = if (previous.firstOrNull()?.tripId == entry.tripId) {
            listOf(entry) + previous.drop(1)          // 同行程：替换成更高的速度
        } else {
            listOf(entry) + previous.take(MAX_TOP_SPEED_HISTORY - 1)
        }
        persistTopSpeedHistory()
    }

    private fun persistTopSpeedHistory() {
        topSpeedHistorySink?.invoke(encodeTopSpeedHistory())
    }

    /** `tripId|speedKmh|date|clock`；字段不含分隔符，坏行恢复时跳过 */
    fun encodeTopSpeedHistory(): String = topSpeedHistory.joinToString("\n") { e ->
        listOf(
            e.tripId.toString(), e.speedKmh.toString(), e.date, e.clock,
        ).joinToString("|")
    }

    fun restoreTopSpeedHistory(raw: String) {
        if (raw.isBlank()) return
        val rows = raw.lineSequence().mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 4) return@mapNotNull null
            val tripId = p[0].toLongOrNull() ?: return@mapNotNull null
            val speed = p[1].toIntOrNull() ?: return@mapNotNull null
            TopSpeedHistoryEntry(tripId, speed, p[2], p[3])
        }
            .sortedByDescending { it.tripId }
            .take(MAX_TOP_SPEED_HISTORY)
            .toList()
        topSpeedHistory = rows
        bumpTripSeq(rows.map { it.tripId })
    }

    /**
     * ⭐ 两份历史**共用** [tripSeq]，恢复时只能往上抬、不能压回去：
     * 否则后恢复的那份会把编号改小，新行程会「替换」掉另一份里的旧记录。
     */
    private fun bumpTripSeq(ids: List<Long>) {
        var max = tripSeq
        for (id in ids) if (id > max) max = id
        tripSeq = max
    }

    /**
     * ⭐⭐ **极速历史的唯一写入口**，挂在 [updatePeak] 的**会话峰值门控之前**
     * （与 [updateDiffHistory] 同理：挂在门控之后，第 2 次行程没破会话极速
     * 就一条都记不到）。
     *
     * 三条规则与压差历史完全对称：
     * 1. 不在行程里不记；2. 速度没**严格大于**阈值不记；
     * 3. 同行程只在刷新本行程峰值时替换，一条 = 一次行程。
     */
    private fun updateTopSpeedHistory(valueKmh: Float, now: java.util.Date) {
        val trip = tripSeq
        if (!isRecording || trip <= 0L) return
        val speed = valueKmh.toInt()             // 与页面水印 / 峰值卡一致的整数口径
        if (speed.toFloat() <= thresholds().topSpeedKmh) return
        val current = topSpeedHistory.firstOrNull { it.tripId == trip }
        if (current != null && speed <= current.speedKmh) return
        appendTopSpeedHistory(
            TopSpeedHistoryEntry(
                tripId = trip,
                speedKmh = speed,
                date = DATE_FORMAT.format(now),
                clock = CLOCK_FORMAT.format(now),
            )
        )
    }

    /**
     * ⭐⭐ **压差历史的唯一写入口**（用户 2026-11-09 修正的口径）。
     *
     * 三条硬规则：
     * 1. **单次行程只记录一次** —— 同一行程里压差再创新高，是**替换**这一条，
     *    内容换成「该行程压差最大那一帧」的快照；
     * 2. **压差没超过记录阈值（默认 50mV，可在连接设置页改）不记录** ——
     *    阈值由 [thresholds] 实时读 AppPrefs；`cellDiff` 单位就是 mV
     *    （见 YybmsPayloadParser：`(max-min)*1000f`）；
     * 3. **行程以记录状态机为准** —— 一次 `isRecording: false→true` 是一次新行程，
     *    停车等红灯不会结束行程（锁存），所以一次骑行不会冒出好几条。
     *
     * ⚠️⚠️ **必须独立于会话峰值门控**：[updatePeak] 只在「破了整段会话最大值」
     * 时才继续往下走。若把历史塞在那个分支里，第 2 次行程压差没破第 1 次的
     * 会话纪录就一条都不记 —— 这恰恰与「一次行程一条」矛盾。
     * 所以本方法在 [updatePeak] 的最前面调用，用**本行程**的峰值做比较，
     * 与 [peakRecords] 的会话峰值互不干扰。
     */
    private fun updateDiffHistory(
        valueMv: Float,
        d: io.github.supit.rideassist.core.protocol.model.BmsRealtimeData,
        now: java.util.Date,
    ) {
        val trip = tripSeq
        if (!isRecording || trip <= 0L) return          // 不在行程里 → 不记
        if (valueMv <= thresholds().diffMv) return      // 没超过记录阈值 → 不记
        val v = d.cellVoltages
        if (d.cellCount < 2 || v.size < 2) return       // 无「压差」可言

        // 同行程只在**本行程**压差创新高时替换；否则保留峰值那帧的快照
        val current = diffHistory.firstOrNull { it.tripId == trip }
        if (current != null && valueMv <= current.diffMv) return

        val outlier = findOutlierCell(v)
        appendDiffHistory(
            DiffHistoryEntry(
                tripId = trip,
                diffMv = valueMv,
                date = DATE_FORMAT.format(now),
                cellIndex = outlier.first + 1,
                cellVolt = if (outlier.first >= 0) v[outlier.first] else 0f,
                deviationMv = kotlin.math.abs(outlier.second) * 1000f,
            )
        )
    }

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
    private val _peakRevision = MutableStateFlow(0L)

    /** ⭐ 峰值版本号做成 Flow：悬浮窗订阅它，峰值一变立刻重渲（不再慢一帧） */
    val peakRevisionFlow: StateFlow<Long> = _peakRevision.asStateFlow()

    /** 供骑行页做「跳过无变化重绘」的同步读取 */
    val peakRevision: Long get() = _peakRevision.value

    /**
     * ⭐ 最近一次 GPS 低通速度（km/h，未取整），由实时页喂入。
     * 应用级采集器在**退后台**（实时页 onPause 会停 GPS）后读它做
     * MoveGate 的第三路证据；置 null = 无速度信号，判据只走电流/功率。
     */
    @Volatile
    var liveSpeedKmh: Float? = null

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
            motionLatched = motionLatched,
            speedAllowed = speedAllowed,
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
        /** 运动锁存是否成立（速度门控的唯一状态，诊断面板用它排查「松油门归零」） */
        val motionLatched: Boolean,
        /** 当前是否允许把 GPS 速度当真（含「长时间无 GPS 帧自动解锁」） */
        val speedAllowed: Boolean,
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
     * @param nowMs 墙钟时刻；默认取系统时间，纯 JVM 单测可注入以模拟「断连超时」
     * @return true = 状态发生了迁移（调用方需要重绘 UI）
     */
    fun updateRecordingState(
        dischargingNow: Boolean,
        hasDeviceData: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val before = isRecording
        if (hasDeviceData) lastDataWallMs = nowMs

        if (dischargingNow) {
            // 启动条件：放电 >= 阈值。**一旦启动就不再因电流回落而停止。**
            isRecording = true
        } else if (isRecording) {
            // 停止条件：**只有断连超时**才停止。
            // 用「距末次有效数据」判断，而不是「本次电流为 0」——
            // 后者在滑行/等红灯时会误停。
            val last = lastDataWallMs
            val disconnectedTooLong = last == 0L ||
                nowMs - last >= STOP_AFTER_DISCONNECT_MS
            if (disconnectedTooLong) isRecording = false
        }

        // ⭐ 一次「未记录 → 记录中」= 一次新行程。
        //    tripSeq 是压差历史「一条 = 一次行程」的唯一编号来源；
        //    停车等红灯时 isRecording 保持 true，不会重复开行程。
        if (!before && isRecording) tripSeq++
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
        // ⭐ 同一帧只取一次时间：HH:mm 给峰值卡、MM-dd 给压差历史，
        //    跨零点时两处才不会对不上。
        val now = java.util.Date()

        // ⭐⭐ 压差历史按**行程**统计，必须放在会话峰值门控之前
        //    （详见 [updateDiffHistory] 的说明：门控之后会漏掉整个行程）。
        if (index == CARD_DIFF) updateDiffHistory(value, d, now)

        // ⭐⭐ 极速历史同理：按行程统计，必须放在会话峰值门控之前，
        //    否则第 2 次行程没破整段会话极速就一条都记不到。
        if (index == CARD_SPEED) updateTopSpeedHistory(value, now)

        val cur = peakRecords[index]
        if (cur != null && value <= cur.value) return false    // 没超过历史最大

        val clock = CLOCK_FORMAT.format(now)
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
            val rec = PeakRecord(
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
            // ⚠️ 压差历史**不在这里**写：它按行程统计，与会话峰值不同口径，
            //    已在方法开头由 [updateDiffHistory] 独立处理。
            rec
        } else {
            PeakRecord(value = value, clock = clock)
        }
        _peakRevision.update { it + 1 }
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


    // ==================== ⭐ 会话重置 ====================

    /**
     * 手动清空全部会话数据（数据页底部的「清空本次会话」按钮用）。
     *
     * 曲线队列也一并清掉 —— 否则会出现「累加器归零但曲线还在」的自相矛盾。
     *
     * ⚠️ 压差历史 / 极速历史是**跨 APP 重启持久化**的（用户要求一直能显示），
     *    所以这里必须同步把落盘写成空串；否则用户明明点了清空，
     *    重开 APP 旧记录又会「复活」。
     */
    fun resetSession() {
        maxSpeed = 0
        lastCurrentEvidenceMs = 0L
        motionLatch.reset()
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
        speedPoints.clear()
        powerPoints.clear()
        currentPoints.clear()
        lastDischargeWallMs = 0L
        diffPoints.clear()
        diffHistory = emptyList()
        topSpeedHistory = emptyList()
        tripSeq = 0L             // ⭐ 会话清空 = 行程计数重新从 1 开始
        persistDiffHistory()     // ⭐ 同步清掉落盘，避免重开 APP 旧记录「复活」
        persistTopSpeedHistory() // ⭐ 极速历史同口径：清空也要落盘
        java.util.Arrays.fill(peakRecords, null)
        _peakRevision.update { it + 1 }
        // ⭐ maxSpeed 也归零了，必须通知订阅方（悬浮窗的「极速」），
        //    否则清空会话后悬浮窗还挂着旧数字。
        _speedRevision.update { it + 1 }
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
         * ⭐⭐ **会话内采样点硬上限：30000**（保险丝，不是设计目标）。
         *
         * 用户 2026-11-08 明确两条：历史曲线「不会因为数据滚出屏幕就丢失」、
         * 「退出 APP 后不保留」。所以不再按时间窗口截断 —— 一次会话内全部保留，
         * 退出 APP（进程结束）自然清零，不需要落盘。
         *
         * 30000 点是防止极端长会话把队列撑爆：4 条 × 30000 × 4B ≈ 480KB，
         * 真触顶也只是丢最老的点，不会 OOM。
         * ⚠️ 实际采样率不止 1Hz（每个合法 BMS 回包都会 emit 一次 `_realtime`），
         * 30000 点大约对应 2~8 小时，足够覆盖任何单次骑行。
         *
         * ⚠️ 渲染时**不要**每帧 `q.toFloatArray()` 全量拷贝 ——
         * 实时曲线只取尾部窗口（见 `SeriesMath.tail`）。
         */
        const val MAX_POINTS = 30_000

        /**
         * 实时模式的统计窗口：Max/Min 小字只看最近这么多点。
         *
         * ⭐ 队列改成全会话保留后，如果不限窗口，实时页的 Min 会变成
         * 「整段行程最低」——那是历史模式的语义。实时模式要反映近期状态。
         * 1800 点 ≈ 最近 30 分钟（按 1Hz 折算）。
         */
        const val RECENT_WINDOW = 1_800

        // ==================== Max/Min 持久化 ====================


        /** 压差历史保留条数（用户指定 10 条 = 最近 10 次行程，最新在最上） */
        const val MAX_DIFF_HISTORY = 10

        /**
         * ⭐ **压差历史的最小压差（mV）**：**没超过 50mV 不记录**（用户 2026-11-09）。
         *
         * `cellDiff` 单位本就是 mV（YybmsPayloadParser：`(max-min)*1000f`），
         * 用严格大于判断：恰好 50mV 也不记。
         * 阈值只作用于**历史卡**，峰值记录卡仍照常显示小压差，不改变旧行为。
         *
         * ⚠️ 2026-11-10 起这是**默认值**：实际阈值由连接设置页的
         *    「最大压差记录阈值（mV）」决定，经 [thresholdsProvider] 实时读取；
         *    仅在未注入 provider（纯 JVM 单测）时作为兜底。
         */
        const val MIN_DIFF_HISTORY_MV = 50f

        /** 极速历史保留条数（最近 10 次行程，最新在最上） */
        const val MAX_TOP_SPEED_HISTORY = 10

        /**
         * ⭐ **极速历史的最小速度（Km/h）**：没超过 50Km/h 不记录。
         *
         * 与 [MIN_DIFF_HISTORY_MV] 一样只是**默认值**，实际由连接设置页的
         * 「极速记录阈值（Km/h）」决定，经 [thresholdsProvider] 实时读取。
         */
        const val MIN_TOP_SPEED_HISTORY_KMH = 50f

        /**
         * **停止记录**的判据：断开保护板连接超过这么久。
         *
         * 与「断开保护板 3 分钟」是同一个时长（用户指定）。
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

        /** 压差历史的日期格式化（MM-dd，如 11-09） */
        private val DATE_FORMAT =
            java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
    }
}
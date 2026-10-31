package io.github.supit.rideassist.core.gate

import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData

/**
 * ============================================================================
 *  ⭐⭐⭐ 全项目**唯一**的「是否在移动」判定入口
 * ============================================================================
 *
 * ## 存在的理由（这是本项目踩坑最集中的一类问题）
 *
 * 本项目发生过两次「同一物理量、不同路径、判据不一致」的严重 bug：
 *
 * | # | 现象 | 根因 |
 * |---|---|---|
 * | 1 | GPS 丢失穿隧道被算成停车 → 百公里电耗虚高 | `moving` 只看电流 |
 * | 2 | 停车时「预计可行驶里程」不降反升 | `distanceKm` 无门控、`energyWh` 只判 `power>0` |
 *
 * 两次都是**判据分裂**而非算法错误。所以本类把判据收敛成一处：
 * 任何需要「在动 / 静止」语义的地方，**必须**调 [evaluate]，
 * 禁止再自行写 `abs(current) >= X` 或 `speed >= Y`。
 *
 * ## 三路证据（任一成立即认为在动）
 *
 * | 证据 | 判据 | 覆盖场景 |
 * |---|---|---|
 * | 1 电流 | `abs(I) >= [TH_CURRENT_A]` | 常规骑行、爬坡 |
 * | 2 功率 | `P >= [TH_POWER_W]` | ⭐ GPS 丢失穿隧道（速度不可用） |
 * | 3 速度 | `v >= [TH_SPEED_KMH]` | 低速蠕行（电流尚未起来） |
 *
 * 取「或」而非「与」：三者是**冗余**证据，任何一路失效都不能导致误判为静止。
 *
 * ## 门控契约（所有派生指标必须遵守）
 *
 * 以 `moving` 为门控的量：
 * - 累计里程 `distanceKm`
 * - 累计耗能 `energyWh`
 * - 回收电量 `regenWh`
 * - 骑行时长 `rideMovingMs`
 *
 * ⚠️ **分子分母必须共用同一套门控**。曾出现 `distanceKm` 用 `moving`
 * 而 `energyWh` 用 `power>0`，静止时分子冻结分母增长 → 电耗持续变小。
 */
object MoveGate {

    // ==================== 阈值（全部集中于此，不再散落各处） ====================

    /**
     * 电流阈值（A）—— 证据 1。
     *
     * 协议事实：充电为负、放电为正，所以取**绝对值**，充电也视为在动。
     *
     * ⚠️ 阈值来源：**协议口径**。与记录状态机的启动条件同源，
     * 沿用官方/用户既定的 1A（霍尔传感器在该量级已有可信分辨率）。
     */
    const val TH_CURRENT_A: Float = 1.0f

    /**
     * 功率阈值（W）—— 证据 2。
     *
     * ⭐⭐ **阈值来源：量纲估算，尚未实机标定**（诊断面板会显式标注这一点）。
     *
     * 为什么必须有这一路：穿隧道 / 高架桥下 / 城市峡谷时 GPS 收不到星，
     * `speed` 证据直接失效；但车明明在 25km/h 行驶，电机功率高达数百瓦。
     * 只看电流在「电机高效率区」可能电流反而偏低（大扭矩低转速效率高），
     * 所以功率是独立且必要的一路。
     *
     * 150W 的依据：城市助力车巡航功率典型 150~400W，
     * 取下沿保证「明显在推车」才算在动，滤掉静态助力/仪表供电。
     *
     * ⚠️ 日后可回调：实测后改这一个常量即可，全项目自动跟随。
     */
    const val TH_POWER_W: Float = 150f

    /**
     * 速度阈值（km/h）—— 证据 3。
     *
     * ⚠️ 阈值来源：**量纲估算，尚未实机标定**。
     * 取 5km/h 是与页面「低于 5km/h 显示 0」的既有口径一致，
     * 让门控与显示同源 —— 否则会出现「页面显 0 但里程在涨」的自相矛盾。
     */
    const val TH_SPEED_KMH: Float = 5f

    // ==================== 判定结果 ====================

    /**
     * 一次判定的完整结论 —— 不只给布尔值，还要给**证据**。
     *
     * 诊断面板要显示「当前是哪一路判定的」，否则阈值调错时无从查起。
     */
    data class Verdict(
        /** 最终结论：是否在动 */
        val moving: Boolean,
        /** 命中的判据名，用于诊断显示；无命中为 null */
        val reason: String?,
        /** 三路原始证据值，便于诊断面板并排对照 */
        val absCurrent: Float,
        val power: Float,
        val speed: Float,
    )

    /**
     * ⭐ **唯一的移动判定实现**。
     *
     * @param speedKmh 速度（km/h），`null` 表示当前无可用速度信号
     *   （无权限 / GPS 失锁 / 定位关闭）—— 注意与「速度为 0」是两回事，
     *   前者要跳过速度证据，后者要走速度证据但阈值不满足。
     */
    fun evaluate(d: BmsRealtimeData, speedKmh: Float?): Verdict {
        val amps = kotlin.math.abs(d.totalCurrent)
        val power = d.power
        val speed = speedKmh ?: 0f

        if (amps >= TH_CURRENT_A) {
            return Verdict(true, "电流 $amps%.1fA ≥ ${TH_CURRENT_A}A", amps, power, speed)
        }
        if (power >= TH_POWER_W) {
            return Verdict(true, "功率 ${power.toInt()}W ≥ ${TH_POWER_W.toInt()}W", amps, power, speed)
        }
        if (speedKmh != null && speed >= TH_SPEED_KMH) {
            return Verdict(true, "速度 ${speed.toInt()}km/h ≥ ${TH_SPEED_KMH.toInt()}km/h", amps, power, speed)
        }
        return Verdict(false, null, amps, power, speed)
    }

    /**
     * 门控对照表 —— 直接渲染到诊断面板。
     *
     * ⭐ 目的不是好看，而是**把「阈值从哪来」钉在代码里**。
     * 本项目有过「拍脑袋阈值忘了标定」导致后续误判的隐患，
     * 凡是未经实机标定的估计值，这里一律显式写「未标定」。
     */
    fun gateTable(): List<Row> = listOf(
        Row("累计里程 distanceKm", "moving", "%.1f km", "协议"),
        Row("累计耗能 energyWh", "moving", "%.2f Wh", "协议"),
        Row("回收电量 regenWh", "moving", "%.2f Wh", "协议"),
        Row("骑行时长 rideMovingMs", "moving", "时长", "协议"),
        Row("平均速度 avgSpeed", "moving", "%.1f km/h", "协议"),
        Row("证据1 电流", "abs(I) ≥ %.1fA".format(TH_CURRENT_A), "", "协议口径"),
        Row("证据2 功率", "P ≥ %.0fW".format(TH_POWER_W), "", "⚠ 量纲估算·未标定"),
        Row("证据3 速度", "v ≥ %.0fkm/h".format(TH_SPEED_KMH), "", "⚠ 量纲估算·与显示同源"),
    )

    data class Row(val item: String, val gate: String, val fmt: String, val source: String)
}
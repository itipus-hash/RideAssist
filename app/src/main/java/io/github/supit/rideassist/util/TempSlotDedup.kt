package io.github.supit.rideassist.util

/**
 * ⭐ 温度卡探头槽位的去重判据 —— **唯一实现**。
 *
 * 用户口径（2026-11-08）：「除了 MOS 外的两个温度的数值一样，就只显示一个，
 * 除非他们数值不一样才显示成两个」。固件可能把同一路探头的值填进 T1/T2
 * 两个字节（诊断面板「温度探头独立性」验证过），两路并排显示同一个数值
 * 没有信息量。
 *
 * ⚠️ 传进来的必须是**已格式化好的显示值**（如 `25.0℃`），不是原始浮点：
 * 协议虽是 1℃ 粒度，解析仍可能带 0.1 抖动，按显示口径比较才不会在临界点
 * 反复合并 / 拆分导致视图抖动。
 *
 * ⚠️ 判据放在纯 Kotlin 里而不是 Fragment 里，是为了能在 JVM 单测中覆盖 ——
 * 实机「未连接保护板」时温度全为 null，截图根本验证不了合并逻辑
 * （本项目踩过「截图分不清改对了和根本没数据」的坑）。
 */
object TempSlotDedup {

    /**
     * @param displayValues 按槽位顺序的显示值；**下标 0 固定是 MOS，不参与去重**；
     *   null = 无读数，不参与去重也不被隐藏。
     * @return 与入参等长的数组，true = 该槽位应隐藏。
     */
    fun hiddenIndices(displayValues: List<String?>): BooleanArray {
        val hidden = BooleanArray(displayValues.size)
        val seen = HashSet<String>()
        for (i in 1 until displayValues.size) {
            val v = displayValues[i] ?: continue
            if (!seen.add(v)) hidden[i] = true
        }
        return hidden
    }
}

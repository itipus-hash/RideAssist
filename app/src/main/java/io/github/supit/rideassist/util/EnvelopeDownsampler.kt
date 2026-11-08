package io.github.supit.rideassist.util

/**
 * ⭐⭐ **按像素列取 min/max 包络的降采样**（用户 2026-11-08 选定的方案 B②）。
 *
 * ## 为什么不用 LTTB / 道格拉斯-普克
 * 速度曲线是**孤立单点尖峰 + 急刹突变**形态，而 LTTB 按三角形面积选点、
 * 视觉平滑但**不保证**孤立尖峰被选中；DP 保拐点，但阈值要调、极值也只是
 * 「通常」能保住，仍需额外加「强制峰值锚点」。
 *
 * min/max 包络不一样：把 N 个原始点按目标列数分桶，每桶只保留
 * **该桶内最大点和最小点**，按原索引顺序连接 —— 于是
 * **全局最大值所在桶的最大点就是它本人**，数学上不可能被抹掉；
 * 谷值、急刹突变同理。没有阈值、没有调参、没有「通常」。
 *
 * ## 边界保证
 * - 首点、末点强制保留（否则曲线两端会缺一截）
 * - 相邻桶按原索引顺序写入 → 连线不会出现回头 / 断层
 * - 输出点数 ≤ 2 × 目标列数 + 2，绘制压力与原始窗口同一量级
 *
 * ## 什么时候不压缩
 * `n <= targetColumns * 2` 时**原样返回同一个数组**（不拷贝、不改动），
 * 短行程保持 100% 原始采样点。
 *
 * 纯 JVM，无 Android 依赖 —— 极值是否真的没丢，用单测钉死。
 */
object EnvelopeDownsampler {

    /**
     * @param values 原始采样（按时间顺序）
     * @param targetColumns 目标列数，通常取画布可用宽度（px）
     * @return 压缩后的采样；点数不足时**返回入参本身**
     */
    fun downsample(values: FloatArray, targetColumns: Int): FloatArray {
        val n = values.size
        val cols = targetColumns.coerceAtLeast(2)
        if (n <= cols * 2) return values

        val out = ArrayList<Float>(cols * 2 + 2)
        out.add(values[0])
        var lastIndex = 0

        for (c in 0 until cols) {
            val from = (c.toLong() * n / cols).toInt()
            val to = ((c + 1).toLong() * n / cols).toInt().coerceAtMost(n)
            if (to <= from) continue

            var maxI = from
            var minI = from
            for (i in from until to) {
                if (values[i] > values[maxI]) maxI = i
                if (values[i] < values[minI]) minI = i
            }

            // 按索引顺序写入：先出现的先写，保证折线不会回头
            if (maxI == minI) {
                if (maxI != lastIndex) {
                    out.add(values[maxI])
                    lastIndex = maxI
                }
            } else {
                val first = minOf(maxI, minI)
                val second = maxOf(maxI, minI)
                if (first != lastIndex) {
                    out.add(values[first])
                    lastIndex = first
                }
                if (second != lastIndex) {
                    out.add(values[second])
                    lastIndex = second
                }
            }
        }

        // 末点强制保留（最后一桶的极值未必落在最后一个索引上）
        if (lastIndex != n - 1) out.add(values[n - 1])
        return out.toFloatArray()
    }
}

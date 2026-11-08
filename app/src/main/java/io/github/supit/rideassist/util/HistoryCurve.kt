package io.github.supit.rideassist.util

/**
 * ⭐ 历史曲线的 Y 映射 —— 「颜色 = 数值」的唯一判据。
 *
 * 历史模式用**沿画布 Y 轴**的 LinearGradient 上色：shader 的 0f 在顶部
 * （暖色，橙红）、1f 在底部（冷色，青绿）。所以只要把**最大值**映射到
 * 画布顶部、**最小值**映射到底部，颜色就自然与数值对应 ——
 * 高的地方红、低的地方绿，不需要逐点算颜色。
 *
 * 这个映射一旦写反（min 到顶部），整张图会变成「高处绿、低处红」：
 * 图还是能看，但语义全错，而且很难一眼发现。所以抽成纯函数用单测钉死。
 */
object HistoryCurve {

    /**
     * @return 0f = 数值最小（画布**底部**，渐变冷色端）
     *         1f = 数值最大（画布**顶部**，渐变暖色端）
     *
     * 全平数据（max == min）返回 0f 贴底：
     * 没有可比区间时不该让它看起来像「满值」。
     */
    fun verticalRatio(value: Float, min: Float, max: Float): Float {
        val range = max - min
        if (range <= 0f) return 0f
        return ((value - min) / range).coerceIn(0f, 1f)
    }
}

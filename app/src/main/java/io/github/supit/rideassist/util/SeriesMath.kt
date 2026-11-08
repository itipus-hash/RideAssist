package io.github.supit.rideassist.util

import java.util.ArrayDeque

/**
 * 曲线采样队列的取数工具。
 *
 * ## 为什么需要它
 * `java.util.ArrayDeque` **不支持按下标随机访问**（项目里踩过：需要时先
 * `toList()`）。而曲线数据现在**不再截断**（会话内全量保留，见
 * `SessionSpeedTracker.MAX_POINTS`），每帧 `q.toFloatArray()` 会把几万个点
 * 全拷一遍 —— 1Hz 下虽然不至于卡，但每次分配几十 KB 纯属浪费。
 *
 * 实时曲线只需要**尾部一个窗口**（TrendChartView 自己还会按 6dp 步长再裁到
 * 约 54 点），所以只取尾部 n 个点即可，O(n) 且只分配一次。
 */
object SeriesMath {

    /**
     * 取队列**尾部** n 个点（按原时间顺序）。不足 n 时返回全部。
     *
     * 用 `descendingIterator` 反向填充定长数组，避免整体拷贝后再切片。
     */
    fun tail(values: ArrayDeque<Float>, n: Int): FloatArray {
        if (values.isEmpty() || n <= 0) return FloatArray(0)
        val take = if (values.size <= n) values.size else n
        val out = FloatArray(take)
        var i = take - 1
        val it = values.descendingIterator()
        while (i >= 0 && it.hasNext()) {
            out[i] = it.next()
            i--
        }
        return out
    }

    /** 取全部点（历史模式用）。与 [tail] 同源，避免调用方自己拼两条路。 */
    fun all(values: ArrayDeque<Float>): FloatArray = tail(values, Int.MAX_VALUE)
}

package io.github.supit.rideassist.util

import java.util.ArrayDeque

/**
 * ⭐⭐ **「位置到底动没动」的粗判据** —— 用来否决 GPS 速度噪声。
 *
 * ## 为什么 `Location.getSpeed()` 不够
 * 静止时 GPS 芯片仍会解算出 1~9km/h 的速度（贴窗、高楼下、刚定位时更明显）。
 * 单纯靠「速度持续超过阈值 N 秒」总有概率漏过去，而且一旦进了运动锁存，
 * 锁存的保持条件**只看速度**，噪声就会自己维持自己 —— 表现为一条停不下来的假曲线。
 *
 * 但 `getSpeed()` 不可信不代表位置不可信：静止时**连续定位点的位移**几乎为 0。
 * 所以这里用「窗口首点 → 当前点」的直线位移除以时间跨度，得到一个
 * **平均位移速度**：静止时接近 0，真骑行时明显为正。它噪声大、不能当速度显示，
 * 但用来判断「到底有没有在移动」足够硬。
 *
 * ## 只在「平均位移速度明显偏低」时否决
 * 阈值取 6km/h：慢于这个速度本来就该显示 0（`SpeedDisplay.ZERO_THRESHOLD_KMH=5`），
 * 否决不会误伤真骑行；而静止时哪怕 `getSpeed()` 报 20km/h，位移平均速度也只有 0~3。
 *
 * ## 为什么要跨度足够才给结论
 * 刚进页面时窗口里只有一个点，位移恒为 0 —— 若此时判定「静止」，
 * 起步阶段会被永久否决。所以跨度不足 [MIN_SPAN_MS] 时返回 -1（= 不下结论）。
 *
 * 纯 JVM（Haversine 自己算，不依赖 Android `Location`），可用单测钉死。
 */
class GpsMotionProbe(private val windowMs: Long = WINDOW_MS) {

    private class Fix(val lat: Double, val lon: Double, val timeMs: Long)

    private val fixes = ArrayDeque<Fix>()

    @Synchronized
    fun reset() = fixes.clear()

    /**
     * 喂一帧定位。
     *
     * @return 窗口内「平均位移速度」(km/h)；样本跨度不足时返回 **-1**（不下结论）
     */
    @Synchronized
    fun accept(lat: Double, lon: Double, timeMs: Long): Float {
        fixes.addLast(Fix(lat, lon, timeMs))
        // 至少留 2 个点；超出窗口的旧点滚出
        while (fixes.size > 2 && timeMs - fixes.peekFirst().timeMs > windowMs) {
            fixes.removeFirst()
        }
        val first = fixes.peekFirst() ?: return -1f
        val span = timeMs - first.timeMs
        if (span < MIN_SPAN_MS) return -1f
        val meters = distanceMeters(first.lat, first.lon, lat, lon)
        // m / ms * 1000 = m/s；* 3.6 = km/h
        return (meters / span * 1000.0 * 3.6).toFloat()
    }

    companion object {
        /** 滑动窗口长度：太短被单帧跳点带偏，太长则停下后迟迟不给结论 */
        const val WINDOW_MS = 10_000L

        /** 至少积累这么长的跨度才敢下「动没动」的结论 */
        const val MIN_SPAN_MS = 8_000L

        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Haversine 球面距离（米） */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a))
        }
    }
}

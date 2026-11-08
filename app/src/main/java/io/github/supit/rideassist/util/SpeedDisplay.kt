package io.github.supit.rideassist.util

/**
 * ⭐⭐ **速度显示口径的唯一实现**。
 *
 * 实时页（曲线/水印/MoveGate 速度证据）与悬浮前台服务（退后台后维持会话极速）
 * 是两条独立的 GPS 订阅，但「低通系数 / 5km/h 归零 / 取整」必须完全一致，
 * 否则同一时刻两处显示的 Max 会对不上（本项目「判据分裂」类 bug 的老坑）。
 *
 * 每个订阅源各自持有一个 [Filter] 实例（低通状态不能共享，否则同一帧
 * 被两个回调各算一次会打乱序列）；口径常量则只有这一处。
 */
object SpeedDisplay {

    /** 低于该速度显示 0 —— 静止时 GPS 仍有 1~3km/h 漂移，不归零会锯齿 */
    const val ZERO_THRESHOLD_KMH = 5f

    /** 指数低通系数，越小越平滑 */
    const val SMOOTH_ALPHA = 0.3f

    class Filter {
        private var filtered: Float? = null

        /** 低通后的**真实浮点速度**（km/h），积分 / MoveGate 用；null = 尚无速度信号 */
        val realSpeed: Float? get() = filtered

        /** 喂一帧原始速度，返回与页面水印一致的**显示值**（整数 km/h） */
        fun push(rawKmh: Float): Int {
            val f = filtered
            filtered = if (f == null) rawKmh else f + (rawKmh - f) * SMOOTH_ALPHA
            val v = filtered ?: 0f
            return if (v < ZERO_THRESHOLD_KMH) 0 else v.toInt()
        }

        /** GPS 源断开 / 无定位权限时清空，避免下次接着陈旧值平滑 */
        fun reset() { filtered = null }
    }
}

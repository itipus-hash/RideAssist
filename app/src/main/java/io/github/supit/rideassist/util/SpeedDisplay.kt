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
 *
 * ## ⭐⭐ 运动门控（2026-11-09 重做）
 *
 * 「是否允许把 GPS 速度当真」由 [MotionLatch] 决定，状态只在
 * `SessionSpeedTracker`（Application 级单例）里存一份。
 * 早期版本用「距末次 ≥1A 电流 ≤3 秒」直接当开关，解决了静止 GPS 漂移，
 * 但**行驶中松开油门滑行超过 3 秒就把 40km/h 硬压成 0** —— 用户实测反馈。
 * 现在改为：电流只在起步时上锁，锁存后由 GPS 速度维持，停稳 5 秒才解锁。
 * 常数见下方，判据实现只有 [MotionLatch] 一处。
 */
object SpeedDisplay {

    /** 低于该速度显示 0 —— 静止时 GPS 仍有 1~3km/h 漂移，不归零会锯齿 */
    const val ZERO_THRESHOLD_KMH = 5f

    /** 指数低通系数，越小越平滑 */
    const val SMOOTH_ALPHA = 0.3f

    /**
     * 「近期有电流证据」的宽限时长。
     *
     * ⚠️ 它**不再是速度硬开关**：只作为 [MotionLatch] 起步上锁的辅助证据，
     * 覆盖「刚起步但 GPS 还没解算出速度」的几秒。滑行期间锁存由 GPS 速度维持，
     * 与这个值无关，所以调大/调小都不会再造成「松油门归零」。
     */
    const val CURRENT_EVIDENCE_GRACE_MS = 3_000L

    /**
     * 无电流证据时，GPS 要**持续**达到该速度才允许独立上锁（无 BMS 也能测速）。
     * 8km/h 明显高于静止漂移量级（1~5km/h）。
     */
    const val MOTION_START_KMH = 8f

    /** 上面的起速阈值要持续多久（ms）才上锁 —— 防止单个 GPS 尖峰误触发 */
    const val MOTION_START_HOLD_MS = 1_500L

    /** 已锁存后，GPS 低于 [ZERO_THRESHOLD_KMH] 持续多久判定「真停稳了」 */
    const val MOTION_STOP_HOLD_MS = 5_000L

    /** 超过这么久没有任何 GPS 帧 → 自动解锁，防止陈旧速度证据挂死 */
    const val MOTION_GPS_STALE_MS = 15_000L

    class Filter {
        private var filtered: Float? = null

        /** 低通后的**真实浮点速度**（km/h），积分 / MoveGate 用；null = 尚无速度信号 */
        val realSpeed: Float? get() = filtered

        /**
         * 喂一帧原始速度，返回与页面水印一致的**显示值**（整数 km/h）。
         *
         * @param allowMotion 取自 `SessionSpeedTracker.speedAllowed`（[MotionLatch] 的结论）。
         *   false → **清空低通序列**并返回 0，[realSpeed] 同时变 null，
         *   这样 MoveGate 的第三路速度证据也一起失效 —— 只改显示不改证据
         *   会出现「大字 0 但里程/极速还在涨」的老问题。
         *   清空而非写 0，是为了恢复运动的那一帧直接采用真实速度，
         *   不被一段静止的 0 拖着慢慢爬升。
         */
        fun push(rawKmh: Float, allowMotion: Boolean): Int {
            if (!allowMotion) {
                filtered = null
                return 0
            }
            val f = filtered
            filtered = if (f == null) rawKmh else f + (rawKmh - f) * SMOOTH_ALPHA
            val v = filtered ?: 0f
            return if (v < ZERO_THRESHOLD_KMH) 0 else v.toInt()
        }

        /** GPS 源断开 / 无定位权限时清空，避免下次接着陈旧值平滑 */
        fun reset() { filtered = null }
    }
}

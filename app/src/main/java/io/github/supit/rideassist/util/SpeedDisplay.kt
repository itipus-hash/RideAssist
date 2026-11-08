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
 * ## ⭐⭐ 无电流证据时锁 0（2026-11-08 用户要求）
 *
 * 静止时 GPS 仍有 1~5 km/h 漂移，低通后偶尔越过 5 km/h 阈值，
 * 会被当成真实速度记进会话极速（实机出现过「速度_Max 5 km/h」）。
 * 用户判据原话：「如果电流一直是0，那么速度保持为0」。
 *
 * 所以 [Filter.push] 必须由调用方**显式**传入 allowMotion，取自
 * `SessionSpeedTracker.speedAllowedByCurrent` —— 该状态只在 Application
 * 级单例里维护一份，两条 GPS 订阅只读不写。
 */
object SpeedDisplay {

    /** 低于该速度显示 0 —— 静止时 GPS 仍有 1~3km/h 漂移，不归零会锯齿 */
    const val ZERO_THRESHOLD_KMH = 5f

    /** 指数低通系数，越小越平滑 */
    const val SMOOTH_ALPHA = 0.3f

    /**
     * 距末次「有电流证据」超过该时长即认为静止、速度锁 0。
     *
     * 留 3 秒宽限是为了等红灯 / 短滑行时的**瞬时**零电流不把速度打断；
     * 用户说的「一直是 0」指持续为零，超过宽限期就必须归零。
     */
    const val NO_CURRENT_GRACE_MS = 3_000L

    class Filter {
        private var filtered: Float? = null

        /** 低通后的**真实浮点速度**（km/h），积分 / MoveGate 用；null = 尚无速度信号 */
        val realSpeed: Float? get() = filtered

        /**
         * 喂一帧原始速度，返回与页面水印一致的**显示值**（整数 km/h）。
         *
         * @param allowMotion 是否有电流证据支持「车真的在动」。
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

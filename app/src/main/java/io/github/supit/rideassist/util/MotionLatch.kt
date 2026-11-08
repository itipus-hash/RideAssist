package io.github.supit.rideassist.util

/**
 * ⭐⭐⭐ **「车是否真的在动」的运动锁存器** —— 速度门控的唯一判据。
 *
 * ## 为什么需要锁存（两次用户反馈的正面冲突）
 * - 反馈 A：「电流一直是 0，速度保持为 0」——静止时 GPS 有 1~5km/h 漂移，
 *   不该被记进会话极速。
 * - 反馈 B：「行驶中一松开油门速度就归零」——滑行/下坡时电流本来就是 0，
 *   但车仍在以 40km/h 前进。
 *
 * 只用一个「最近 N 秒有没有电流」的开关无法满足两者：滑行超过 N 秒必误伤，
 * N 调大则静止漂移又会漏进来。正解是**区分「从没动过」和「动过之后电流停了」**：
 *
 * | 状态 | 上锁条件 | 保持条件 | 解锁条件 |
 * |---|---|---|---|
 * | 未锁存 | 有电流证据，或 GPS 持续 ≥8km/h 达 1.5s | — | — |
 * | 已锁存 | — | GPS ≥5km/h | GPS <5km/h 且持续 5s |
 *
 * 电流证据**只在起步时**起作用；一旦锁存，滑行期间完全由 GPS 速度维持，
 * 与电流无关。真正停稳 5 秒后解锁，静止漂移重新被压成 0。
 *
 * ## 单一事实来源
 * 实例只放在 [io.github.supit.rideassist.ui.realtime.SessionSpeedTracker]
 * （Application 级单例）里一份；实时页、悬浮前台服务两条 GPS 订阅都只调用
 * [update] 喂数，**不各自持有锁存状态**。低通 Filter 则仍按订阅源各持一份
 * （低通序列不能共享），两者职责不同。
 *
 * 纯 JVM 实现，无 Android 依赖 —— 便于用单测把「滑行不归零 / 静止不起锁」
 * 这两条互相制约的性质钉死（实机不接保护板时截图验证不了）。
 */
class MotionLatch {

    private var latched = false

    /** 连续达到起速阈值的起始时刻（0 = 当前没在计数） */
    private var highSinceMs = 0L

    /** 已锁存后，速度持续低于保持阈值的起始时刻（0 = 当前没在计数） */
    private var lowSinceMs = 0L

    /** 末次喂数的时刻；长时间没有 GPS 帧则自动解锁，防止陈旧速度证据挂死 */
    private var lastGpsMs = 0L

    /**
     * 喂一帧 GPS 速度，返回**本帧是否允许把 GPS 速度当真**。
     *
     * @param rawKmh 原始速度（km/h，未经低通/取整）
     * @param currentEvidence 本帧是否有「近期 ≥1A 电流」证据（只用于起步上锁）
     * @param nowMs 墙钟毫秒（可注入，便于单测）
     */
    @Synchronized
    fun update(rawKmh: Float, currentEvidence: Boolean, nowMs: Long): Boolean {
        expireIfStale(nowMs)
        lastGpsMs = nowMs

        if (!latched) {
            when {
                // 起步：电池明确在放电 → 立刻上锁，不等 GPS 爬升
                currentEvidence -> arm()
                rawKmh >= SpeedDisplay.MOTION_START_KMH -> {
                    if (highSinceMs == 0L) highSinceMs = nowMs
                    else if (nowMs - highSinceMs >= SpeedDisplay.MOTION_START_HOLD_MS) arm()
                }
                // 单次 GPS 尖峰不复位计时不算数，下一帧低于阈值就清零
                else -> highSinceMs = 0L
            }
        } else {
            // ⚠️ 保持只看 GPS 速度，**不看电流**。
            // 若把电流证据也写进保持条件，插着充电器（1A）静止时锁存永不解除，
            // 静止漂移又会漏进极速。GPS 丢失期间由推算速度继续喂数（≥5km/h）。
            if (rawKmh >= SpeedDisplay.ZERO_THRESHOLD_KMH) {
                lowSinceMs = 0L
            } else {
                if (lowSinceMs == 0L) lowSinceMs = nowMs
                else if (nowMs - lowSinceMs >= SpeedDisplay.MOTION_STOP_HOLD_MS) reset()
            }
        }
        return latched
    }

    /**
     * 不喂数、只查询当前是否允许速度。
     *
     * GPS 丢失推算的每秒 tick、以及「是否跟踪 GPS 丢失」判据走这里；
     * 会顺带执行「长时间无 GPS 帧 → 自动解锁」，避免锁存永久挂着。
     */
    @Synchronized
    fun isAllowed(nowMs: Long): Boolean {
        expireIfStale(nowMs)
        return latched
    }

    /** 当前是否锁存（不触发过期检查，诊断面板/单测用） */
    @Synchronized
    fun isLatched(): Boolean = latched

    @Synchronized
    fun reset() {
        latched = false
        highSinceMs = 0L
        lowSinceMs = 0L
        lastGpsMs = 0L
    }

    private fun arm() {
        latched = true
        highSinceMs = 0L
        lowSinceMs = 0L
    }

    private fun expireIfStale(nowMs: Long) {
        if (lastGpsMs != 0L && nowMs - lastGpsMs > SpeedDisplay.MOTION_GPS_STALE_MS) reset()
    }
}

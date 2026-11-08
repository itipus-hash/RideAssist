package io.github.supit.rideassist.data.repo

/**
 * ⭐⭐ **保护参数（0xA0）的读取时机** —— 抽成纯函数，用单测锁住。
 *
 * ## 为什么单独抽出来
 * 用户要求「不要反复读取」，第一版直接写成「连接后只在第一轮读一次，
 * 之后每 5 分钟」，结果：连上时第一条请求一旦丢包（设备刚上电、GATT 未就绪
 * 都很常见），就要**干等 5 分钟**才重试 —— 表现是阈值一直读不到、
 * 连带阈值缓存永远是空的。用户原话：「甚至不如以前可以读取到」。
 *
 * 正确的语义是**自适应**：
 * - 本次会话还没读到 → **每轮都读**（等价于旧版每秒读一次，能自愈）
 * - 已经读到 → 才降到校准周期（5 分钟），这才是"不要反复读取"的本意
 *
 * ⚠️ 「读到了」的判据必须排除缓存：
 * `hasProtectParams && !protectParamsFromCache`。
 * 只看 `hasProtectParams` 会被本地缓存污染 —— 缓存一注入就以为读到了，
 * 于是永远不再向设备请求，缓存也就永远停在旧值。
 */
object ProtectPollPolicy {

    /** 校准周期：已经读到阈值后，隔这么久再对一次表 */
    const val CALIBRATE_MS = 5 * 60_000L

    /**
     * @param readThisSession 本次会话是否**真的从设备读到过**阈值（不能是缓存）
     * @param sinceLastReadMs 距上次请求过去了多久
     */
    fun shouldRead(
        readThisSession: Boolean,
        sinceLastReadMs: Long,
        calibrateMs: Long = CALIBRATE_MS,
    ): Boolean {
        if (!readThisSession) return true        // 没读到就一直重试
        return sinceLastReadMs >= calibrateMs    // 读到了才降频
    }
}

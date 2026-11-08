package io.github.supit.rideassist.util

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * ⭐⭐ **「跨自然日」判定的唯一实现**。
 *
 * 首页胎压卡与设置页传感器信息都调它，禁止各自写 `/ 86_400_000`
 * （本项目踩过「同一判据两份实现、其中一份忘改」的坑）。
 *
 * ## 为什么按自然日，而不是「过了多少小时」
 * 用户看的是日历：昨天 23:50 的数据，今天 08:00 看只过了 8 小时，
 * 但屏幕上写「更新于 23:50:31」根本看不出**是哪一天的 23:50**。
 * 所以只要数据不在今天，一律用「昨天 / N 天前」表达 —— 跨天优先于分钟差。
 *
 * ## 为什么不是 `diffMs / 24h`
 * ① 那是滚动 24 小时，昨天 23:50 → 今天 08:00 会算成 0 天，正好漏掉要修的场景；
 * ② 夏令时切换日只有 23h/25h，整除会偏一天。
 * 这里先各自归零到当天 00:00 再相减，用 round 吸收 DST 的 ±1 小时。
 */
object RelativeTime {

    /**
     * 今天 = 0，昨天 = 1，前天 = 2 ……
     * 未来时间戳（设备时钟回拨 / NTP 校正）与非法值一律当今天 0，
     * 避免冒出「-1 天前」这种负数文案。
     */
    fun daysAgo(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): Int {
        if (timestampMs <= 0L) return 0
        val diff = dayStart(nowMs) - dayStart(timestampMs)
        if (diff <= 0L) return 0
        return (diff / 86_400_000.0).roundToInt().coerceAtLeast(1)
    }

    /** 本地时区当天 00:00:00.000 的墙钟毫秒 */
    private fun dayStart(ms: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}

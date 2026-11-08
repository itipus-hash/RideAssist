package io.github.supit.rideassist.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 纯 JVM 单测：跨自然日判定。
 *
 * ⭐ 重点锁定「昨天 23:50 → 今天 08:00」这个只过了 8 小时、
 * 但按日历已经跨天的场景 —— 用滚动 24 小时算会错成 0 天，
 * 屏幕上就会退化成「更新于 23:50:31」，正是用户要修的现象。
 */
class RelativeTimeTest {

    /** 本地时区构造一个确定时刻（month 传 1..12） */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun sameDayIsZero_evenManyHoursApart() {
        val now = at(2026, 11, 8, 23, 50)
        assertEquals(0, RelativeTime.daysAgo(at(2026, 11, 8, 0, 5), now))
        assertEquals(0, RelativeTime.daysAgo(now, now))
    }

    @Test
    fun lateYesterdayMorningToday_isYesterday_notZero() {
        // 只过了 8 小时 10 分钟，但自然日已经跨了一天
        val now = at(2026, 11, 8, 8, 0)
        assertEquals(1, RelativeTime.daysAgo(at(2026, 11, 7, 23, 50), now))
    }

    @Test
    fun twoDaysAgo() {
        val now = at(2026, 11, 8, 8, 0)
        assertEquals(2, RelativeTime.daysAgo(at(2026, 11, 6, 21, 56), now))
    }

    @Test
    fun sevenDaysAgo() {
        val now = at(2026, 11, 8, 12, 0)
        assertEquals(7, RelativeTime.daysAgo(at(2026, 11, 1, 9, 0), now))
    }

    @Test
    fun acrossMonthBoundary() {
        val now = at(2026, 11, 1, 0, 1)
        assertEquals(1, RelativeTime.daysAgo(at(2026, 10, 31, 23, 59), now))
    }

    @Test
    fun acrossYearBoundary() {
        val now = at(2026, 1, 1, 8, 0)
        assertEquals(1, RelativeTime.daysAgo(at(2025, 12, 31, 23, 0), now))
    }

    @Test
    fun leapDayIsCountedCorrectly() {
        // 2028 是闰年，2/29 → 3/1 应算 1 天而不是 0 或 2
        val now = at(2028, 3, 1, 10, 0)
        assertEquals(1, RelativeTime.daysAgo(at(2028, 2, 29, 10, 0), now))
        assertEquals(2, RelativeTime.daysAgo(at(2028, 2, 28, 10, 0), now))
    }

    @Test
    fun futureTimestampTreatedAsToday() {
        val now = at(2026, 11, 8, 8, 0)
        assertEquals(0, RelativeTime.daysAgo(at(2026, 11, 9, 8, 0), now))
        assertEquals(0, RelativeTime.daysAgo(now + 60_000L, now))
    }

    @Test
    fun invalidTimestampTreatedAsToday() {
        assertEquals(0, RelativeTime.daysAgo(0L, at(2026, 11, 8, 8, 0)))
        assertEquals(0, RelativeTime.daysAgo(-1L, at(2026, 11, 8, 8, 0)))
    }

    @Test
    fun midnightToMidnightIsOneDay() {
        val now = at(2026, 11, 8, 0, 0)
        assertEquals(1, RelativeTime.daysAgo(at(2026, 11, 7, 0, 0), now))
    }
}

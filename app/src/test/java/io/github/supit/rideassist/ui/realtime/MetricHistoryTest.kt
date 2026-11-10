package io.github.supit.rideassist.ui.realtime

import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电流 / 功率记录卡的数据口径单测（用户 2026-11-10）。
 *
 * 需求：「给电流和功率也加上相对应的最大值卡片，包括阈值的设置」，
 * 口径与极速 / 压差历史完全对称：
 * 1. **一次行程只记一条**（同行程更大是「替换」，不是追加）；
 * 2. **没超过记录阈值不记**（电流默认 50A、功率默认 1000W，都可在设置页改）；
 * 3. 最新行程在最上，最多 10 条；
 * 4. 跨 APP 重启保留，编解码往返、编号不撞车、清空会话 / 长按清除要同步落盘。
 */
class MetricHistoryTest {

    private fun frame() = BmsRealtimeData(hasDeviceData = true)

    private var clock = 1_000L

    private fun startTrip(t: SessionSpeedTracker) {
        t.updateRecordingState(true, true, clock)
        clock += 1_000L
    }

    private fun endTrip(t: SessionSpeedTracker) {
        clock += SessionSpeedTracker.STOP_AFTER_DISCONNECT_MS + 1_000L
        t.updateRecordingState(false, false, clock)
        clock += 1_000L
    }

    private fun current(t: SessionSpeedTracker, a: Float) =
        t.updatePeak(SessionSpeedTracker.CARD_CURRENT, a, frame())

    private fun power(t: SessionSpeedTracker, w: Float) =
        t.updatePeak(SessionSpeedTracker.CARD_POWER, w, frame())

    @Test
    fun `电流没超过 50A 不记录 超过才记`() {
        val t = SessionSpeedTracker()
        startTrip(t)

        current(t, 49f)
        assertTrue(t.currentHistory.isEmpty())
        current(t, 50f)                       // 恰好等于阈值也不记
        assertTrue(t.currentHistory.isEmpty())

        current(t, 51f)
        assertEquals(1, t.currentHistory.size)
        assertEquals(51, t.currentHistory[0].value)
        assertEquals(5, t.currentHistory[0].date.length)
        assertEquals(5, t.currentHistory[0].clock.length)
    }

    @Test
    fun `功率默认阈值是 1000W 不是 50W`() {
        val t = SessionSpeedTracker()
        startTrip(t)

        power(t, 999f)
        assertTrue(t.powerHistory.isEmpty())
        power(t, 1000f)
        assertTrue(t.powerHistory.isEmpty())
        power(t, 1001f)
        assertEquals(1, t.powerHistory.size)
        assertEquals(1001, t.powerHistory[0].value)
    }

    @Test
    fun `没进入行程时不记录`() {
        val t = SessionSpeedTracker()
        current(t, 120f)
        power(t, 5000f)
        assertTrue(t.currentHistory.isEmpty())
        assertTrue(t.powerHistory.isEmpty())
    }

    @Test
    fun `单次行程只记录一条 且保留最大值那帧`() {
        val t = SessionSpeedTracker()
        startTrip(t)

        current(t, 60f)
        current(t, 90f)
        current(t, 70f)                       // 低于本行程峰值：不追加也不覆盖

        assertEquals(1, t.currentHistory.size)
        assertEquals(90, t.currentHistory[0].value)
    }

    @Test
    fun `新行程追加一条 最新在最上 且没破会话峰值也要记`() {
        val t = SessionSpeedTracker()
        startTrip(t)
        current(t, 90f)
        endTrip(t)

        startTrip(t)
        current(t, 55f)                       // < 上次会话峰值 90A，仍必须记

        val rows = t.currentHistory
        assertEquals(2, rows.size)
        assertTrue(rows[0].tripId != rows[1].tripId)
        assertEquals(55, rows[0].value)
        assertEquals(90, rows[1].value)
    }

    @Test
    fun `电流和功率各自独立 互不干扰`() {
        val t = SessionSpeedTracker()
        startTrip(t)
        current(t, 60f)
        power(t, 1200f)

        assertEquals(1, t.currentHistory.size)
        assertEquals(1, t.powerHistory.size)
        assertEquals(60, t.currentHistory[0].value)
        assertEquals(1200, t.powerHistory[0].value)
    }

    @Test
    fun `最多保留 10 次行程 且丢的是最老的`() {
        val t = SessionSpeedTracker()
        repeat(12) { i ->
            startTrip(t)
            power(t, 1100f + i * 10)
            endTrip(t)
        }
        val rows = t.powerHistory
        assertEquals(SessionSpeedTracker.MAX_METRIC_HISTORY, rows.size)
        assertEquals(1210, rows[0].value)     // i = 11 最新
        assertEquals(1120, rows[9].value)     // i = 2 最老
    }

    @Test
    fun `落盘可往返 且恢复后新行程编号不撞车`() {
        val t = SessionSpeedTracker()
        startTrip(t)
        current(t, 88f)
        endTrip(t)
        startTrip(t)
        current(t, 61f)

        val raw = t.encodeCurrentHistory()
        assertEquals(2, raw.lines().size)

        val reopened = SessionSpeedTracker()
        reopened.restoreCurrentHistory(raw)
        assertEquals(t.currentHistory, reopened.currentHistory)

        startTrip(reopened)
        current(reopened, 66f)
        assertEquals(3, reopened.currentHistory.size)
        assertTrue(reopened.currentHistory[0].tripId > t.currentHistory[0].tripId)
    }

    @Test
    fun `坏行跳过 且最多恢复10条`() {
        val t = SessionSpeedTracker()
        t.restorePowerHistory("garbage\n1|2\n\n1|1200|11-10|11:34|extra")
        assertTrue(t.powerHistory.isEmpty())

        val many = (1..15).joinToString("\n") { i -> "$i|${1000 + i}|11-10|11:34" }
        t.restorePowerHistory(many)
        assertEquals(SessionSpeedTracker.MAX_METRIC_HISTORY, t.powerHistory.size)
        assertEquals(15L, t.powerHistory[0].tripId)
        assertEquals(1015, t.powerHistory[0].value)
    }

    @Test
    fun `长按清除只清这一张卡 且同步清空落盘`() {
        val t = SessionSpeedTracker()
        var savedCurrent = ""
        var savedPower = ""
        t.currentHistorySink = { savedCurrent = it }
        t.powerHistorySink = { savedPower = it }

        startTrip(t)
        current(t, 70f)
        power(t, 1500f)
        assertTrue(savedCurrent.isNotEmpty())
        assertTrue(savedPower.isNotEmpty())

        t.clearMetricHistory(SessionSpeedTracker.CARD_CURRENT)
        assertTrue(t.currentHistory.isEmpty())
        assertEquals("", savedCurrent)        // 落盘也要清，否则重开又「复活」
        assertEquals(1, t.powerHistory.size)  // 功率不受影响
        assertTrue(savedPower.isNotEmpty())
    }

    @Test
    fun `清空会话时四份历史全部清空`() {
        val t = SessionSpeedTracker()
        startTrip(t)
        current(t, 70f)
        power(t, 1500f)

        t.resetSession()
        assertTrue(t.currentHistory.isEmpty())
        assertTrue(t.powerHistory.isEmpty())
        assertTrue(t.diffHistory.isEmpty())
        assertTrue(t.topSpeedHistory.isEmpty())
    }
}

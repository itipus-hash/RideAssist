package io.github.supit.rideassist.ui.realtime

import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 极速记录卡的数据口径单测（用户 2026-11-10）。
 *
 * 需求与「最大压差单体记录」完全对称：
 * 1. **一次行程只记一条**（同行程更快是「替换」，不是追加）；
 * 2. **速度没超过记录阈值不记**（默认 50Km/h，可在连接设置页改）；
 * 3. 最新行程在最上，最多 10 条；
 * 4. **跨 APP 重启保留**，编解码可往返、恢复后行程编号不撞车、清空会话同步清空落盘；
 * 5. 显示格式 `11-10 极速 55Km/h @ 11:34`（date / speed / clock 三段）。
 *
 * 行程靠 [SessionSpeedTracker.updateRecordingState] 的 `false→true` 迁移驱动，
 * 与 [DiffHistoryTest] 用同一套注入时钟模拟「断连超过 3 分钟」。
 */
class TopSpeedHistoryTest {

    /** 速度卡不读单体电压，给一个最小合法帧即可 */
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

    private fun speed(t: SessionSpeedTracker, kmh: Float) =
        t.updatePeak(SessionSpeedTracker.CARD_SPEED, kmh, frame())

    @Test
    fun `速度没超过阈值不记录 超过才记`() {
        val t = SessionSpeedTracker()
        startTrip(t)

        speed(t, 49f)
        assertTrue(t.topSpeedHistory.isEmpty())
        // 「没超过」= 严格大于才算，恰好 50Km/h 也不记
        speed(t, 50f)
        assertTrue(t.topSpeedHistory.isEmpty())

        speed(t, 51f)
        assertEquals(1, t.topSpeedHistory.size)
        assertEquals(51, t.topSpeedHistory[0].speedKmh)
    }

    @Test
    fun `没进入行程时不记录`() {
        val t = SessionSpeedTracker()
        speed(t, 88f)
        assertTrue(t.topSpeedHistory.isEmpty())
    }

    @Test
    fun `单次行程只记录一条 且保留该行程最快那帧`() {
        val t = SessionSpeedTracker()
        startTrip(t)

        speed(t, 60f)
        speed(t, 72f)
        // 低于本行程极速：既不能追加，也不能覆盖掉最快那帧
        speed(t, 65f)

        val rows = t.topSpeedHistory
        assertEquals(1, rows.size)
        assertEquals(72, rows[0].speedKmh)
        // 日期 MM-dd（5 字符，如 11-10）
        assertEquals(5, rows[0].date.length)
        assertEquals('-', rows[0].date[2])
        // 时刻 HH:mm（5 字符，如 11:34）
        assertEquals(5, rows[0].clock.length)
        assertEquals(':', rows[0].clock[2])
    }

    @Test
    fun `新行程追加一条 最新在最上 且没破会话极速也要记`() {
        val t = SessionSpeedTracker()
        startTrip(t)
        speed(t, 80f)
        endTrip(t)

        startTrip(t)
        // ⭐ 55 < 上一次的会话极速 80：挂在「会话峰值」门控之后就会漏掉整条行程
        speed(t, 55f)

        val rows = t.topSpeedHistory
        assertEquals(2, rows.size)
        assertTrue(rows[0].tripId != rows[1].tripId)
        assertEquals(55, rows[0].speedKmh)
        assertEquals(80, rows[1].speedKmh)
    }

    @Test
    fun `低于阈值的行程不占位 也不会挤掉已有记录`() {
        val t = SessionSpeedTracker()
        startTrip(t)
        speed(t, 70f)
        endTrip(t)

        startTrip(t)
        speed(t, 30f)
        assertEquals(1, t.topSpeedHistory.size)
        assertEquals(70, t.topSpeedHistory[0].speedKmh)
    }

    @Test
    fun `最多保留 10 次行程 且丢的是最老的`() {
        val t = SessionSpeedTracker()
        repeat(12) { i ->
            startTrip(t)
            speed(t, 60f + i)
            endTrip(t)
        }
        val rows = t.topSpeedHistory
        assertEquals(SessionSpeedTracker.MAX_TOP_SPEED_HISTORY, rows.size)
        assertEquals(71, rows[0].speedKmh)      // i = 11 最新
        assertEquals(62, rows[9].speedKmh)      // i = 2 最老（前两次已被挤出）
        for (i in 1 until rows.size) {
            assertTrue(rows[i - 1].speedKmh > rows[i].speedKmh)
        }
    }

    @Test
    fun `阈值改成 30 后 40Kmh 也能记录`() {
        val t = SessionSpeedTracker()
        t.thresholdsProvider = {
            SessionSpeedTracker.RecordThresholds(diffMv = 50f, topSpeedKmh = 30f)
        }
        startTrip(t)
        speed(t, 30f)                            // 恰好等于阈值 → 仍不记
        assertTrue(t.topSpeedHistory.isEmpty())
        speed(t, 40f)
        assertEquals(1, t.topSpeedHistory.size)
        assertEquals(40, t.topSpeedHistory[0].speedKmh)
    }

    @Test
    fun `历史可编码落盘 重开APP恢复后字段一致且新行程编号不撞车`() {
        val t = SessionSpeedTracker()
        startTrip(t)
        speed(t, 88f)
        endTrip(t)
        startTrip(t)
        speed(t, 61f)

        val raw = t.encodeTopSpeedHistory()
        assertEquals(2, raw.lines().size)

        val reopened = SessionSpeedTracker()
        reopened.restoreTopSpeedHistory(raw)
        assertEquals(t.topSpeedHistory, reopened.topSpeedHistory)
        assertEquals(88, reopened.topSpeedHistory[1].speedKmh)

        // ⭐ 新行程必须用全新编号追加，否则会把上次运行的记录当成同一行程替换掉
        startTrip(reopened)
        speed(reopened, 66f)
        assertEquals(3, reopened.topSpeedHistory.size)
        assertTrue(reopened.topSpeedHistory[0].tripId > t.topSpeedHistory[0].tripId)
        assertEquals(66, reopened.topSpeedHistory[0].speedKmh)
    }

    @Test
    fun `落盘串损坏时跳过坏行 且恢复最多10条`() {
        val t = SessionSpeedTracker()
        t.restoreTopSpeedHistory("garbage\n1|2\n\n1|55|11-10|11:34|extra")
        assertTrue(t.topSpeedHistory.isEmpty())

        val many = (1..15).joinToString("\n") { i -> "$i|${50 + i}|11-10|11:34" }
        t.restoreTopSpeedHistory(many)
        assertEquals(SessionSpeedTracker.MAX_TOP_SPEED_HISTORY, t.topSpeedHistory.size)
        assertEquals(15L, t.topSpeedHistory[0].tripId)
        assertEquals(65, t.topSpeedHistory[0].speedKmh)
    }

    @Test
    fun `只在历史变化时才触发落盘 清空会话同步清空落盘`() {
        val t = SessionSpeedTracker()
        var saved = ""
        t.topSpeedHistorySink = { saved = it }

        startTrip(t)
        assertEquals("", saved)
        speed(t, 60f)
        assertTrue(saved.isNotEmpty())
        assertEquals(1, saved.lines().size)

        // 同行程内没更快 → 不写盘（不是每帧写 SP）
        val snapshot = saved
        speed(t, 58f)
        assertEquals(snapshot, saved)

        t.resetSession()
        // ⭐ 清空会话不再清历史（用户 2026-11-10），落盘也保持原样
        assertEquals(snapshot, saved)
    }
}

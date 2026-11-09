package io.github.supit.rideassist.ui.realtime

import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压差历史卡的数据口径单测（2026-11-09 用户修正后的口径）。
 *
 * 新的三条规则：
 * 1. **单次行程只记录一次**（同行程压差创新高是「替换」，不是追加）；
 * 2. **压差没超过 50mV 不记录**；
 * 3. 最新行程在最上，最多 10 条。
 *
 * 实机上「压差创新高」是可遇不可求的偶发事件，靠截图等不到，
 * 所以把行程边界、阈值与字段口径钉死在纯 JVM 测试里。
 *
 * 行程靠 [SessionSpeedTracker.updateRecordingState] 的 `false→true` 迁移驱动，
 * 测试注入 `nowMs` 来模拟「断连超过 3 分钟」以结束一次行程。
 *
 * 三颗电芯里让第 3 颗明显偏低，`findOutlierCell` 必然指向它：
 *   dev_3 = v3 − (v1+v2)/2 → 负值最大，电压/偏离量都便于断言。
 */
class DiffHistoryTest {

    private fun frame(lowest: Float) = BmsRealtimeData(
        hasDeviceData = true,
        cellCount = 3,
        cellVoltages = listOf(4.000f, 4.001f, lowest),
    )

    /** 可注入的墙钟，便于模拟跨行程 */
    private var clock = 1_000L

    /** 开一次新行程：放电帧让 isRecording 从 false 锁存为 true */
    private fun startTrip(t: SessionSpeedTracker) {
        t.updateRecordingState(true, true, clock)
        clock += 1_000L
    }

    /** 结束一次行程：让「距末次有效数据」超过 3 分钟，锁存才会解除 */
    private fun endTrip(t: SessionSpeedTracker) {
        clock += SessionSpeedTracker.STOP_AFTER_DISCONNECT_MS + 1_000L
        t.updateRecordingState(false, false, clock)
        clock += 1_000L
    }

    @Test
    fun `压差没超过 50mV 不记录 超过才记`() {
        val t = SessionSpeedTracker()
        startTrip(t)

        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 49f, frame(3.900f))
        assertTrue(t.diffHistory.isEmpty())
        // 「没超过」= 严格大于才算，恰好 50mV 也不记
        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 50f, frame(3.900f))
        assertTrue(t.diffHistory.isEmpty())

        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 50.5f, frame(3.850f))
        assertEquals(1, t.diffHistory.size)
        assertEquals(50.5f, t.diffHistory[0].diffMv, 0.0001f)
        assertEquals(3.850f, t.diffHistory[0].cellVolt, 0.0001f)
    }

    @Test
    fun `没进入行程时不记录`() {
        val t = SessionSpeedTracker()
        // 静止待机时即使压差很大，也不属于任何一次行程
        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 120f, frame(3.900f))
        assertTrue(t.diffHistory.isEmpty())
    }

    @Test
    fun `单次行程只记录一条 且保留该行程压差最大那帧的快照`() {
        val t = SessionSpeedTracker()
        startTrip(t)

        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 60f, frame(3.900f))
        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 90f, frame(3.850f))
        // 低于本行程峰值：既不能追加，也不能把峰值那帧覆盖掉
        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 70f, frame(3.800f))

        val rows = t.diffHistory
        assertEquals(1, rows.size)
        assertEquals(90f, rows[0].diffMv, 0.0001f)
        assertEquals(3.850f, rows[0].cellVolt, 0.0001f)
        assertEquals(3, rows[0].cellIndex)
        // 日期 MM-dd（5 字符，如 11-09）
        assertEquals(5, rows[0].date.length)
        assertTrue(rows[0].date[2] == '-')
        // 3.850 − (4.000+4.001)/2 = −0.1505V → 150.5mV（其余电芯均值的偏离量）
        assertEquals(150.5f, rows[0].deviationMv, 0.05f)
        assertTrue(rows.all { it.deviationMv >= 0f })
    }

    @Test
    fun `新行程追加一条 最新在最上 且没破会话峰值也要记`() {
        val t = SessionSpeedTracker()
        startTrip(t)
        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 90f, frame(3.900f))
        endTrip(t)

        startTrip(t)
        // ⭐ 55mV < 上一次的会话峰值 90mV：旧实现挂在「会话峰值」分支里，
        //    这一整条行程会被漏掉。现在按行程独立统计，必须有记录。
        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 55f, frame(3.850f))

        val rows = t.diffHistory
        assertEquals(2, rows.size)
        assertTrue(rows[0].tripId != rows[1].tripId)
        assertEquals(3.850f, rows[0].cellVolt, 0.0001f)
        assertEquals(55f, rows[0].diffMv, 0.0001f)
        assertEquals(3.900f, rows[1].cellVolt, 0.0001f)
    }

    @Test
    fun `低于阈值的行程不占位 也不会挤掉已有记录`() {
        val t = SessionSpeedTracker()
        startTrip(t)
        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 80f, frame(3.900f))
        endTrip(t)

        startTrip(t)
        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 30f, frame(3.850f))
        assertEquals(1, t.diffHistory.size)
        assertEquals(3.900f, t.diffHistory[0].cellVolt, 0.0001f)
    }

    @Test
    fun `最多保留 10 次行程 且丢的是最老的`() {
        val t = SessionSpeedTracker()
        repeat(12) { i ->
            startTrip(t)
            t.updatePeak(
                SessionSpeedTracker.CARD_DIFF,
                60f + i,
                frame(3.900f - i * 0.001f),
            )
            endTrip(t)
        }
        val rows = t.diffHistory
        assertEquals(SessionSpeedTracker.MAX_DIFF_HISTORY, rows.size)
        // 第 12 次行程（i=11）最新，电压最小
        assertEquals(3.889f, rows[0].cellVolt, 0.0001f)
        // 最老的一条是 i=2（前两次行程已被挤出），仍在列表末尾
        assertEquals(3.898f, rows[9].cellVolt, 0.0001f)
        for (i in 1 until rows.size) {
            assertTrue(rows[i - 1].cellVolt < rows[i].cellVolt)
        }
    }

    @Test
    fun `清空会话时历史一并清空 且行程编号重新从 1 开始`() {
        val t = SessionSpeedTracker()
        startTrip(t)
        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 80f, frame(3.900f))
        assertEquals(1, t.diffHistory.size)

        t.resetSession()
        assertTrue(t.diffHistory.isEmpty())

        startTrip(t)
        t.updatePeak(SessionSpeedTracker.CARD_DIFF, 80f, frame(3.850f))
        assertEquals(1, t.diffHistory.size)
        assertEquals(1L, t.diffHistory[0].tripId)
    }
}

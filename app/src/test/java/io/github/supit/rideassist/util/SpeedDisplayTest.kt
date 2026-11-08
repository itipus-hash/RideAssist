package io.github.supit.rideassist.util

import io.github.supit.rideassist.ui.realtime.SessionSpeedTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐ 两条 GPS 订阅（实时页 / 悬浮前台服务）共用 [SpeedDisplay] 的口径。
 *
 * 悬浮服务是这次为修「极速退后台冻结」新加的第二条订阅。两条订阅各持一个
 * Filter 实例，但只要有一路偷偷改了低通系数 / 归零阈值 / 取整方式，
 * 同一时刻的 Max 与页面大字就会对不上 —— 这类「判据分裂」是本项目老坑。
 * 这里锁住口径，以及会话极速的单调性与「只在变更时通知」语义。
 */
class SpeedDisplayTest {

    @Test fun `首帧直接采用原始速度不做平滑`() {
        val f = SpeedDisplay.Filter()
        assertNull(f.realSpeed)
        assertEquals(30, f.push(30f))
        assertEquals(30f, f.realSpeed!!, 1e-4f)
    }

    @Test fun `第二帧起按 SMOOTH_ALPHA 低通`() {
        val f = SpeedDisplay.Filter()
        f.push(0f)
        assertEquals(30, f.push(100f))              // 0 + (100 - 0) * 0.3 = 30
        assertEquals(30f, f.realSpeed!!, 1e-4f)
    }

    @Test fun `低于阈值显示归零但真实速度仍保留给移动判定`() {
        val f = SpeedDisplay.Filter()
        assertEquals(0, f.push(4.9f))
        assertEquals(4.9f, f.realSpeed!!, 1e-4f)
    }

    @Test fun `恰好到阈值开始显示整数速度`() {
        val f = SpeedDisplay.Filter()
        assertEquals(5, f.push(5f))
        assertEquals(7, f.push(12f))                // 5 + (12 - 5) * 0.3 = 7.1 → 7
    }

    @Test fun `reset 后不接着旧值平滑`() {
        val f = SpeedDisplay.Filter()
        f.push(80f)
        f.reset()
        assertNull(f.realSpeed)
        assertEquals(10, f.push(10f))
    }

    @Test fun `两个订阅源的 Filter 状态互不干扰`() {
        val page = SpeedDisplay.Filter()
        val overlay = SpeedDisplay.Filter()
        page.push(60f)
        assertEquals(20, overlay.push(20f))         // 另一路仍是首帧
        assertEquals(60f, page.realSpeed!!, 1e-4f)
    }

    @Test fun `noteSpeed 只在破纪录时写入并通知重渲`() {
        val t = SessionSpeedTracker()
        val r0 = t.speedRevisionFlow.value
        assertTrue(t.noteSpeed(30))
        assertEquals(30, t.maxSpeed)
        assertEquals(r0 + 1, t.speedRevisionFlow.value)

        assertFalse(t.noteSpeed(30))
        assertFalse(t.noteSpeed(12))
        assertEquals(30, t.maxSpeed)
        assertEquals(r0 + 1, t.speedRevisionFlow.value)   // 没破纪录就不打扰订阅方

        assertTrue(t.noteSpeed(31))
        assertEquals(31, t.maxSpeed)
        assertEquals(r0 + 2, t.speedRevisionFlow.value)
    }

    @Test fun `resetSession 清零极速并通知订阅方`() {
        val t = SessionSpeedTracker()
        t.noteSpeed(88)
        val before = t.speedRevisionFlow.value
        t.resetSession()
        assertEquals(0, t.maxSpeed)
        assertTrue(t.speedRevisionFlow.value > before)
    }
}

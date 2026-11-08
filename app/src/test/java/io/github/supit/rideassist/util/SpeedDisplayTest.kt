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
 * 悬浮服务是修「极速退后台冻结」时加的第二条订阅。两条订阅各持一个
 * Filter 实例，但只要有一路偷偷改了低通系数 / 归零阈值 / 取整方式，
 * 同一时刻的 Max 与页面大字就会对不上 —— 这类「判据分裂」是本项目老坑。
 *
 * 2026-11-08 追加：**电流持续为 0 时速度必须锁 0**，用户原话
 * 「速度怎么会有 5km/h，这个大概率偏移造成啊，如果电流一直是0，
 * 那么速度保持为0」。下面同时锁住显示口径与电流证据状态。
 *
 * 2026-11-08 修订：用户反馈「行驶中一松开油门速度就归零」——
 * 电流证据退居为**起步上锁**的辅助条件，锁存后的滑行由 GPS 速度维持，
 * 详细状态机时序见 [MotionLatchTest]。
 */
class SpeedDisplayTest {

    // ==================== 正常路径（有电流证据） ====================

    @Test fun `首帧直接采用原始速度不做平滑`() {
        val f = SpeedDisplay.Filter()
        assertNull(f.realSpeed)
        assertEquals(30, f.push(30f, true))
        assertEquals(30f, f.realSpeed!!, 1e-4f)
    }

    @Test fun `第二帧起按 SMOOTH_ALPHA 低通`() {
        val f = SpeedDisplay.Filter()
        f.push(0f, true)
        assertEquals(30, f.push(100f, true))              // 0 + (100 - 0) * 0.3 = 30
        assertEquals(30f, f.realSpeed!!, 1e-4f)
    }

    @Test fun `低于阈值显示归零但真实速度仍保留给移动判定`() {
        val f = SpeedDisplay.Filter()
        assertEquals(0, f.push(4.9f, true))
        assertEquals(4.9f, f.realSpeed!!, 1e-4f)
    }

    @Test fun `恰好到阈值开始显示整数速度`() {
        val f = SpeedDisplay.Filter()
        assertEquals(5, f.push(5f, true))
        assertEquals(7, f.push(12f, true))                // 5 + (12 - 5) * 0.3 = 7.1 → 7
    }

    @Test fun `reset 后不接着旧值平滑`() {
        val f = SpeedDisplay.Filter()
        f.push(80f, true)
        f.reset()
        assertNull(f.realSpeed)
        assertEquals(10, f.push(10f, true))
    }

    @Test fun `两个订阅源的 Filter 状态互不干扰`() {
        val page = SpeedDisplay.Filter()
        val overlay = SpeedDisplay.Filter()
        page.push(60f, true)
        assertEquals(20, overlay.push(20f, true))         // 另一路仍是首帧
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

    // ==================== ⭐ 电流门控：静止漂移不得变成速度 ====================

    @Test fun `电流持续为 0 时速度锁 0 且清空低通序列`() {
        val f = SpeedDisplay.Filter()
        f.push(60f, true)
        assertEquals(0, f.push(7f, false))
        // realSpeed 必须为 null：MoveGate 第三路速度证据要一起失效，
        // 否则会出现「大字 0 但里程 / 极速还在涨」的老问题
        assertNull(f.realSpeed)
    }

    @Test fun `恢复电流证据后首帧直接采用真实速度不被旧 0 拖累`() {
        val f = SpeedDisplay.Filter()
        f.push(60f, true)
        f.push(3f, false)
        assertEquals(42, f.push(42f, true))
    }

    @Test fun `静止漂移连续喂入一百帧也累积不出极速`() {
        val f = SpeedDisplay.Filter()
        repeat(100) { assertEquals(0, f.push(4.9f, false)) }
        assertNull(f.realSpeed)
    }

    @Test fun `电流证据只在起步上锁 断连帧不刷新`() {
        val t = SessionSpeedTracker()
        val now = System.currentTimeMillis()
        assertFalse(t.noteGpsSpeed(2f, now))               // 静止漂移、无证据 → 不上锁
        t.noteCurrentEvidence(0f, true)                   // 电流 0 → 不算证据
        assertFalse(t.noteGpsSpeed(2f, now))
        t.noteCurrentEvidence(2.5f, true)                   // ≥ MoveGate.TH_CURRENT_A → 上锁
        assertTrue(t.noteGpsSpeed(40f, now))
        t.noteCurrentEvidence(9f, false)                    // 断连帧不刷新证据
        assertTrue(t.noteGpsSpeed(40f, now))                // 滑行由 GPS 速度维持
    }

    @Test fun `resetSession 连运动锁存一起清掉`() {
        val t = SessionSpeedTracker()
        val now = System.currentTimeMillis()
        t.noteCurrentEvidence(3f, true)
        assertTrue(t.noteGpsSpeed(40f, now))
        assertTrue(t.motionLatched)
        assertTrue(t.speedAllowed)
        t.resetSession()
        assertFalse(t.motionLatched)
        assertFalse(t.speedAllowed)
    }

    /**
     * ⭐⭐ 电流曲线空白那个 bug 的根因锁死：
     * [SessionSpeedTracker.updateRecordingState] 返回的是「状态是否发生迁移」，
     * 不是「当前是否在记录」。四张曲线一律读 isRecording 属性。
     */
    @Test fun `updateRecordingState 返回状态迁移 不能当作是否在记录`() {
        val t = SessionSpeedTracker()
        assertFalse(t.isRecording)
        assertTrue("首次启动应报告一次迁移", t.updateRecordingState(true, true))
        assertTrue(t.isRecording)
        assertFalse("状态未变化时返回 false", t.updateRecordingState(true, true))
        assertTrue("但记录仍在进行 —— 曲线绝不能因此清空", t.isRecording)
        assertFalse(t.updateRecordingState(false, true))   // 滑行电流回落不停记录
        assertTrue(t.isRecording)
    }

    @Test fun `静止门控下 noteSpeed 收到 0 不会刷新极速`() {
        val t = SessionSpeedTracker()
        val before = t.speedRevisionFlow.value
        val f = SpeedDisplay.Filter()
        repeat(60) { t.noteSpeed(f.push(4.9f, false)) }
        assertEquals(0, t.maxSpeed)
        assertEquals(before, t.speedRevisionFlow.value)
    }

    // ============ ⭐ 速度曲线不能画出贴底 0 线（用户二次反馈） ============
    //
    // 上一次只修了「打开 APP 就一直静止」（队列全 0 不画）；
    // 「跑过之后停车」时队列是 [30,20,10]，allZero 判不出来，
    // 于是 0 被无限追加 → 一条越来越长的贴底横线。

    @Test fun `停车首次落地允许追加一个 0`() {
        assertTrue(SpeedDisplay.shouldAppendSample(30, null))
        assertTrue(SpeedDisplay.shouldAppendSample(0, 30f))
    }

    @Test fun `已经落到底后不再追加 0 避免贴底横线`() {
        assertFalse(SpeedDisplay.shouldAppendSample(0, 0f))
        assertFalse(SpeedDisplay.shouldAppendSample(0, 0f))
    }

    @Test fun `静止一百帧最多只落地一次`() {
        var last: Float? = 30f
        var appended = 0
        repeat(100) {
            if (SpeedDisplay.shouldAppendSample(0, last)) {
                appended++
                last = 0f
            }
        }
        assertEquals(1, appended)
    }

    @Test fun `重新起步后继续正常入队`() {
        assertTrue(SpeedDisplay.shouldAppendSample(12, 0f))
        assertTrue(SpeedDisplay.shouldAppendSample(1, 0f))
    }
}

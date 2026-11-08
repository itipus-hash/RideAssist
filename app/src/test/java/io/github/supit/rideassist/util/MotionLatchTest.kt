package io.github.supit.rideassist.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐⭐ **「松油门不归零」与「静止不起速」两条互相制约的性质**。
 *
 * 实机不接保护板 / 不骑车时截图根本验证不了门控，所以判据抽成纯 JVM
 * [MotionLatch]，用注入的墙钟时刻把整段骑行时序跑一遍。
 *
 * 两次用户反馈（都要同时满足，缺一不可）：
 * - 「如果电流一直是 0，那么速度保持为 0」→ 静止漂移不许记进极速
 * - 「行驶过程中，一松开油门他就把速度归零了」→ 滑行期间电流为 0 也要保速
 */
class MotionLatchTest {

    private var clock = 1_000_000L

    /** 推进一秒并喂一帧，返回本帧是否允许速度 */
    private fun tick(latch: MotionLatch, kmh: Float, currentEvidence: Boolean = false): Boolean {
        clock += 1_000L
        return latch.update(kmh, currentEvidence, clock)
    }

    // ==================== 静止：绝不能起锁 ====================

    @Test fun `静止 GPS 漂移喂一百帧也不上锁`() {
        val l = MotionLatch()
        repeat(100) {
            assertFalse(tick(l, if (it % 2 == 0) 1.5f else 4.9f))
        }
        assertFalse(l.isLatched())
        assertFalse(l.isAllowed(clock))
    }

    @Test fun `单个 GPS 尖峰不足以误上锁`() {
        val l = MotionLatch()
        assertFalse(tick(l, 12f))                      // 一帧尖峰
        assertFalse(tick(l, 2f))                      // 立刻回落 → 计时清零
        assertFalse(tick(l, 12f))
        assertFalse(l.isLatched())
    }

    /**
     * ⭐ 2026-11-08 用户第二次反馈漂移（「我甚至保护板都没连接」）的回归锁。
     * 静止时 GPS 速度噪声能长期停在 5~9km/h 区间，起速阈值必须是 10 才不会误锁。
     */
    @Test fun `低于起速阈值的持续噪声永远不上锁`() {
        val l = MotionLatch()
        repeat(60) { assertFalse(tick(l, 9f)) }
        assertFalse(l.isLatched())
    }

    @Test fun `无 BMS 数据时持续高速 GPS 也能独立上锁`() {
        val l = MotionLatch()
        // 起速阈值 10km/h 要持续 3 秒，挡掉静止时连续两三帧的 GPS 噪声
        assertFalse(tick(l, 12f))                      // 计时起点
        assertFalse(tick(l, 12f))                      // 1s
        assertFalse(tick(l, 12f))                      // 2s
        assertTrue(tick(l, 12f))                       // 3s ≥ 3s → 上锁
        assertTrue(tick(l, 30f))
        assertTrue(tick(l, 42f))
    }

    // ==================== 起步：电流是可靠证据 ====================

    @Test fun `起步电流证据立刻上锁 不等 GPS 爬升`() {
        val l = MotionLatch()
        assertTrue(tick(l, 1.5f, currentEvidence = true))
        assertTrue(l.isLatched())
    }

    @Test fun `电流为 0 且无设备数据不算起步证据`() {
        val l = MotionLatch()
        assertFalse(tick(l, 3f, currentEvidence = false))
        assertFalse(tick(l, 3f, currentEvidence = false))
    }

    // ==================== ⭐ 核心回归：滑行不归零 ====================

    @Test fun `松油门滑行两分钟电流为 0 速度仍保持`() {
        val l = MotionLatch()
        assertTrue(tick(l, 30f, currentEvidence = true))   // 起步：有电流
        // 电流证据撤回（松开油门），但车仍在跑 40km/h —— 每帧都必须允许
        repeat(120) {
            assertTrue("滑行第 ${it + 1} 秒被误判静止", tick(l, 40f, currentEvidence = false))
        }
        assertTrue(l.isLatched())
    }

    @Test fun `长下坡低速滑行只要不低于阈值就不断速`() {
        val l = MotionLatch()
        assertTrue(tick(l, 20f, currentEvidence = true))
        repeat(60) { assertTrue(tick(l, 6f)) }             // 6km/h 仍 ≥ 5
        assertTrue(l.isLatched())
    }

    @Test fun `滑行中短暂低速不立刻解锁`() {
        val l = MotionLatch()
        assertTrue(tick(l, 35f, currentEvidence = true))
        assertTrue(tick(l, 2f))                            // 低速第 1 秒
        assertTrue(tick(l, 2f))
        assertTrue(tick(l, 2f))
        assertTrue(tick(l, 30f))                          // 又动起来 → 锁存继续
        assertTrue(tick(l, 30f))
        assertTrue(l.isLatched())
    }

    // ==================== 停稳：真的停下才解锁 ====================

    @Test fun `停稳持续五秒后解锁 静止漂移重新被压成 0`() {
        val l = MotionLatch()
        assertTrue(tick(l, 40f, currentEvidence = true))
        // 停稳后 5 秒内仍保持（等红灯不该反复上锁/解锁）
        repeat(5) { assertTrue(tick(l, 0f)) }
        assertFalse(tick(l, 0f))                           // 低速持续满 5 秒 → 解锁
        assertFalse(l.isLatched())
        // 解锁后 GPS 漂移不能再把它带起来
        assertFalse(tick(l, 4.9f))
        assertFalse(tick(l, 4.9f))
    }

    @Test fun `插着充电器静止时电流证据不会把锁存永久挂住`() {
        val l = MotionLatch()
        assertTrue(tick(l, 30f, currentEvidence = true))
        // 停车充电：电流证据一直为 true，但速度为 0 —— 保持只看 GPS 速度
        repeat(6) { tick(l, 0f, currentEvidence = true) }
        assertFalse(l.isLatched())
    }

    // ==================== 无 GPS 帧：陈旧证据不许挂死 ====================

    @Test fun `超过 GPS 陈旧上限自动解锁`() {
        val l = MotionLatch()
        assertTrue(tick(l, 40f, currentEvidence = true))
        assertTrue(l.isAllowed(clock + SpeedDisplay.MOTION_GPS_STALE_MS - 1))
        assertFalse(l.isAllowed(clock + SpeedDisplay.MOTION_GPS_STALE_MS + 1))
        assertFalse(l.isLatched())
    }

    @Test fun `reset 后重新回到静止判定`() {
        val l = MotionLatch()
        assertTrue(tick(l, 40f, currentEvidence = true))
        l.reset()
        assertFalse(l.isLatched())
        assertFalse(tick(l, 3f))
    }
}

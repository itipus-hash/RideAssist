package io.github.supit.rideassist.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ 「位置到底动没动」的粗判据。
 *
 * 用户第二次反馈漂移（「我甚至保护板都没连接」）后加的第三道闸：
 * 静止时 `Location.getSpeed()` 能持续报 5~9km/h，光靠速度阈值挡不干净；
 * 但同一段时间里**位置几乎没位移**，这条判据不受速度噪声影响。
 */
class GpsMotionProbeTest {

    @Test fun `完全静止时平均位移速度为 0`() {
        val p = GpsMotionProbe()
        p.accept(24.0, 118.0, 1_000)
        val v = p.accept(24.0, 118.0, 10_000)
        assertEquals(0f, v, 0.01f)
    }

    @Test fun `跨度不足时不给结论 返回 -1`() {
        val p = GpsMotionProbe()
        p.accept(24.0, 118.0, 1_000)
        val v = p.accept(24.0005, 118.0, 5_000)          // 跨度 4s < 8s
        assertEquals(-1f, v, 0f)
    }

    @Test fun `匀速移动算出正确速度`() {
        val p = GpsMotionProbe()
        p.accept(24.0, 118.0, 1_000)
        // 10 秒移动 0.0005 度纬度 ≈ 55.6m → 20.0km/h
        val v = p.accept(24.0005, 118.0, 11_000)
        assertEquals(20f, v, 0.6f)
    }

    @Test fun `窗口会滚动 不会一直拿最早的点当起点`() {
        val p = GpsMotionProbe()
        for (i in 0..20) p.accept(24.0, 118.0, i * 1_000L)   // 20 秒原地
        // 若还以 0 秒为起点：167m / 21s ≈ 28.6km/h
        // 窗口滚到 11 秒为起点：167m / 10s ≈ 60km/h
        val v = p.accept(24.0015, 118.0, 21_000)
        assertTrue("窗口没滚动，起点仍是 0 秒（v=$v）", v > 45f)
    }

    @Test fun `reset 后重新从空窗口开始`() {
        val p = GpsMotionProbe()
        p.accept(24.0, 118.0, 1_000)
        p.accept(24.001, 118.0, 10_000)
        p.reset()
        assertEquals(-1f, p.accept(24.01, 118.0, 11_000), 0f)
    }

    @Test fun `Haversine 距离量级正确`() {
        // 1 毫度纬度 ≈ 111.2m
        val d = GpsMotionProbe.distanceMeters(24.0, 118.0, 24.001, 118.0)
        assertEquals(111.2, d, 1.0)
        assertEquals(0.0, GpsMotionProbe.distanceMeters(24.0, 118.0, 24.0, 118.0), 0.001)
    }
}

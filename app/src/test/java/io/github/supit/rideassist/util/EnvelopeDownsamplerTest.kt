package io.github.supit.rideassist.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐ 历史曲线压缩的「不丢极值」保证。
 *
 * 用户明确要求：压缩不能抹掉峰值（速度最大值、突变急刹点必须保留），
 * 也不能出现断层。min/max 包络靠的是数学性质而不是调参，
 * 所以这里逐条把它钉死。
 */
class EnvelopeDownsamplerTest {

    @Test fun `点数不多时原样返回同一数组`() {
        val v = FloatArray(100) { it.toFloat() }
        assertSame(v, EnvelopeDownsampler.downsample(v, 100))
    }

    @Test fun `空数组与单点不崩`() {
        val empty = FloatArray(0)
        assertSame(empty, EnvelopeDownsampler.downsample(empty, 50))
        val one = floatArrayOf(7f)
        assertSame(one, EnvelopeDownsampler.downsample(one, 50))
    }

    @Test fun `全局最大值一定被保留`() {
        val v = FloatArray(5000) { 20f + (it % 7) }
        v[3210] = 67f                       // 会话极速，只能出现一次
        val out = EnvelopeDownsampler.downsample(v, 200)
        assertTrue("压缩后丢了峰值 67", out.any { it == 67f })
        assertEquals(67f, out.max(), 0f)
    }

    @Test fun `全局最小值一定被保留`() {
        val v = FloatArray(5000) { 40f + (it % 5) }
        v[1234] = -17f                      // 电流最小值（充电）
        val out = EnvelopeDownsampler.downsample(v, 200)
        assertTrue("压缩后丢了谷值 -17", out.any { it == -17f })
        assertEquals(-17f, out.min(), 0f)
    }

    @Test fun `孤立单点尖峰不会被抽稀掉`() {
        val v = FloatArray(2000) { 10f }
        v[1000] = 99f
        val out = EnvelopeDownsampler.downsample(v, 100)
        assertTrue(out.any { it == 99f })
    }

    @Test fun `急刹突变保留 高值与低值都在`() {
        val v = FloatArray(3000) { if (it < 1500) 100f else 0f }
        val out = EnvelopeDownsampler.downsample(v, 120)
        assertTrue(out.any { it == 100f })
        assertTrue(out.any { it == 0f })
    }

    @Test fun `首点与末点强制保留 曲线两端不缺一截`() {
        val v = FloatArray(4000) { (it % 13).toFloat() }
        val out = EnvelopeDownsampler.downsample(v, 80)
        assertEquals(v.first(), out.first(), 0f)
        assertEquals(v.last(), out.last(), 0f)
    }

    @Test fun `输出点数不超过两倍列数加二`() {
        val v = FloatArray(10000) { (it % 91).toFloat() }
        val cols = 150
        val out = EnvelopeDownsampler.downsample(v, cols)
        assertTrue("点数 ${out.size} 超出上限", out.size <= cols * 2 + 2)
    }

    @Test fun `单调递增序列压缩后仍单调不减 不断层不回头`() {
        val v = FloatArray(6000) { it.toFloat() }
        val out = EnvelopeDownsampler.downsample(v, 100)
        for (i in 1 until out.size) {
            assertTrue("第 $i 个点比前一个小，说明顺序错了", out[i] >= out[i - 1])
        }
    }

    @Test fun `目标列数非法时按两列兜底`() {
        val v = FloatArray(1000) { it.toFloat() }
        val out = EnvelopeDownsampler.downsample(v, 0)
        assertTrue(out.size <= 2 * 2 + 2)
        assertEquals(v.first(), out.first(), 0f)
        assertEquals(v.last(), out.last(), 0f)
    }
}

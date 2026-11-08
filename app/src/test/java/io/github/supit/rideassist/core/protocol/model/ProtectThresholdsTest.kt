package io.github.supit.rideassist.core.protocol.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ 保护板阈值的本地固化。
 *
 * 用户要求：诊断面板的阈值不能等读到保护板才显示，要用本机保存过的；
 * 后续读到变化再覆盖，但不要反复读取。
 *
 * 编码是**按位置**的，字段顺序错一位就会拿错阈值去报警 —— 所以往返
 * 一致性和"数量不符就丢掉"这两点必须钉死。
 */
class ProtectThresholdsTest {

    private fun sample() = ProtectThresholds(
        overVoltSet = 4.2f,
        underVoltSet = 3.0f,
        overVoltRecover = 4.1f,
        underVoltRecover = 3.1f,
        overCurrentCharge = 75f,
        overCurrentDischarge = 157.5f,
        overCurrentDischarge2 = 200f,
        shortCircuitCurrent = 888f,
        packOverVoltSet = 71.4f,
        packOverVoltRecover = 69.7f,
        packUnderVoltSet = 51f,
        packUnderVoltRecover = 53.55f,
        overTempCharge = 60f,
        overTempChargeRecover = 55f,
        underTempCharge = -20f,
        underTempChargeRecover = -15f,
        overTempDischarge = 60f,
        overTempDischargeRecover = 55f,
        underTempDischarge = -20f,
        underTempDischargeRecover = -15f,
        mosOverTemp = 95f,
        savedAtMs = 1_700_000_000_000L,
    )

    @Test fun `编码解码往返一致 负温度也不丢`() {
        val back = ProtectThresholds.decode(sample().encode())!!
        assertFalse(sample().differsFrom(back))
        assertEquals(-20f, back.underTempCharge, 0f)
        assertEquals(1_700_000_000_000L, back.savedAtMs)
    }

    @Test fun `字段数不符时视为无缓存`() {
        val broken = sample().encode().substringBefore("|").split(",").dropLast(1).joinToString(",") + "|0"
        assertNull(ProtectThresholds.decode(broken))
    }

    @Test fun `空串与乱码返回 null`() {
        assertNull(ProtectThresholds.decode(null))
        assertNull(ProtectThresholds.decode(""))
        assertNull(ProtectThresholds.decode("abc|def"))
    }

    @Test fun `只有保存时间不同不算变化 避免反复写盘`() {
        val a = sample()
        val b = a.copy(savedAtMs = a.savedAtMs + 60_000L)
        assertFalse(a.differsFrom(b))
    }

    @Test fun `阈值真的变了才算变化`() {
        val a = sample()
        assertTrue(a.differsFrom(a.copy(overVoltSet = 4.25f)))
        assertTrue(a.differsFrom(a.copy(mosOverTemp = 90f)))
    }

    @Test fun `注入后标记为缓存来源 且阈值生效`() {
        val d = ProtectThresholds.decode(sample().encode())!!.applyTo(BmsRealtimeData())
        assertTrue(d.hasProtectParams)
        assertTrue(d.protectParamsFromCache)
        assertEquals(4.2f, d.overVoltSet, 0f)
        assertEquals(1_700_000_000_000L, d.protectParamsSavedAtMs)
    }

    @Test fun `从实时数据摘取阈值再注入 不改变数值`() {
        val src = BmsRealtimeData(
            hasProtectParams = true,
            overVoltSet = 4.2f, underVoltSet = 3.0f, mosOverTemp = 95f,
            overCurrentDischarge = 157.5f, packOverVoltSet = 71.4f,
        )
        val injected = ProtectThresholds.of(src, 123L).applyTo(BmsRealtimeData())
        assertEquals(4.2f, injected.overVoltSet, 0f)
        assertEquals(3.0f, injected.underVoltSet, 0f)
        assertEquals(95f, injected.mosOverTemp, 0f)
        assertEquals(157.5f, injected.overCurrentDischarge, 0f)
        assertEquals(71.4f, injected.packOverVoltSet, 0f)
    }
}

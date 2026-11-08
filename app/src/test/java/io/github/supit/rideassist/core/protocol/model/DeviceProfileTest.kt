package io.github.supit.rideassist.core.protocol.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ 设备信息 / 电池信息本地缓存 + **换板子检测**。
 *
 * 用户要求：设备信息也缓存；并且换板子时旧阈值必须自动作废 ——
 * 阈值缓存是纯数值，看不出属于哪块板子，误用会把正常状态报成故障。
 */
class DeviceProfileTest {

    private fun sample() = DeviceProfile(
        productName = "YY-BCU12-MOS-20S150A-MUX",
        firmwareVersion = "1.43",
        serialNumber = "SN123456",
        batteryType = 1,
        maxCellNumSupported = 24,
        tempProbeCount = 2,
        cellCount = 20,
        ratedCapacity = 60f,
        nominalTotalVolt = 64f,
        sleepTimeSec = 60,
        savedAtMs = 1_700_000_000_000L,
    )

    @Test fun `编码解码往返一致`() {
        val back = DeviceProfile.decode(sample().encode())!!
        assertFalse(sample().differsFrom(back))
        assertEquals("YY-BCU12-MOS-20S150A-MUX", back.productName)
        assertEquals(60f, back.ratedCapacity, 0f)
        assertEquals(1_700_000_000_000L, back.savedAtMs)
    }

    @Test fun `字段数不符或空值视为无缓存`() {
        assertNull(DeviceProfile.decode(null))
        assertNull(DeviceProfile.decode(""))
        assertNull(DeviceProfile.decode("a\tb"))
    }

    @Test fun `只有保存时间不同不算变化`() {
        val a = sample()
        assertFalse(a.differsFrom(a.copy(savedAtMs = a.savedAtMs + 1000)))
    }

    @Test fun `型号变化算变化`() {
        assertTrue(sample().differsFrom(sample().copy(productName = "YY-OTHER")))
    }

    // ====== 换板子检测 ======

    @Test fun `型号不同判为换了板子`() {
        val cached = sample()
        val now = sample().copy(productName = "YY-BCU16-MOS-24S200A")
        assertTrue(DeviceProfile.isDifferentBoard(cached, now))
    }

    @Test fun `型号相同不算换板子`() {
        assertFalse(DeviceProfile.isDifferentBoard(sample(), sample()))
    }

    @Test fun `当前型号还没读到时不判换板子`() {
        assertFalse(DeviceProfile.isDifferentBoard(sample(), sample().copy(productName = "")))
    }

    @Test fun `没有缓存时不判换板子`() {
        assertFalse(DeviceProfile.isDifferentBoard(null, sample()))
    }

    // ====== 面板展示 ======

    @Test fun `缓存能生成设备信息行`() {
        val rows = sample().identityRows()
        val map = rows.associate { it[0] to it[1] }
        assertEquals("YY-BCU12-MOS-20S150A-MUX", map["保护板型号"])
        assertEquals("1.43", map["版本"])
        assertEquals("SN123456", map["SN"])
        assertEquals("铁锂", map["电池类型"])
        assertEquals("60.0 Ah", map["额定容量"])
        assertEquals("64.00 V", map["标称总压"])
    }

    @Test fun `空缓存不产生任何行`() {
        assertTrue(DeviceProfile().identityRows().isEmpty())
    }
}

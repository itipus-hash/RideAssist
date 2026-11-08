package io.github.supit.rideassist.core.protocol.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐ 用户实测 bug 的回归锁：
 * **充电结束后连上保护板，误报了一次「单体过压」**（实测 4.145V、阈值未读到）。
 *
 * 根因：`effectiveAlarms()` 在 `hasProtectParams=false` 时直接照搬硬件告警位，
 * 而充电末期的过压位带迟滞、充电结束后仍残留。
 *
 * 现在：没有阈值来源时，**有物理阈值的项一律不判定**（诊断面板标"未判定"），
 * 只保留采样/执行器失效这类本就只能看硬件位的项。
 */
class AlarmFallbackTest {

    @Test fun `阈值完全缺失时 硬件残留的单体过压位不再报警`() {
        val d = BmsRealtimeData(
            hasDeviceData = true,
            hasProtectParams = false,
            cellVoltages = listOf(4.145f, 4.137f),
            alarmFlags = listOf(AlarmFlag.CELL_OVER_VOLTAGE),
        )
        assertFalse(d.effectiveAlarms().contains(AlarmFlag.CELL_OVER_VOLTAGE))
        assertTrue(d.effectiveAlarms().isEmpty())
    }

    @Test fun `阈值缺失时 无独立阈值的失效项仍然照搬硬件位`() {
        val d = BmsRealtimeData(
            hasDeviceData = true,
            hasProtectParams = false,
            alarmFlags = listOf(AlarmFlag.VOLT_SENSOR_FAIL, AlarmFlag.CELL_OVER_VOLTAGE),
        )
        val eff = d.effectiveAlarms()
        assertTrue(eff.contains(AlarmFlag.VOLT_SENSOR_FAIL))
        assertFalse(eff.contains(AlarmFlag.CELL_OVER_VOLTAGE))
    }

    @Test fun `有阈值时 4_145V 未达 4_2V 不报单体过压`() {
        val d = BmsRealtimeData(
            hasDeviceData = true,
            hasProtectParams = true,
            cellVoltages = listOf(4.145f, 4.137f),
            overVoltSet = 4.2f,
            alarmFlags = listOf(AlarmFlag.CELL_OVER_VOLTAGE),
        )
        assertFalse(d.effectiveAlarms().contains(AlarmFlag.CELL_OVER_VOLTAGE))
    }

    @Test fun `有阈值时真的超了才报`() {
        val d = BmsRealtimeData(
            hasDeviceData = true,
            hasProtectParams = true,
            cellVoltages = listOf(4.25f, 4.2f),
            overVoltSet = 4.2f,
        )
        assertTrue(d.effectiveAlarms().contains(AlarmFlag.CELL_OVER_VOLTAGE))
    }

    // ====== 诊断面板：缺值显示 `-`（用户要求） ======

    @Test fun `无设备数据时实测列显示短横线`() {
        val rows = BmsRealtimeData(hasDeviceData = false).alarmDiagnostics()
        assertTrue(rows.isNotEmpty())
        val overVolt = rows.first { it[0] == "单体过压" }
        assertEquals("-", overVolt[1])
    }

    @Test fun `阈值未读到时阈值列显示短横线 而不是 0_000V`() {
        val rows = BmsRealtimeData(
            hasDeviceData = true,
            hasProtectParams = false,
            cellVoltages = listOf(4.145f),
        ).alarmDiagnostics()
        val overVolt = rows.first { it[0] == "单体过压" }
        assertEquals("4.145V", overVolt[1])
        assertEquals("-", overVolt[2])
    }

    @Test fun `既无数据也无阈值时两列都是短横线`() {
        val rows = BmsRealtimeData(hasDeviceData = false).alarmDiagnostics()
        val mos = rows.first { it[0] == "MOS过温" }
        assertEquals("-", mos[1])
        assertEquals("-", mos[2])
    }

    @Test fun `阈值读到后阈值列正常显示`() {
        val rows = BmsRealtimeData(
            hasDeviceData = true,
            hasProtectParams = true,
            cellVoltages = listOf(4.1f),
            overVoltSet = 4.2f,
        ).alarmDiagnostics()
        val overVolt = rows.first { it[0] == "单体过压" }
        assertEquals("4.100V", overVolt[1])
        assertEquals("4.200V", overVolt[2])
    }
}

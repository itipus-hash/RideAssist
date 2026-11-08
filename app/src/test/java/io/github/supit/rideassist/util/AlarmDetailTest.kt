package io.github.supit.rideassist.util

import io.github.supit.rideassist.core.protocol.model.AlarmFlag
import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ 用户反馈「有时候会出现保护板警告，但是又看不出是警告的啥」。
 *
 * 弹窗必须能回答三件事：告警的是哪一项、实测多少、阈值多少。
 * 实机告警是偶发的，靠截图等不到，所以把文案生成抽成纯 JVM 单测钉死。
 */
class AlarmDetailTest {

    private val base = BmsRealtimeData(
        hasDeviceData = true,
        hasProtectParams = true,
        totalVoltage = 70.28f,
        totalCurrent = 6.63f,
        soc = 100f,
        cellDiff = 6f,
        cellVoltages = listOf(4.149f, 4.141f),
        mosTemp = 96f,
        temp1 = 29f,
        temp2 = 28f,
        mosOverTemp = 95f,
        overTempDischarge = 60f,
        underTempDischarge = -20f,
        overCurrentDischarge = 157.5f,
        overCurrentCharge = 75f,
        overVoltSet = 4.2f,
        underVoltSet = 3.0f,
    )

    @Test fun `MOS 过温必须写出实测温度与本机阈值`() {
        val text = AlarmDetail.build(base, listOf(AlarmFlag.OVER_TEMP_MOS))
        assertTrue(text, text.contains("MOS过温"))
        assertTrue(text, text.contains("96.0 ℃"))
        assertTrue(text, text.contains("95.0 ℃"))
        assertTrue(text, text.contains("＞"))
        assertTrue(text, text.contains("MOS 温度"))
    }

    @Test fun `温度类告警指出是哪一路探头最热`() {
        val text = AlarmDetail.build(base, listOf(AlarmFlag.OVER_TEMP_DISCHARGE))
        assertTrue(text, text.contains("放电过温"))
        assertTrue(text, text.contains("MOS"))
        assertTrue(text, text.contains("60.0 ℃"))
        assertTrue(text, text.contains("探头 1"))
        assertTrue(text, text.contains("探头 2"))
    }

    @Test fun `电流类告警给出实测电流与过流阈值`() {
        val text = AlarmDetail.build(base, listOf(AlarmFlag.OVER_CURRENT_DISCHARGE))
        assertTrue(text, text.contains("6.63 A"))
        assertTrue(text, text.contains("157.50 A"))
    }

    @Test fun `电压类告警给出单体实测与阈值`() {
        val text = AlarmDetail.build(base, listOf(AlarmFlag.CELL_OVER_VOLTAGE))
        assertTrue(text, text.contains("4.149 V"))
        assertTrue(text, text.contains("4.200 V"))
    }

    @Test fun `一帧多条告警逐项列出判据`() {
        val text = AlarmDetail.build(
            base, listOf(AlarmFlag.OVER_TEMP_MOS, AlarmFlag.VOLT_SENSOR_FAIL)
        )
        assertTrue(text, text.contains("MOS过温"))
        assertTrue(text, text.contains("电压失效"))
        assertTrue(text, text.contains("按保护板硬件告警位判定"))
    }

    @Test fun `阈值读不到时如实说明按硬件位判定 不编造数字`() {
        val noParams = base.copy(
            hasProtectParams = false,
            mosOverTemp = 0f,
            overTempDischarge = 0f,
        )
        val text = AlarmDetail.build(noParams, listOf(AlarmFlag.OVER_TEMP_MOS))
        assertTrue(text, text.contains("阈值未读到"))
        assertTrue(text, text.contains("保护参数帧未读到"))
    }

    @Test fun `没有探头的项整项不出现 不留占位符`() {
        val noTemp = BmsRealtimeData(
            hasDeviceData = true,
            hasProtectParams = true,
            totalVoltage = 70.28f,
            totalCurrent = 0f,
            soc = 100f,
            mosOverTemp = 95f,
        )
        val text = AlarmDetail.build(noTemp, listOf(AlarmFlag.OVER_TEMP_MOS))
        assertFalse(text, text.contains("MOS 温度"))
        assertFalse(text, text.contains("探头 1"))
        assertFalse(text, text.contains("--"))
        assertTrue(text, text.contains("无独立阈值"))
    }

    @Test fun `实时读数列齐本机实测值`() {
        val text = AlarmDetail.build(base, listOf(AlarmFlag.OVER_TEMP_MOS))
        assertTrue(text, text.contains("实时读数"))
        assertTrue(text, text.contains("总压　70.28 V"))
        assertTrue(text, text.contains("电流　6.63 A"))
        assertTrue(text, text.contains("SOC　100 %"))
        assertTrue(text, text.contains("最高单体　4.149 V"))
    }
}

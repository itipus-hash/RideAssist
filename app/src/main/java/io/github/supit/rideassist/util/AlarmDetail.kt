package io.github.supit.rideassist.util

import io.github.supit.rideassist.core.protocol.model.AlarmFlag
import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData

/**
 * ⭐⭐ **保护板告警弹窗的「到底在警告什么」**。
 *
 * ## 为什么单独抽出来
 * 旧弹窗只列了总压 / 电流 / SOC / 压差四个**无关的实时值**，
 * 用户看到「MOS 过温」却不知道哪一项实测超了哪一项阈值，
 * 反馈原话：「有时候会出现保护板警告，但是又看不出是警告的啥」。
 * 现在每项告警都给出**实测值 vs 本机保护参数阈值**的一行判据，
 * 并把 MOS / 各探头温度、单体电压等本机实测读数完整列出来。
 *
 * ## 口径
 * - 阈值一律来自设备自己的 0xA0 保护参数帧，**不写死任何第三方参照值**。
 * - 读不到的项**整项不出现**（不留 `--` 占位）。
 * - 无独立阈值的硬件类告警（欠压关断 / SOC 异常 / 采样失效…）明确写出
 *   「按保护板硬件告警位判定」，不再让弹窗看起来像空内容。
 *
 * 纯 JVM：数据类无 Android 依赖，判据可用单测钉死。
 */
object AlarmDetail {

    /** 生成弹窗正文（不含标题与告警项名，那两行由调用方拼） */
    fun build(d: BmsRealtimeData, flags: List<AlarmFlag>): String = buildString {
        appendLine("判定依据")
        if (flags.isEmpty()) {
            appendLine("保护板硬件告警位判定（本帧未解析出具体告警项）")
        } else {
            flags.forEach { appendLine(reason(d, it)) }
        }
        if (!d.hasProtectParams) {
            appendLine("保护参数帧未读到，本次结论来自保护板硬件告警位")
        }

        appendLine()
        appendLine("实时读数")
        if (d.totalVoltage > 0f) appendLine("总压　%.2f V".format(d.totalVoltage))
        appendLine("电流　%.2f A".format(d.totalCurrent))
        if (d.soc > 0f) appendLine("SOC　%d %%".format(d.soc.toInt()))
        if (d.cellDiff > 0f) appendLine("压差　%d mV".format(d.cellDiff.toInt()))
        if (d.maxCellVolt > 0f) appendLine("最高单体　%.3f V".format(d.maxCellVolt))
        if (d.minCellVolt > 0f) appendLine("最低单体　%.3f V".format(d.minCellVolt))
        d.mosTemp?.let { appendLine("MOS 温度　%.1f ℃".format(it)) }
        d.temp1?.let { appendLine("探头 1　%.1f ℃".format(it)) }
        d.temp2?.let { appendLine("探头 2　%.1f ℃".format(it)) }
        d.temp3?.let { appendLine("探头 3　%.1f ℃".format(it)) }
    }

    /** 单项告警的判据行：`<告警名>　实测 X ＜/＞ 阈值 Y` */
    private fun reason(d: BmsRealtimeData, f: AlarmFlag): String {
        // 阈值有效才写「实测 vs 阈值」；否则如实说明按硬件位判定
        fun cmp(measured: String, op: String, limit: Float, limitText: String, note: String = ""): String =
            if (limit > 0f) "${f.label}　实测 $measured $op 阈值 $limitText$note"
            else "${f.label}　实测 $measured（阈值未读到，按硬件告警位判定）"

        fun hardwareOnly(): String = "${f.label}　该项无独立阈值，按保护板硬件告警位判定"

        val charging = d.totalCurrent < 0f
        return when (f) {
            AlarmFlag.CELL_OVER_VOLTAGE -> cmp(
                "%.3f V".format(d.maxCellVolt), "＞", d.overVoltSet,
                "%.3f V".format(d.overVoltSet), "（最高单体）"
            )
            AlarmFlag.CELL_UNDERVOLTAGE -> cmp(
                "%.3f V".format(d.minCellVolt), "＜", d.underVoltSet,
                "%.3f V".format(d.underVoltSet), "（最低单体）"
            )
            AlarmFlag.PACK_OVER_VOLTAGE -> cmp(
                "%.2f V".format(d.totalVoltage), "＞", d.packOverVoltSet,
                "%.2f V".format(d.packOverVoltSet)
            )
            AlarmFlag.PACK_UNDERVOLTAGE -> cmp(
                "%.2f V".format(d.totalVoltage), "＜", d.packUnderVoltSet,
                "%.2f V".format(d.packUnderVoltSet)
            )
            AlarmFlag.OVER_CURRENT_CHARGE -> cmp(
                "%.2f A".format(kotlin.math.abs(d.totalCurrent)), "＞",
                d.overCurrentCharge, "%.2f A".format(d.overCurrentCharge), "（充电电流取绝对值）"
            )
            AlarmFlag.OVER_CURRENT_DISCHARGE, AlarmFlag.HW_OVER_CURRENT -> cmp(
                "%.2f A".format(d.totalCurrent), "＞",
                d.overCurrentDischarge, "%.2f A".format(d.overCurrentDischarge)
            )
            AlarmFlag.SHORT_CIRCUIT -> cmp(
                "%.2f A".format(d.totalCurrent), "＞",
                d.shortCircuitCurrent, "%.2f A".format(d.shortCircuitCurrent)
            )
            AlarmFlag.OVER_TEMP_CHARGE, AlarmFlag.OVER_TEMP_DISCHARGE -> {
                val hottest = hottest(d)
                val th = if (charging) d.overTempCharge else d.overTempDischarge
                if (hottest == null) hardwareOnly()
                else cmp(
                    "%.1f ℃".format(hottest.second), "＞", th,
                    "%.1f ℃".format(th), "（${hottest.first}）"
                )
            }
            AlarmFlag.UNDER_TEMP_CHARGE, AlarmFlag.UNDER_TEMP_DISCHARGE -> {
                val coldest = coldest(d)
                val th = if (charging) d.underTempCharge else d.underTempDischarge
                if (coldest == null) hardwareOnly()
                else cmp(
                    "%.1f ℃".format(coldest.second), "＜", th,
                    "%.1f ℃".format(th), "（${coldest.first}）"
                )
            }
            AlarmFlag.OVER_TEMP_MOS -> {
                val t = d.mosTemp
                if (t == null) hardwareOnly()
                else cmp(
                    "%.1f ℃".format(t), "＞", d.mosOverTemp,
                    "%.1f ℃".format(d.mosOverTemp), "（MOS 温度）"
                )
            }
            // 欠压关断 / SOC 异常 / 均衡与采样失效：0xA0 帧没有对应阈值
            else -> hardwareOnly()
        }
    }

    /** 与 BmsRealtimeData.maxMeasuredTemp() 同源，额外带上传感器名字方便定位 */
    private fun hottest(d: BmsRealtimeData): Pair<String, Float>? = candidates(d).maxByOrNull { it.second }

    /** 与 BmsRealtimeData.minMeasuredTemp() 同源 */
    private fun coldest(d: BmsRealtimeData): Pair<String, Float>? = candidates(d).minByOrNull { it.second }

    private fun candidates(d: BmsRealtimeData): List<Pair<String, Float>> = listOfNotNull(
        d.mosTemp?.let { "MOS" to it },
        d.temp1?.let { "探头 1" to it },
        d.temp2?.let { "探头 2" to it },
        d.temp3?.let { "探头 3" to it },
    )
}

package io.github.supit.rideassist.core.protocol.model

/**
 * ⭐⭐ **电池实况的本地快照**（可持久化）。
 *
 * 用户反复提：「断开 BMS，电池信息直接丢失，应该保存最后一次的记录」。
 * 内存快照不够 —— 诊断面板每次打开都是新 Activity，APP 重启更是全丢。
 * 所以把诊断面板「电池实况」需要的那些字段落到 `device_cache`。
 *
 * 只挑**展示用**的标量，不存整帧：`BmsRealtimeData` 有 60 多个字段、
 * 还有 `cellVoltages` 这类长列表，整帧序列化又大又容易随字段增删失配。
 *
 * 编码：TAB 分隔，温度缺失写成 `nan`（TAB 是 XML 合法字符，控制字符不行）。
 */
data class BatterySnapshot(
    val batteryType: Int = -1,
    val cellCount: Int = 0,
    val maxCellNumSupported: Int = 0,
    val tempProbeCount: Int = 0,
    val totalVoltage: Float = 0f,
    val totalCurrent: Float = 0f,
    val soc: Float = 0f,
    val remainingAh: Float = 0f,
    val ratedAh: Float = 0f,
    val cycleCount: Int = 0,
    val mosTemp: Float? = null,
    val temp1: Float? = null,
    val temp2: Float? = null,
    val temp3: Float? = null,
    val balanceOn: Boolean = false,
    /** 正在均衡的串号，逗号分隔（空 = 无） */
    val balancingCells: String = "",
    val firmware: String = "",
    val savedAtMs: Long = 0L,
) {

    /** 转成只用于**展示**的实时数据（`hasDeviceData=false`，不参与任何判定） */
    fun toDisplayData(): BmsRealtimeData = BmsRealtimeData(
        batteryType = batteryType,
        cellCount = cellCount,
        maxCellNumSupported = maxCellNumSupported,
        tempProbeCount = tempProbeCount,
        totalVoltage = totalVoltage,
        totalCurrent = totalCurrent,
        soc = soc,
        remainingCapacity = remainingAh,
        ratedCapacity = ratedAh,
        cycleCount = cycleCount,
        mosTemp = mosTemp,
        temp1 = temp1,
        temp2 = temp2,
        temp3 = temp3,
        balanceOn = balanceOn,
        balancingCells = balancingCells.split(',').mapNotNull { it.toIntOrNull() },
        firmwareVersion = firmware.ifBlank { "--" },
        hasDeviceData = false,
    )

    fun encode(): String = listOf(
        batteryType.toString(), cellCount.toString(),
        maxCellNumSupported.toString(), tempProbeCount.toString(),
        totalVoltage.toString(), totalCurrent.toString(),
        soc.toString(), remainingAh.toString(), ratedAh.toString(),
        cycleCount.toString(),
        mosTemp?.toString() ?: NAN, temp1?.toString() ?: NAN,
        temp2?.toString() ?: NAN, temp3?.toString() ?: NAN,
        balanceOn.toString(), balancingCells,
        firmware.replace('\t', ' ').replace('\n', ' '),
        savedAtMs.toString(),
    ).joinToString(TAB)

    companion object {
        private const val TAB = "\t"
        private const val NAN = "nan"

        /** 编码字段数；**只增不减** */
        const val FIELD_COUNT = 18

        fun of(d: BmsRealtimeData, savedAtMs: Long): BatterySnapshot = BatterySnapshot(
            batteryType = d.batteryType,
            cellCount = d.cellCount,
            maxCellNumSupported = d.maxCellNumSupported,
            tempProbeCount = d.tempProbeCount,
            totalVoltage = d.totalVoltage,
            totalCurrent = d.totalCurrent,
            soc = d.soc,
            remainingAh = d.remainingCapacity,
            ratedAh = d.ratedCapacity,
            cycleCount = d.cycleCount,
            mosTemp = d.mosTemp,
            temp1 = d.temp1,
            temp2 = d.temp2,
            temp3 = d.temp3,
            balanceOn = d.balanceOn,
            balancingCells = d.balancingCells.joinToString(","),
            firmware = d.firmwareVersion,
            savedAtMs = savedAtMs,
        )

        fun decode(raw: String?): BatterySnapshot? {
            if (raw.isNullOrBlank()) return null
            val f = raw.split(TAB)
            if (f.size != FIELD_COUNT) return null
            fun num(i: Int): Float? = f[i].takeIf { it != NAN }?.toFloatOrNull()
            return BatterySnapshot(
                batteryType = f[0].toIntOrNull() ?: return null,
                cellCount = f[1].toIntOrNull() ?: return null,
                maxCellNumSupported = f[2].toIntOrNull() ?: return null,
                tempProbeCount = f[3].toIntOrNull() ?: return null,
                totalVoltage = f[4].toFloatOrNull() ?: return null,
                totalCurrent = f[5].toFloatOrNull() ?: return null,
                soc = f[6].toFloatOrNull() ?: return null,
                remainingAh = f[7].toFloatOrNull() ?: return null,
                ratedAh = f[8].toFloatOrNull() ?: return null,
                cycleCount = f[9].toIntOrNull() ?: return null,
                mosTemp = num(10), temp1 = num(11),
                temp2 = num(12), temp3 = num(13),
                balanceOn = f[14] == "true",
                balancingCells = f[15],
                firmware = f[16],
                savedAtMs = f[17].toLongOrNull() ?: 0L,
            )
        }
    }
}

package io.github.supit.rideassist.core.protocol.model

/**
 * ⭐⭐ **保护板的静态身份信息快照**（可持久化）。
 *
 * 用户要求：电池信息 / 设备信息也要缓存到本地，没连保护板时也能看。
 *
 * 同时它还承担一个更重要的职责：**换板子检测**。
 * 阈值缓存是按位置编码的纯数值，本身看不出属于哪块板子；
 * 一旦换了一块参数不同的板子，旧阈值会被当成新板子的阈值去判定，
 * 可能把正常状态报成故障、也可能漏报。所以缓存里记下型号
 * （`YY-BCU12-MOS-20S150A-MUX` 这种，来自设备信息帧），
 * 型号一变就把阈值缓存一起作废、等下一条 0xA0 帧重新读。
 *
 * ## 编码格式
 * 字段用 **TAB** 拼接（见 [FIELD_COUNT] 旁的注释：XML 不允许控制字符）。字段顺序**只能追加不能重排**，数量不符视为无效。
 */
data class DeviceProfile(
    /** 产品型号，换板子检测的唯一依据 */
    val productName: String = "",
    val firmwareVersion: String = "",
    val serialNumber: String = "",
    val batteryType: Int = -1,
    val maxCellNumSupported: Int = 0,
    val tempProbeCount: Int = 0,
    /** 实际串数（来自实时帧，属于电池信息） */
    val cellCount: Int = 0,
    val ratedCapacity: Float = 0f,
    val nominalTotalVolt: Float = 0f,
    val sleepTimeSec: Int = 0,
    val savedAtMs: Long = 0L,
) {

    /**
     * ⭐ **字段级合并**：新读到的值若是默认值（0 / -1 / 空），用旧缓存补上。
     *
     * 场景：设备信息帧是分帧到达的，连接后第一秒可能只读到型号和串数，
     * 探头数 / 额定容量 / 休眠时间还没到 —— 这时若直接覆盖缓存，
     * 就会把之前完整缓存的这几个字段抹成 0（「温度探头」「额定容量」
     * 「休眠时间」三行随之消失，用户 2026-11-08 实测）。
     */
    fun mergedWith(cached: DeviceProfile?): DeviceProfile {
        val c = cached ?: return this
        return copy(
            tempProbeCount = if (tempProbeCount > 0) tempProbeCount else c.tempProbeCount,
            cellCount = if (cellCount > 0) cellCount else c.cellCount,
            maxCellNumSupported = if (maxCellNumSupported > 0) maxCellNumSupported
            else c.maxCellNumSupported,
            batteryType = if (batteryType >= 0) batteryType else c.batteryType,
            ratedCapacity = if (ratedCapacity > 0f) ratedCapacity else c.ratedCapacity,
            sleepTimeSec = if (sleepTimeSec > 0) sleepTimeSec else c.sleepTimeSec,
            firmwareVersion = firmwareVersion.takeIf { it.isNotBlank() && it != "--" }
                ?: c.firmwareVersion,
        )
    }

    /** 仅比较内容（忽略保存时间） */
    fun differsFrom(other: DeviceProfile): Boolean =
        productName != other.productName ||
            firmwareVersion != other.firmwareVersion ||
            serialNumber != other.serialNumber ||
            batteryType != other.batteryType ||
            maxCellNumSupported != other.maxCellNumSupported ||
            tempProbeCount != other.tempProbeCount ||
            cellCount != other.cellCount ||
            ratedCapacity != other.ratedCapacity ||
            nominalTotalVolt != other.nominalTotalVolt ||
            sleepTimeSec != other.sleepTimeSec

    fun encode(): String = listOf(
        productName, firmwareVersion, serialNumber,
        batteryType.toString(), maxCellNumSupported.toString(),
        tempProbeCount.toString(), cellCount.toString(),
        ratedCapacity.toString(), nominalTotalVolt.toString(),
        sleepTimeSec.toString(), savedAtMs.toString(),
    ).joinToString(SEP)

    /**
     * 用于诊断面板「设备信息」区块（与 `BmsRealtimeData.deviceIdentity()`
     * 同格式、同顺序），多补了电池信息三项。
     */
    fun identityRows(): List<List<String>> {
        val rows = mutableListOf<Pair<String, String>>()
        fun row(label: String, value: String) { rows.add(label to value) }
        if (productName.isNotEmpty()) row("保护板型号", productName)
        if (firmwareVersion.isNotEmpty() && firmwareVersion != "--") row("版本", firmwareVersion)
        if (serialNumber.isNotEmpty()) row("SN", serialNumber)
        if (batteryType >= 0) row("电池类型", batteryTypeName(batteryType))
        if (maxCellNumSupported > 0) row("最大串数", "$maxCellNumSupported")
        if (cellCount > 0) row("实际串数", "$cellCount")
        if (tempProbeCount > 0) row("温度探头", "$tempProbeCount 路")
        if (ratedCapacity > 0f) row("额定容量", "%.1f Ah".format(ratedCapacity))
        if (nominalTotalVolt > 0f) row("标称总压", "%.2f V".format(nominalTotalVolt))
        if (sleepTimeSec > 0) row("休眠时间", "${sleepTimeSec}S")
        return rows.map { listOf(it.first, it.second) }
    }

    companion object {
        /**
         * 分隔符用 **TAB**，不能用 `\u0001` 之类的控制字符。
         *
         * ⚠️ SharedPreferences 落盘是 XML，而 XML 1.0 只允许 0x09 / 0x0A / 0x0D
         * 与 0x20 以上的字符 —— 塞 `\u0001` 进去会让整个 prefs 文件写坏/读不回。
         * TAB 合法，且型号 / 版本 / SN 里不会出现 TAB。
         */
        private const val SEP = "\t"

        /** 编码字段数；**只增不减**，否则旧缓存判为无效 */
        const val FIELD_COUNT = 11

        /**
         * 缓存的板子与当前读到的板子**是否是两块不同的板子**。
         *
         * 只有两边都读到了型号才敢下结论 —— 型号为空说明设备信息帧还没到，
         * 此时判"换了"会把好缓存误清。
         */
        fun isDifferentBoard(cached: DeviceProfile?, current: DeviceProfile): Boolean =
            cached != null &&
                cached.productName.isNotBlank() &&
                current.productName.isNotBlank() &&
                cached.productName != current.productName

        fun of(d: BmsRealtimeData, savedAtMs: Long): DeviceProfile = DeviceProfile(
            productName = d.productName.trim(),
            firmwareVersion = d.firmwareVersion,
            serialNumber = d.serialNumber,
            batteryType = d.batteryType,
            maxCellNumSupported = d.maxCellNumSupported,
            tempProbeCount = d.tempProbeCount,
            cellCount = d.cellCount,
            ratedCapacity = d.ratedCapacity,
            nominalTotalVolt = if (d.nominalTotalVolt > 0f) d.nominalTotalVolt else 0f,
            sleepTimeSec = d.sleepTimeSec,
            savedAtMs = savedAtMs,
        )

        fun decode(raw: String?): DeviceProfile? {
            if (raw.isNullOrBlank()) return null
            val f = raw.split(SEP)
            if (f.size != FIELD_COUNT) return null
            return DeviceProfile(
                productName = f[0],
                firmwareVersion = f[1],
                serialNumber = f[2],
                batteryType = f[3].toIntOrNull() ?: return null,
                maxCellNumSupported = f[4].toIntOrNull() ?: return null,
                tempProbeCount = f[5].toIntOrNull() ?: return null,
                cellCount = f[6].toIntOrNull() ?: return null,
                ratedCapacity = f[7].toFloatOrNull() ?: return null,
                nominalTotalVolt = f[8].toFloatOrNull() ?: return null,
                sleepTimeSec = f[9].toIntOrNull() ?: return null,
                savedAtMs = f[10].toLongOrNull() ?: 0L,
            )
        }

        /** 与 `BmsRealtimeData.batteryTypeName` 同一套映射，避免两处口径 */
        private fun batteryTypeName(t: Int): String = when (t) {
            0 -> "三元"
            1 -> "铁锂"
            2 -> "铅酸"
            3 -> "钠离子"
            else -> "未知"
        }
    }
}

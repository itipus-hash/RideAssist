package io.github.powerycy.rideassist.core.ble

import java.util.UUID

/**
 * 彦阳 BMS 蓝牙透传模块 UUID 配置。
 *
 * ⚠️ 不同批次的彦阳保护板蓝牙模组 UUID 可能不同，
 * 现场如遇连不上/收不到数据，只需在此处替换为模组规格书中的 UUID 即可。
 */
object BleUuids {

    /**
     * 服务/特征 UUID。
     *
     * 📌 已通过真机 adb 抓 GATT 实测校准（设备 YY-NBCL-6666）：
     * ```
     * SERVICE 0000ffe0
     *   CH 0000ffe1props=0x1e  [NOTIFY] [WRITE] [READ]  ← 通知特征
     *   CH 0000ffe2  props=0x0c  [WRITE]                 ← 仅写
     * ```
     * 关键点：**通知走 FFE1**，FFE2 只能写。之前两者都填 FFE2，
     * 表现为"写帧日志正常但永远收不到数据"。
     *
     * 换批次模组时：跑 `adb logcat -s BleManager:I`，
     * 从 "GATT TABLE" 日志核对真实 UUID，不要靠猜。
     */
    var SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    /** 通知特征（BMS → APP） */
    var NOTIFY_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    /** 写特征（APP → BMS，只发送读命令） */
    var WRITE_UUID: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

    /** 常见备选模组 UUID，换板子时在此尝试 */
    val ALT_SERVICE_UUIDS = listOf(
        UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb")
    )

    /** 设备名关键词，用于过滤彦阳设备 */
    val DEVICE_KEYWORDS = listOf(
        "YY", "yy", "YanYang", "yanyang", "彦阳", "BMS", "bms", "JY"
    )

    fun isYanYangDevice(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return DEVICE_KEYWORDS.any { name.contains(it) }
    }
}
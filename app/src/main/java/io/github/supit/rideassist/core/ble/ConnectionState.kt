package io.github.supit.rideassist.core.ble

/** 连接状态机 */
enum class ConnectionState {
    /** 未连接（初始态） */
    DISCONNECTED,

    /** 连接中（含自动连接过程） */
    CONNECTING,

    /** 已连接可通讯 */
    CONNECTED,

    /** 连接失败 */
    FAILED;

    val displayText: String
        get() = when (this) {
            DISCONNECTED -> "未连接"
            CONNECTING -> "连接中"
            CONNECTED -> "已连接"
            FAILED -> "未连接保护板"
        }
}

/** 扫描到的一台设备 */
data class ScanDevice(
    val name: String?,
    val macAddress: String,
    val rssi: Int,
    val isYanYang: Boolean
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "未知设备"
    val rssiText: String get() = "${rssi}dBm"
}
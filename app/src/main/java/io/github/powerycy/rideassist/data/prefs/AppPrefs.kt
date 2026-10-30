package io.github.powerycy.rideassist.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * 已保存设备记录与开关状态。
 *
 * ⚠️ 覆盖规则（关键）：只有新设备【连接成功】才调用 [saveConnectedDevice]，
 * 扫描到设备或连接失败都不写入，避免把不可用设备记成"上次设备"。
 */
class AppPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("yybms_prefs", Context.MODE_PRIVATE)

    var lastDeviceMac: String?
        get() = sp.getString(KEY_LAST_MAC, null)
        private set(value) = sp.edit { putString(KEY_LAST_MAC, value) }

    var lastDeviceName: String?
        get() = sp.getString(KEY_LAST_NAME, null)
        private set(value) = sp.edit { putString(KEY_LAST_NAME, value) }

    /** 启动自动连接开关，默认开启 */
    var autoConnectEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_CONNECT, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_CONNECT, value) }

    /** 震动提醒开关，默认开启 */
    var vibrateEnabled: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, true)
        set(value) = sp.edit { putBoolean(KEY_VIBRATE, value) }

    /** 弹窗告警开关，默认开启 */
    var alertDialogEnabled: Boolean
        get() = sp.getBoolean(KEY_ALERT_DIALOG, true)
        set(value) = sp.edit { putBoolean(KEY_ALERT_DIALOG, value) }

    // ==================== 胎压监测（TPMS） ====================
    // 绑定 = 轮位 → 传感器 SN（广播包 6B 身份码的 hex 串），纯本地记录，
    // 与传感器无任何握手（传感器是纯广播 beacon，见 TPMS_REVERSE.md）。

    /** 前轮绑定的传感器 SN，null/空 = 未绑定 */
    var tpmsFrontSn: String?
        get() = sp.getString(KEY_TPMS_FRONT_SN, null)
        set(value) = sp.edit { putString(KEY_TPMS_FRONT_SN, value) }

    /** 后轮绑定的传感器 SN，null/空 = 未绑定 */
    var tpmsRearSn: String?
        get() = sp.getString(KEY_TPMS_REAR_SN, null)
        set(value) = sp.edit { putString(KEY_TPMS_REAR_SN, value) }

    /** 胎压低压阈值 bar（低于标红），默认 1.5 */
    var tpmsLowBar: Float
        get() = sp.getFloat(KEY_TPMS_LOW_BAR, 1.5f)
        set(value) = sp.edit { putFloat(KEY_TPMS_LOW_BAR, value) }

    /** 胎压高压阈值 bar（高于标红），默认 3.0 */
    var tpmsHighBar: Float
        get() = sp.getFloat(KEY_TPMS_HIGH_BAR, 3.0f)
        set(value) = sp.edit { putFloat(KEY_TPMS_HIGH_BAR, value) }

    /** 胎温高温阈值 ℃（高于标红），默认 60 */
    var tpmsHighTemp: Int
        get() = sp.getInt(KEY_TPMS_HIGH_TEMP, 60)
        set(value) = sp.edit { putInt(KEY_TPMS_HIGH_TEMP, value) }

    /** 传感器电池型号（默认 CR1632，可改，方便更换时知道型号） */
    var tpmsBatteryModel: String
        get() = sp.getString(KEY_TPMS_BATTERY_MODEL, "CR1632") ?: "CR1632"
        set(value) = sp.edit { putString(KEY_TPMS_BATTERY_MODEL, value) }

    // ==================== 悬浮显示 ====================

    /** 悬浮项目开关：胎压（后续新增项目照此扩展） */
    var overlayShowTpms: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_TPMS, true)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_TPMS, value) }

    /** 悬浮框拖动后的 X 位置（px）；-1 = 未拖过，用默认位置 */
    var overlayX: Int
        get() = sp.getInt(KEY_OVERLAY_X, -1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_X, value) }

    /** 悬浮框拖动后的 Y 位置（px） */
    var overlayY: Int
        get() = sp.getInt(KEY_OVERLAY_Y, -1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_Y, value) }

    /** 悬浮项目开关（每项独立） */
    var overlayShowVolts: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_VOLTS, false)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_VOLTS, value) }

    var overlayShowAmps: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_AMPS, false)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_AMPS, value) }

    var overlayShowPower: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_POWER, false)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_POWER, value) }

    var overlayShowDiffLive: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_DIFF_LIVE, false)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_DIFF_LIVE, value) }

    var overlayShowDiffMax: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_DIFF_MAX, false)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_DIFF_MAX, value) }

    /** 悬浮项目开关：电量（电池状态，两颗任一低即「电量不足」） */
    var overlayShowBattery: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_BATTERY, false)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_BATTERY, value) }

    /** 悬浮项目开关：MOS 温度 */
    var overlayShowMosTemp: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_MOS_TEMP, false)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_MOS_TEMP, value) }

    /** 悬浮项目开关：最大速度（会话峰值，km/h） */
    var overlayShowMaxSpeed: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_MAX_SPEED, false)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_MAX_SPEED, value) }

    /** 悬浮项目开关：前轮胎压（默认继承旧「胎压监测」开关） */
    var overlayShowTpmsFront: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_TPMS_FRONT, sp.getBoolean("overlay_show_tpms", false))
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_TPMS_FRONT, value) }

    /** 悬浮项目开关：后轮胎压（默认继承旧「胎压监测」开关） */
    var overlayShowTpmsRear: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_TPMS_REAR, sp.getBoolean("overlay_show_tpms", false))
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_TPMS_REAR, value) }

    /** 悬浮项目开关：实时电流（默认继承旧「最大电流」开关） */
    var overlayShowAmpsLive: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_AMPS_LIVE, sp.getBoolean("overlay_show_amps", false))
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_AMPS_LIVE, value) }

    /** 悬浮项目开关：实时功率（默认继承旧「峰值功率」开关） */
    var overlayShowPowerLive: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_POWER_LIVE, sp.getBoolean("overlay_show_power", false))
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_POWER_LIVE, value) }

    /** 悬浮框背景不透明度 %（100=不透明，30=较透明） */
    var overlayBgAlpha: Int
        get() = sp.getInt(KEY_OVERLAY_BG_ALPHA, 70)
        set(value) = sp.edit { putInt(KEY_OVERLAY_BG_ALPHA, value) }

    /** 悬浮项目排序（逗号分隔的项目 key，按用户排列顺序） */
    var overlayOrder: String
        get() = sp.getString(KEY_OVERLAY_ORDER, "") ?: ""
        set(value) = sp.edit { putString(KEY_OVERLAY_ORDER, value) }

    /** 导航软件包名（「打开导航软件」按钮的目标；空 = 未选择） */
    var navAppPkg: String
        get() = sp.getString(KEY_NAV_APP_PKG, "") ?: ""
        set(value) = sp.edit { putString(KEY_NAV_APP_PKG, value) }

    /** ⭐ 点导航按钮时自动开启悬浮信息（用户要求可关） */
    var overlayAutoOnNav: Boolean
        get() = sp.getBoolean("overlay_auto_on_nav", true)
        set(value) = sp.edit { putBoolean("overlay_auto_on_nav", value) }

    /** ⭐ 点导航按钮时自动跳转实时页并聚焦速度卡（用户要求可关） */
    var overlayJumpOnNav: Boolean
        get() = sp.getBoolean("overlay_jump_on_nav", true)
        set(value) = sp.edit { putBoolean("overlay_jump_on_nav", value) }

    /** ⭐ 悬浮项目成对重排的一次性标记 */
    var pairResetDone: Boolean
        get() = sp.getBoolean("overlay_pair_reset_done", false)
        set(value) = sp.edit { putBoolean("overlay_pair_reset_done", value) }

    /** 快捷导航列表（JSON 数组：[{"name":"...","uri":"..."}]，支持多个） */
    var quickNavList: String
        get() = sp.getString(KEY_QUICK_NAV_LIST, "[]") ?: "[]"
        set(value) = sp.edit { putString(KEY_QUICK_NAV_LIST, value) }

    /** 各项目字号档位：0=小 1=中 2=大 */
    var overlaySizeTpms: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_TPMS, 1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_TPMS, value) }

    var overlaySizeVolts: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_VOLTS, 1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_VOLTS, value) }

    var overlaySizeAmps: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_AMPS, 1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_AMPS, value) }

    var overlaySizePower: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_POWER, 1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_POWER, value) }

    var overlaySizeDiffLive: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_DIFF_LIVE, 1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_DIFF_LIVE, value) }

    var overlaySizeDiffMax: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_DIFF_MAX, 1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_DIFF_MAX, value) }

    var overlaySizeBattery: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_BATTERY, 1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_BATTERY, value) }

    var overlaySizeMosTemp: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_MOS_TEMP, 1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_MOS_TEMP, value) }

    var overlaySizeMaxSpeed: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_MAX_SPEED, 1)
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_MAX_SPEED, value) }

    var overlaySizeTpmsFront: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_TPMS_FRONT, sp.getInt("overlay_size_tpms", 1))
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_TPMS_FRONT, value) }

    var overlaySizeTpmsRear: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_TPMS_REAR, sp.getInt("overlay_size_tpms", 1))
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_TPMS_REAR, value) }

    var overlaySizeAmpsLive: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_AMPS_LIVE, sp.getInt("overlay_size_amps", 1))
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_AMPS_LIVE, value) }

    var overlaySizePowerLive: Int
        get() = sp.getInt(KEY_OVERLAY_SIZE_POWER_LIVE, sp.getInt("overlay_size_power", 1))
        set(value) = sp.edit { putInt(KEY_OVERLAY_SIZE_POWER_LIVE, value) }


    val hasSavedDevice: Boolean get() = !lastDeviceMac.isNullOrBlank()

    /** 仅在连接成功时调用 */
    fun saveConnectedDevice(mac: String, name: String?) {
        sp.edit {
            putString(KEY_LAST_MAC, mac)
            putString(KEY_LAST_NAME, name ?: "")
        }
    }

    /** 设置页"清除已保存设备" */
    fun clearSavedDevice() {
        sp.edit {
            remove(KEY_LAST_MAC)
            remove(KEY_LAST_NAME)
        }
    }

    companion object {
        private const val KEY_LAST_MAC = "last_device_mac"
        private const val KEY_LAST_NAME = "last_device_name"
        private const val KEY_AUTO_CONNECT = "auto_connect_enabled"
        private const val KEY_VIBRATE = "vibrate_enabled"
        private const val KEY_ALERT_DIALOG = "alert_dialog_enabled"
        private const val KEY_TPMS_FRONT_SN = "tpms_front_sn"
        private const val KEY_TPMS_REAR_SN = "tpms_rear_sn"
        private const val KEY_TPMS_LOW_BAR = "tpms_low_bar"
        private const val KEY_TPMS_HIGH_BAR = "tpms_high_bar"
        private const val KEY_TPMS_HIGH_TEMP = "tpms_high_temp"
        private const val KEY_TPMS_BATTERY_MODEL = "tpms_battery_model"
        private const val KEY_OVERLAY_TPMS = "overlay_show_tpms"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_OVERLAY_VOLTS = "overlay_show_volts"
        private const val KEY_OVERLAY_AMPS = "overlay_show_amps"
        private const val KEY_OVERLAY_POWER = "overlay_show_power"
        private const val KEY_OVERLAY_DIFF_LIVE = "overlay_show_diff_live"
        private const val KEY_OVERLAY_DIFF_MAX = "overlay_show_diff_max"
        private const val KEY_OVERLAY_BATTERY = "overlay_show_battery"
        private const val KEY_OVERLAY_MOS_TEMP = "overlay_show_mos_temp"
        private const val KEY_OVERLAY_MAX_SPEED = "overlay_show_max_speed"
        private const val KEY_OVERLAY_TPMS_FRONT = "overlay_show_tpms_front"
        private const val KEY_OVERLAY_TPMS_REAR = "overlay_show_tpms_rear"
        private const val KEY_OVERLAY_AMPS_LIVE = "overlay_show_amps_live"
        private const val KEY_OVERLAY_POWER_LIVE = "overlay_show_power_live"
        private const val KEY_OVERLAY_BG_ALPHA = "overlay_bg_alpha"
        private const val KEY_OVERLAY_ORDER = "overlay_order"
        private const val KEY_NAV_APP_PKG = "nav_app_pkg"
        private const val KEY_QUICK_NAV_LIST = "quick_nav_list"
        private const val KEY_OVERLAY_SIZE_TPMS = "overlay_size_tpms"
        private const val KEY_OVERLAY_SIZE_VOLTS = "overlay_size_volts"
        private const val KEY_OVERLAY_SIZE_AMPS = "overlay_size_amps"
        private const val KEY_OVERLAY_SIZE_POWER = "overlay_size_power"
        private const val KEY_OVERLAY_SIZE_DIFF_LIVE = "overlay_size_diff_live"
        private const val KEY_OVERLAY_SIZE_DIFF_MAX = "overlay_size_diff_max"
        private const val KEY_OVERLAY_SIZE_BATTERY = "overlay_size_battery"
        private const val KEY_OVERLAY_SIZE_MOS_TEMP = "overlay_size_mos_temp"
        private const val KEY_OVERLAY_SIZE_MAX_SPEED = "overlay_size_max_speed"
        private const val KEY_OVERLAY_SIZE_TPMS_FRONT = "overlay_size_tpms_front"
        private const val KEY_OVERLAY_SIZE_TPMS_REAR = "overlay_size_tpms_rear"
        private const val KEY_OVERLAY_SIZE_AMPS_LIVE = "overlay_size_amps_live"
        private const val KEY_OVERLAY_SIZE_POWER_LIVE = "overlay_size_power_live"
    }
}
package io.github.supit.rideassist.data.prefs

import android.content.Context
import androidx.core.content.edit
import io.github.supit.rideassist.util.RecordThreshold

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

    /** ⭐ 温度探头自定义名（key: mos/t1/t2/t3；null 或空 = 恢复默认名） */
    fun tempLabel(key: String): String? =
        sp.getString("temp_label_$key", null)?.takeIf { it.isNotBlank() }

    fun setTempLabel(key: String, value: String) =
        sp.edit().putString("temp_label_$key", value).apply()

    /** ⭐ 应用内更新：用户选择「跳过此版本」时记录的版本号（同版本不再提醒） */
    var updateSkippedVersion: String?
        get() = sp.getString("update_skipped_version", null)
        set(value) = sp.edit().putString("update_skipped_version", value).apply()

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

    /**
     * ⭐ 悬浮字体颜色（ARGB）。
     * **0 = 自动**：沿用按实际时间（18:00~06:00 夜白 / 其余黑）的旧口径。
     * 非 0 = 用户选中的纯色，覆盖自动主题；告警红仍由渲染层单独覆盖。
     */
    var overlayTextColor: Int
        get() = sp.getInt("overlay_text_color", 0)
        set(value) = sp.edit { putInt("overlay_text_color", value) }

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

    // ==================== 连接后的自动跳转 ====================

    /**
     * ⭐ **连接保护板后切到哪个标签页**（用户 2026-11-09 / 同日简化）。
     *
     * 默认 [TAB_HOME]（主页）。选项只有 4 个底部标签页 ——
     * 「首页」本身就等于不跳转，所以不再单列「不跳转」。
     * 只在「未连接 → 已连接」的上升沿生效一次，回前台 / 页面重建不会重复跳。
     *
     * ⚠️ 读写都做白名单归一：v1.0.36 曾写入过 `"none"`（旧「不跳转」），
     *    老用户升级后这个遗留值必须按「首页」处理，不能让下拉框落空、
     *    也不能把它当成未知值悄悄关掉跳转。
     */
    var connectJumpTab: String
        get() {
            val saved = sp.getString(KEY_CONNECT_JUMP_TAB, TAB_HOME) ?: TAB_HOME
            return if (saved in VALID_JUMP_TABS) saved else TAB_HOME
        }
        set(value) = sp.edit {
            putString(KEY_CONNECT_JUMP_TAB, if (value in VALID_JUMP_TABS) value else TAB_HOME)
        }

    // ==================== 实时曲线默认显示 ====================

    /**
     * ⭐ **进入实时页时曲线是否默认显示**（用户 2026-11-09）。
     *
     * `false`（默认）= 不显示曲线，保持 2026-11-08「打开 APP 默认不显示曲线」
     * 的既有体验；`true` = 进入实时页直接是 `LIVE`（实时曲线）。
     *
     * 只决定**进入时的初始状态**：用户仍可双击在
     * 不显示 → 实时 → 历史 三态间自由切换，切换结果在本页停留期间保留；
     * 下次进入实时页再按本偏好复位。
     *
     * ⚠️ 聚焦模式仍按既有规则强制隐藏曲线（`onCardFocused` 里 setCurveMode(OFF)），
     *    本偏好只作用于实时页主布局，不改聚焦态行为。
     */
    var realtimeCurveShowByDefault: Boolean
        get() = sp.getBoolean(KEY_REALTIME_CURVE_SHOW, false)
        set(value) = sp.edit { putBoolean(KEY_REALTIME_CURVE_SHOW, value) }

    // ==================== 记录阈值（连接设置页两项数字输入） ====================

    /**
     * ⭐ **最大压差记录阈值（mV）**（用户 2026-11-10）。
     *
     * 只影响「最大压差单体记录」历史卡：行程压差**严格大于**本值才记一条。
     * 默认 50，留空 / 非法 / 负数由 [RecordThreshold] 归一回 50。
     * ⚠️ 会话峰值记录卡不受影响，小压差照常显示。
     */
    var diffRecordThresholdMv: Float
        get() = RecordThreshold.normalize(
            sp.getFloat(KEY_DIFF_RECORD_THRESHOLD, RecordThreshold.DEFAULT)
        )
        set(value) = sp.edit {
            putFloat(KEY_DIFF_RECORD_THRESHOLD, RecordThreshold.normalize(value))
        }

    /**
     * ⭐ **极速记录阈值（Km/h）**（用户 2026-11-10）。
     *
     * 只影响「极速记录」历史卡：行程速度**严格大于**本值才记一条。
     * 默认 50，留空 / 非法 / 负数同样归一回 50。
     */
    var topSpeedRecordThresholdKmh: Float
        get() = RecordThreshold.normalize(
            sp.getFloat(KEY_TOP_SPEED_RECORD_THRESHOLD, RecordThreshold.DEFAULT)
        )
        set(value) = sp.edit {
            putFloat(KEY_TOP_SPEED_RECORD_THRESHOLD, RecordThreshold.normalize(value))
        }

    /**
     * ⭐ **电流记录阈值（A）**（用户 2026-11-10）。
     *
     * 只影响「电流记录」历史卡：行程电流**严格大于**本值才记一条。
     * 默认 50A，留空 / 非法 / 负数同样归一回 50。
     */
    var currentRecordThresholdA: Float
        get() = RecordThreshold.normalize(
            sp.getFloat(KEY_CURRENT_RECORD_THRESHOLD, RecordThreshold.DEFAULT)
        )
        set(value) = sp.edit {
            putFloat(KEY_CURRENT_RECORD_THRESHOLD, RecordThreshold.normalize(value))
        }

    /**
     * ⭐ **功率记录阈值（W）**（用户 2026-11-10）。
     *
     * 只影响「功率记录」历史卡：行程功率**严格大于**本值才记一条。
     * ⚠️ 默认 [RecordThreshold.DEFAULT_POWER] = 1000W，不是 50 —— 与压差 / 极速
     *    不是一个量级，50W 会让每一帧都超过阈值，历史卡失去意义。
     */
    var powerRecordThresholdW: Float
        get() = RecordThreshold.normalize(
            sp.getFloat(KEY_POWER_RECORD_THRESHOLD, RecordThreshold.DEFAULT_POWER),
            RecordThreshold.DEFAULT_POWER,
        )
        set(value) = sp.edit {
            putFloat(
                KEY_POWER_RECORD_THRESHOLD,
                RecordThreshold.normalize(value, RecordThreshold.DEFAULT_POWER),
            )
        }

    /** ⭐ 悬浮「空格占位」开关：开启后悬浮框显示一条空白行 */
    var overlayShowSpace: Boolean
        get() = sp.getBoolean("overlay_show_space", false)
        set(value) = sp.edit { putBoolean("overlay_show_space", value) }

    /** ⭐ 空格占位行高度档位（0=小 1=中 2=大），复用字号按钮 */
    var overlaySizeSpace: Int
        get() = sp.getInt("overlay_size_space", 1)
        set(value) = sp.edit { putInt("overlay_size_space", value) }

    /**
     * ⭐ 追加的空格占位（编号 2 起）：开关与高度档位按编号派生 key。
     * 不为每个占位手抄一对字段 —— 注册表里加一条即可，不会再漏字段。
     * **默认不启用**（false）；字号默认「中」。
     */
    fun extraSpaceShow(n: Int): Boolean = sp.getBoolean("overlay_show_space_$n", false)
    fun setExtraSpaceShow(n: Int, v: Boolean) = sp.edit { putBoolean("overlay_show_space_$n", v) }
    fun extraSpaceSize(n: Int): Int = sp.getInt("overlay_size_space_$n", 1)
    fun setExtraSpaceSize(n: Int, v: Int) = sp.edit { putInt("overlay_size_space_$n", v) }

    /**
     * 快捷导航列表（JSON 数组 `[{"value":"...","label":"..."}]`，支持多个）。
     *
     * ⭐ **首次使用时预置一个「坐标获取」按钮**（高德地图坐标拾取工具）。
     * - 预置只在**这个 key 从未被写过**时发生（raw == null）；
     * - 用户把预置项删掉后 key 依然存在（值为 "[]"），**不会**再自动加回来 ——
     *   「默认存在但可以删除」两条都满足；
     * - 清空 APP 数据后重新预置（相当于新装）。
     *
     * ⚠️ 只认 `{value,label}` 一种格式（用户 2026-11-10：不做任何旧格式兼容）。
     *    历史数据早已由 `tools/migrate_quick_nav.py` 一次性迁成新格式，
     *    这里不再追加/改写旧条目，也不再读写 `dest` / `uri` / `name` 字段。
     */
    var quickNavList: String
        get() {
            val raw = sp.getString(KEY_QUICK_NAV_LIST, null)
            // 全新安装：直接就是预置的那一个
            if (raw == null) {
                // value=完整链接（adapter 认 "://" 判定为直达链接），label=按钮显示名
                val preset = """[{"value":"$COORD_PICKER_URL","label":"$QUICK_NAV_COORD_NAME"}]"""
                sp.edit { putString(KEY_QUICK_NAV_LIST, preset) }
                return preset
            }
            return raw
        }
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
        // 连接后跳转的目标标签页（与底部导航四个 id 一一对应）
        const val TAB_HOME = "home"
        const val TAB_REALTIME = "realtime"
        const val TAB_DATA = "data"
        const val TAB_OVERLAY = "overlay"

        /** 合法值白名单；v1.0.36 的遗留值 "none"（旧「不跳转」）会被归一成首页 */
        private val VALID_JUMP_TABS = setOf(TAB_HOME, TAB_REALTIME, TAB_DATA, TAB_OVERLAY)

        private const val KEY_CONNECT_JUMP_TAB = "connect_jump_tab"
        private const val KEY_REALTIME_CURVE_SHOW = "realtime_curve_show"
        private const val KEY_DIFF_RECORD_THRESHOLD = "diff_record_threshold_mv"
        private const val KEY_TOP_SPEED_RECORD_THRESHOLD = "top_speed_record_threshold_kmh"
        private const val KEY_CURRENT_RECORD_THRESHOLD = "current_record_threshold_a"
        private const val KEY_POWER_RECORD_THRESHOLD = "power_record_threshold_w"
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

        /** 高德地图坐标拾取工具（快捷导航预置项的链接） */
        const val COORD_PICKER_URL = "https://lbs.amap.com/tools/picker"

        /** 预置按钮的名字 */
        const val QUICK_NAV_COORD_NAME = "坐标获取"

        /** 快捷导航列表的 SP key（只存 `{value,label}` 一种格式） */
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
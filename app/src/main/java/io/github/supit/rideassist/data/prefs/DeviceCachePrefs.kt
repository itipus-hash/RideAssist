package io.github.supit.rideassist.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * ⭐⭐ **设备缓存专用存储 —— 与用户设置物理隔离。**
 *
 * ## 为什么要单独一个 SharedPreferences 文件
 * 保护板阈值 / 设备信息是「设备侧的事实」，用户设置（悬浮开关、温度名、
 * 字体颜色…）是「用户的偏好」。它们生命周期完全不同：
 * - 用户点「清空本次会话」→ 只该清会话内存，**绝不该把设备缓存一起清掉**
 * - 用户重置设置 / 将来某个清理入口调 `AppPrefs.edit().clear()` →
 *   也不该波及设备缓存
 *
 * 放进同一个 `yybms_prefs.xml` 只是"少写一个文件"，但代价是任何一次
 * `clear()` 都会把阈值缓存删掉 —— 那样「连过一次就有缓存」就白做了。
 * 所以这里用独立的 `device_cache` 文件，从存储层就断开。
 *
 * 注意：这里**没有任何 clear() 的公开入口**。换板子时由
 * [DeviceProfileStore.syncOrInvalidate] 精确清掉旧阈值，而不是整体清空。
 */
class DeviceCachePrefs(context: Context) {

    private val sp = context.getSharedPreferences("device_cache", Context.MODE_PRIVATE)

    /** 保护板阈值快照（`ProtectThresholds.encode()` 的原始串） */
    var protectParamsRaw: String
        get() = sp.getString("protect_params", "") ?: ""
        set(value) = sp.edit { putString("protect_params", value) }

    /** 设备身份 + 电池信息快照（`DeviceProfile.encode()` 的原始串） */
    var deviceProfileRaw: String
        get() = sp.getString("device_profile", "") ?: ""
        set(value) = sp.edit { putString("device_profile", value) }

    /**
     * 电池实况快照（`BatterySnapshot.encode()`）。
     *
     * 断开保护板后诊断面板的「电池实况」用它兜底 —— 用户要求
     * 「断开 BMS 电池信息不能丢」，且必须**跨 APP 重启**有效。
     */
    var batterySnapshotRaw: String
        get() = sp.getString("battery_snapshot", "") ?: ""
        set(value) = sp.edit { putString("battery_snapshot", value) }
}

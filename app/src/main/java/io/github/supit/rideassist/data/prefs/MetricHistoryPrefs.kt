package io.github.supit.rideassist.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * ⭐ **电流 / 功率历史的独立落盘**（用户 2026-11-10）。
 *
 * 与 [DiffHistoryPrefs] / [TopSpeedHistoryPrefs] 同口径：跨 APP 重启保留，
 * 独立 SP 文件，避免被任何「重置设置 / clear()」顺手清掉。
 *
 * 因为两者结构完全一样（`tripId|value|date|clock`），这里用一个类 + 文件名参数，
 * 不再复制两份代码：
 * - 电流 → `current_history`
 * - 功率 → `power_history`
 *
 * 编解码在 [io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.encodeCurrentHistory]
 * / `encodePowerHistory`，Android 层只负责存一个字符串。
 */
class MetricHistoryPrefs(context: Context, fileName: String) {

    private val sp = context.applicationContext
        .getSharedPreferences(fileName, Context.MODE_PRIVATE)

    /** 编码后的历史行；空串 = 没有记录 */
    var raw: String
        get() = sp.getString(KEY_ROWS, "") ?: ""
        set(value) = sp.edit { putString(KEY_ROWS, value) }

    companion object {
        private const val KEY_ROWS = "rows"

        /** 电流历史文件名 */
        const val FILE_CURRENT = "current_history"

        /** 功率历史文件名 */
        const val FILE_POWER = "power_history"
    }
}

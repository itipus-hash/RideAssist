package io.github.supit.rideassist.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * ⭐⭐ **极速历史专用存储**（用户 2026-11-10：与最大压差单体记录同口径，
 * 重开 APP 也要一直能显示）。
 *
 * ## 为什么单独一个 SharedPreferences 文件
 * 与 [DiffHistoryPrefs] 完全同理：
 * - 这是「跨 APP 重启的事实记录」，不属于用户设置，也不属于会话内存；
 * - 独立文件 `top_speed_history` 避开将来任何「重置设置 / clear()」顺手清掉；
 * - 会话重置只清内存，历史按用户要求跨进程保留（用户显式清空会话时，
 *   由 SessionSpeedTracker 的变更回调把这里同步写成空串）。
 *
 * 格式由 [io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.encodeTopSpeedHistory]
 * 负责 —— 纯 Kotlin 编解码，便于 JVM 单测，Android 层只负责存一个字符串。
 */
class TopSpeedHistoryPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("top_speed_history", Context.MODE_PRIVATE)

    /** 编码后的历史行；空串 = 没有记录 */
    var raw: String
        get() = sp.getString(KEY_ROWS, "") ?: ""
        set(value) = sp.edit { putString(KEY_ROWS, value) }

    companion object {
        private const val KEY_ROWS = "rows"
    }
}

package io.github.supit.rideassist.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * ⭐⭐ **最大压差单体历史专用存储**（用户 2026-11-09：重开 APP 也要一直能显示）。
 *
 * ## 为什么单独一个 SharedPreferences 文件
 * 这段历史是「跨 APP 重启的事实记录」，而 `yybms_prefs` 里全是用户设置，
 * 会话内存里的曲线 / 峰值又遵守「退出 APP = 全新会话」。
 * 放独立文件 `diff_history` 可以同时避开两侧的风险：
 * - 将来任何「重置设置 / clear()」不会顺手把历史清掉；
 * - 会话重置只清内存，历史仍按用户要求跨进程保留（除非用户显式清空会话，
 *   那时由 SessionSpeedTracker 的变更回调把这里同步写成空串）。
 *
 * 格式由 [io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.encodeDiffHistory]
 * 负责 —— 纯 Kotlin 编解码，便于 JVM 单测，Android 层只负责存一个字符串。
 */
class DiffHistoryPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("diff_history", Context.MODE_PRIVATE)

    /** 编码后的历史行；空串 = 没有记录 */
    var raw: String
        get() = sp.getString(KEY_ROWS, "") ?: ""
        set(value) = sp.edit { putString(KEY_ROWS, value) }

    companion object {
        private const val KEY_ROWS = "rows"
    }
}

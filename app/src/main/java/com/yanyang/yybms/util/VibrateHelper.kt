package com.yanyang.yybms.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 告警震动提醒。可在设置页关闭。
 */
object VibrateHelper {

    private const val DURATION_MS = 400L

    @Suppress("DEPRECATION")
    fun vibrate(context: Context, enabled: Boolean) {
        if (!enabled) return
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createOneShot(DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                v.vibrate(DURATION_MS)
            }
        } catch (e: Exception) {
            // 部分机型需要通知渠道权限，失败静默
        }
    }
}
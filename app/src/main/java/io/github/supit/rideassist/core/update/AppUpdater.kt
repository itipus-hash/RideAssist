package io.github.supit.rideassist.core.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * 用系统 DownloadManager 下载更新 APK。
 *
 * 下载完成后由 [UpdateApkReceiver]（Manifest 静态 receiver）自动弹出安装——
 * 用静态 receiver 是因为用户点完「立即更新」很可能切走 APP（进程被杀），
 * 动态注册的 receiver 会随进程一起消失，下载完就「没反应」。
 */
object AppUpdater {
    const val PREF_DOWNLOAD_ID = "update_download_id"
    const val PREF_APK_FILE = "update_apk_file"

    fun startDownload(context: Context, info: UpdateChecker.UpdateInfo) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // 清掉上一次的下载残留文件
        val old = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), info.apkFile)
        if (old.exists()) old.delete()

        val sp = context.getSharedPreferences("yybms_prefs", Context.MODE_PRIVATE)
        var lastEx: Exception? = null
        for (url in info.downloadUrls()) {
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                    .setTitle("RideAssist ${info.version}")
                    .setDescription("正在下载更新…")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, info.apkFile)
                    .setAllowedOverMetered(true)
                val id = dm.enqueue(req)
                sp.edit().putLong(PREF_DOWNLOAD_ID, id).putString(PREF_APK_FILE, info.apkFile).apply()
                return
            } catch (e: Exception) {
                lastEx = e
            }
        }
        throw lastEx ?: IllegalStateException("download failed")
    }
}

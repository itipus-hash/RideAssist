package io.github.supit.rideassist.core.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

/**
 * 用系统 DownloadManager 下载更新 APK 并触发安装。
 *
 * 流程：enqueue（系统通知栏自带进度）→ ACTION_DOWNLOAD_COMPLETE →
 * FileProvider 暴露文件 → ACTION_INSTALL_PACKAGE 弹系统安装确认。
 *
 * ⚠️ 同签名 APK 才能覆盖安装：debug 构建与仓库里的 debug 包签名一致。
 */
object AppUpdater {
    private const val DOWNLOAD_ID_KEY = "update_download_id"

    fun startDownload(context: Context, info: UpdateChecker.UpdateInfo) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // 清掉上一次的下载记录文件
        val old = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), info.apkFile)
        if (old.exists()) old.delete()

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
                context.getSharedPreferences("yybms_prefs", Context.MODE_PRIVATE)
                    .edit().putLong(DOWNLOAD_ID_KEY, id).apply()
                // 只监听本次下载完成
                context.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                        try { c.unregisterReceiver(this) } catch (_: Exception) {}
                        install(c, info)
                    }
                }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0)
                return
            } catch (e: Exception) {
                lastEx = e
            }
        }
        throw lastEx ?: IllegalStateException("download failed")
    }

    private fun install(context: Context, info: UpdateChecker.UpdateInfo) {
        val f = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), info.apkFile)
        if (!f.exists() || f.length() < 1024) return   // 未下完/失败，静默放弃
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }
}

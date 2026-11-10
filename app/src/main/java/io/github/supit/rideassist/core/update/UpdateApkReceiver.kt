package io.github.supit.rideassist.core.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import io.github.supit.rideassist.ui.widget.Feedback

/**
 * ⭐ APK 下载完成 → 自动弹出系统安装（Manifest 静态 receiver，进程被杀也能触发）。
 */
class UpdateApkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val sp = context.getSharedPreferences("yybms_prefs", Context.MODE_PRIVATE)
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (sp.getLong(AppUpdater.PREF_DOWNLOAD_ID, -1) != id) return   // 只响应本应用的更新下载
        if (!sp.contains(AppUpdater.PREF_APK_FILE)) return

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // ⭐ DownloadManager 没有公开的 getStatusForId，用 query cursor 查
        val status: Int = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            else -1
        } ?: -1
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            Feedback.show(context, "更新下载失败，请重试", long = true)
            return
        }
        installApk(context, sp.getString(AppUpdater.PREF_APK_FILE, null) ?: return)
    }

    companion object {
        fun installApk(context: Context, apkFile: String) {
            // ⭐ 「安装未知应用」权限未开 → 跳系统开关页（开完回来重新点立即更新）
            if (Build.VERSION.SDK_INT >= 26 &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                Feedback.show(context, "请先允许「安装未知应用」权限", long = true)
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            val f = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkFile)
            if (!f.exists() || f.length() < 1024) return   // 未下完/失败，静默放弃
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", f)
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}

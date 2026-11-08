package io.github.supit.rideassist.core.update

import android.util.Log
import io.github.supit.rideassist.data.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新检查。
 *
 * 版本信息源 = 仓库 apk/update.json（deploy.py 每次 publish 自动更新）：
 * {"version":"1.0.1","notes":"...","apk":"RideAssist-v1.0.1-debug.apk"}
 *
 * 下载源按顺序尝试：raw 直连 → ghfast.top 镜像（国内直连 raw 经常被墙）。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val OWNER = "itipus-hash"
    private const val REPO = "RideAssist"
    private const val BRANCH = "master"

    private const val PREF_SKIPPED = "update_skipped_version"

    data class HistoryEntry(val version: String, val notes: String)

    data class UpdateInfo(
        val version: String,   // 最新版本号，如 "1.0.1"
        val notes: String,     // 更新信息
        val apkFile: String,   // apk/ 目录下的文件名
        val history: List<HistoryEntry> = emptyList(),   // 各历史版本更新内容（跨版本累积显示用）
    ) {
        fun downloadUrls(): List<String> = listOf(
            "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/apk/$apkFile",
            "https://ghfast.top/https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/apk/$apkFile",
        )

        /**
         * ⭐ **下载页面**（不是直链）：GitHub 上这个 APK 的文件页，能看版本、
         * 能点 Download。更新弹窗最下面的「复制并打开下载页面」用这个地址——
         * APP 内 DownloadManager 下载失败时，用户可把链接粘到浏览器 / 电脑 /
         * 下载器里自己下。地址只在这里构造一份，别在 UI 层拼字符串。
         */
        fun downloadPageUrl(): String =
            "https://github.com/$OWNER/$REPO/blob/$BRANCH/apk/$apkFile"
    }

    /** 当前版本是否落后于远端 */
    fun isOutdated(current: String, latest: String): Boolean = compareVersions(latest, current) > 0

    /** 用户是否已跳过该版本（跳过后同版本不再提醒） */
    fun isSkipped(prefs: AppPrefs, latest: String): Boolean =
        prefs.updateSkippedVersion == latest

    fun markSkipped(prefs: AppPrefs, latest: String) {
        prefs.updateSkippedVersion = latest
    }

    /** 拉取 update.json；失败抛异常（调用方吞掉，不影响正常使用） */
    suspend fun fetch(): UpdateInfo = withContext(Dispatchers.IO) {
        val urls = listOf(
            "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/apk/update.json",
            "https://ghfast.top/https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/apk/update.json",
        )
        var lastErr: Exception? = null
        for (u in urls) {
            try {
                Log.d(TAG, "fetch try: $u")
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/json")
                Log.d(TAG, "fetch http ${conn.responseCode}")
                if (conn.responseCode != 200) {
                    lastErr = IllegalStateException("HTTP ${conn.responseCode}")
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(body)
                val history = mutableListOf<HistoryEntry>()
                val arr = o.optJSONArray("history")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val h = arr.getJSONObject(i)
                        history.add(HistoryEntry(h.getString("version"), h.optString("notes", "")))
                    }
                }
                Log.d(TAG, "fetch ok: v${o.getString("version")} history=${history.size}")
                return@withContext UpdateInfo(
                    version = o.getString("version"),
                    notes = o.optString("notes", ""),
                    apkFile = o.getString("apk"),
                    history = history,
                )
            } catch (e: Exception) {
                Log.d(TAG, "fetch failed: $e")
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("no url")
    }

    /** 版本号分段比较：1.0.10 > 1.0.9 */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}

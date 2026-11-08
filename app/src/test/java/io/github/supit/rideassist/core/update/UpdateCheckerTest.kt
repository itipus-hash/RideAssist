package io.github.supit.rideassist.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ 版本号是「应用内更新推送」的**唯一开关**：
 * `MonitorFragment` 拿本机 versionName 与远端 update.json 的 version 做比较，
 * 只有 `isOutdated(current, latest)` 为真才弹窗。
 * 所以每次交付必须同时 bump versionName 并写高 update.json 的 version，
 * 少一边就永远推不出去。这里锁住比较口径，避免字符串比较把 1.0.10 判成旧版。
 */
class UpdateCheckerTest {

    @Test fun `本次升级 1_0_4 到 1_0_5 必须判为落后`() {
        assertTrue(UpdateChecker.isOutdated("1.0.4", "1.0.5"))
    }

    @Test fun `同版本不弹窗 新版本也不倒退`() {
        assertFalse(UpdateChecker.isOutdated("1.0.5", "1.0.5"))
        assertFalse(UpdateChecker.isOutdated("1.0.5", "1.0.4"))
    }

    @Test fun `分段按数值比较而不是字符串字典序`() {
        assertTrue(UpdateChecker.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.0.99") > 0)
    }

    @Test fun `缺位与非法段按 0 处理且不抛异常`() {
        assertTrue(UpdateChecker.compareVersions("1.0.5", "1.0") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.x", "1.0.0") == 0)
    }

    @Test fun `下载页面是 GitHub 文件页且不是直链`() {
        val info = UpdateChecker.UpdateInfo("1.0.6", "n", "RideAssist-v1.0.6-debug.apk")
        assertEquals(
            "https://github.com/itipus-hash/RideAssist/blob/master/apk/" +
                "RideAssist-v1.0.6-debug.apk",
            info.downloadPageUrl()
        )
        // 复制的是「页面」，不是 raw 文件直链（直链由 downloadUrls() 单独给下载器用）
        assertTrue(!info.downloadPageUrl().startsWith("https://raw.githubusercontent.com/"))
    }

    @Test fun `下载页面随 APK 文件名变化 不硬编码版本`() {
        val info = UpdateChecker.UpdateInfo("1.0.7", "n", "RideAssist-v1.0.7-debug.apk")
        assertTrue(info.downloadPageUrl().endsWith("/apk/RideAssist-v1.0.7-debug.apk"))
    }
}

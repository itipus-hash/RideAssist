package io.github.supit.rideassist.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ 用户反馈「跨多版本更新提示会很长」。
 *
 * 旧实现把所有比本机新的版本段全列出来：从 1.0.1 直接升到 1.0.10 时
 * 正文 52 条说明 + 9 个版本标题 ≈ 69 行，弹窗直接把屏幕撑爆。
 *
 * 现在的规则：只展开最新版本，旧版本压成一行摘要。
 */
class UpdateNotesTest {

    private fun info(
        vararg history: Pair<String, String>,
        notes: String = "",
    ) = UpdateChecker.UpdateInfo(
        version = history.lastOrNull()?.first ?: "1.0.10",
        notes = notes,
        apkFile = "RideAssist-debug.apk",
        history = history.map { UpdateChecker.HistoryEntry(it.first, it.second) },
    )

    @Test fun `只跨一个版本时不出现多版本摘要`() {
        val msg = UpdateNotes.build(
            info(
                "1.0.9" to "9 号说明",
                "1.0.10" to "1. 修复 A\n2. 修复 B",
            ),
            currentVersion = "1.0.9",
        )
        assertTrue(msg, msg.contains("1. 修复 A"))
        assertTrue(msg, msg.contains("2. 修复 B"))
        assertFalse(msg, msg.contains("另含"))
        assertTrue(msg, msg.endsWith("当前版本 v1.0.9"))
    }

    @Test fun `跨多版本只展开最新版 旧版说明不进正文`() {
        val msg = UpdateNotes.build(
            info(
                "1.0.4" to "旧版本才有的说明",
                "1.0.9" to "9 号的说明",
                "1.0.10" to "最新版说明",
            ),
            currentVersion = "1.0.3",
        )
        assertTrue(msg, msg.contains("最新版说明"))
        assertFalse("旧版本说明不该再出现在正文里", msg.contains("旧版本才有的说明"))
        assertFalse(msg, msg.contains("9 号的说明"))
        assertTrue(msg, msg.contains("另含 v1.0.4 – v1.0.9 共 2 个版本的改动"))
    }

    @Test fun `只跨两个版本时摘要写单个版本号`() {
        val msg = UpdateNotes.build(
            info(
                "1.0.9" to "9 号的说明",
                "1.0.10" to "最新版说明",
            ),
            currentVersion = "1.0.8",
        )
        assertTrue(msg, msg.contains("另含 v1.0.9 共 1 个版本的改动"))
    }

    @Test fun `单个版本说明太长会折叠并注明剩余条数`() {
        val long = (1..15).joinToString("\n") { "$it. 说明 $it" }
        val msg = UpdateNotes.build(
            info("1.0.10" to long),
            currentVersion = "1.0.9",
        )
        assertTrue(msg, msg.contains("8. 说明 8"))
        assertFalse("第 9 条应被折叠", msg.contains("9. 说明 9"))
        assertTrue(msg, msg.contains("…另有 7 条未展开"))
    }

    @Test fun `history 为空时退回顶层 notes`() {
        val msg = UpdateNotes.build(
            info(notes = "顶层说明"),
            currentVersion = "1.0.9",
        )
        assertTrue(msg, msg.contains("顶层说明"))
        assertTrue(msg, msg.endsWith("当前版本 v1.0.9"))
    }

    @Test fun `说明全空时给出兜底文案`() {
        val msg = UpdateNotes.build(info(), currentVersion = "1.0.9")
        assertTrue(msg, msg.contains(UpdateNotes.FALLBACK))
    }

    @Test fun `正文行数始终受上限约束`() {
        val huge = (1..60).joinToString("\n") { "$it. 说明" }
        val msg = UpdateNotes.build(
            info(
                "1.0.2" to "旧说明",
                "1.0.10" to huge,
            ),
            currentVersion = "1.0.1",
        )
        val bodyLines = msg.lines().count { it.isNotBlank() }
        // 8 条正文 + 折叠提示 + 摘要 + 空行里的「当前版本」
        assertTrue("正文过长：$bodyLines 行\n$msg", bodyLines <= 12)
    }
}

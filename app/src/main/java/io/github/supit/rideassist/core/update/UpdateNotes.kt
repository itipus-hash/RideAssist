package io.github.supit.rideassist.core.update

/**
 * ⭐⭐ **更新弹窗正文的生成规则**（纯函数，可单测）。
 *
 * ## 要解决的问题
 * 旧实现在弹窗里**把所有比本机新的版本段全列出来**。实测：用户停在 1.0.1
 * 直接升到 1.0.10 时，正文是 52 条说明 + 9 个版本标题 ≈ **69 行**，
 * 而 `tv_update_msg` 是个没有滚动容器的裸 TextView —— 结果要么被屏幕截断，
 * 要么把三个按钮挤出去。用户问的「跨多版本更新提示会很长」就是这件事。
 *
 * ## 现在的规则
 * 1. **只展开最新版本**的更新说明（用户真正要看的是"这次升级给我修了什么"）
 * 2. 旧版本压成**一行摘要**：`另含 v1.0.2 – 1.0.9 共 8 个版本的改动`
 * 3. 单版本说明本身也有上限（[MAX_LINES] 条），超出折叠并注明还剩几条
 * 4. 末尾仍然写明「当前版本 vX」，让用户知道是从哪一版跨过来的
 *
 * 完整历史不丢：`apk/update.json` 里的 `history` 一直是全的，
 * 想看逐版本明细可以直接看那个文件。弹窗不承担"变更日志全文"的职责。
 */
object UpdateNotes {

    /**
     * 正文最多展开几条。
     *
     * 取 8 是配合弹窗高度算的：8 条约 180dp，加上标题、摘要行、
     * 「当前版本」和 4 个竖排按钮，仍在 780dp 高的屏幕上留有余量。
     */
    const val MAX_LINES = 8

    /** 说明为空时的兜底文案 */
    const val FALLBACK = "问题修复与体验优化"

    private val VERSION_ORDER = Comparator<UpdateChecker.HistoryEntry> { a, b ->
        UpdateChecker.compareVersions(a.version, b.version)
    }

    /**
     * @param info 远端 update.json 解析结果
     * @param currentVersion 本机当前版本（用于筛掉已装过的版本段）
     * @param maxLines 正文最多展开条数
     */
    fun build(
        info: UpdateChecker.UpdateInfo,
        currentVersion: String,
        maxLines: Int = MAX_LINES,
    ): String {
        // 比本机新的版本段，按版本从旧到新
        val newer = info.history
            .filter { UpdateChecker.compareVersions(it.version, currentVersion) > 0 }
            .sortedWith(VERSION_ORDER)

        val latest = newer.lastOrNull()
        val raw = latest?.notes?.takeIf { it.isNotBlank() }
            ?: info.notes.takeIf { it.isNotBlank() }
            ?: FALLBACK

        val lines = raw.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val shown = lines.take(maxLines.coerceAtLeast(1))
        val folded = lines.size - shown.size

        return buildString {
            append(shown.joinToString("\n"))
            if (folded > 0) append("\n…另有 $folded 条未展开")

            val intermediate = newer.dropLast(1)   // 除最新版之外的中间版本
            if (intermediate.isNotEmpty()) {
                val span = if (intermediate.size == 1) {
                    "v${intermediate.first().version}"
                } else {
                    "v${intermediate.first().version} – v${intermediate.last().version}"
                }
                append("\n\n另含 $span 共 ${intermediate.size} 个版本的改动")
            }

            append("\n\n当前版本 v$currentVersion")
        }
    }
}

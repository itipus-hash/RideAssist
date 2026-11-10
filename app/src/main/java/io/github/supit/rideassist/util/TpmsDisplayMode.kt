package io.github.supit.rideassist.util

/**
 * ⭐ **实时页面「胎压显示」策略**（用户 2026-11-10）。
 *
 * 设置项名称：「实时卡片胎压显示」，三个选项：
 * - [ALWAYS] —— 始终显示（默认，保持旧行为）；
 * - [NEVER] —— 始终不显示；
 * - [ABNORMAL] —— 胎压异常时才显示（低于下限或高于上限）。
 *
 * ## 为什么单独抽成纯 Kotlin 对象
 * 判定逻辑要在**实时页渲染**与**连接设置页下拉框**之间共用，
 * 而且要有 JVM 单测能覆盖（`AppPrefs` 依赖 Android `Context`，测不了）。
 * 这里不含任何 Android 依赖，`AppPrefs` 只负责存字符串。
 */
object TpmsDisplayMode {

    /** 始终显示 */
    const val ALWAYS = "always"

    /** 始终不显示 */
    const val NEVER = "never"

    /** 胎压异常时才显示 */
    const val ABNORMAL = "abnormal"

    /** 全部合法取值（顺序 = 下拉框顺序） */
    val ALL = listOf(ALWAYS, NEVER, ABNORMAL)

    /** 默认值：始终显示（保持 2026-11-10 之前的行为） */
    const val DEFAULT = ALWAYS

    /** 非法 / 未知值一律归一到 [DEFAULT] */
    fun normalize(raw: String?): String =
        if (raw != null && raw in ALL) raw else DEFAULT

    /**
     * 该模式下胎压是否应该显示。
     *
     * @param mode     当前模式（非法值按 [DEFAULT] 处理）
     * @param abnormal 任一前/后轮胎压越界即为 true
     */
    fun shouldShow(mode: String?, abnormal: Boolean): Boolean = when (normalize(mode)) {
        NEVER -> false
        ABNORMAL -> abnormal
        else -> true
    }
}

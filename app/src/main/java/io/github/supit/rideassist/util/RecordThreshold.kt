package io.github.supit.rideassist.util

/**
 * 「记录阈值」的**唯一口径**（用户 2026-11-10）。
 *
 * 连接设置页有两项可填数字：最大压差记录阈值（mV）、极速记录阈值（Km/h），
 * 默认都是 50。用户要求「没填写或者留空，填充默认数值」—— 归一逻辑只放这里，
 * 避免设置页、偏好存储、记录器、空态文案四处各写一份导致对不上。
 *
 * 纯 Kotlin、无 Android 依赖，便于 JVM 单测。
 */
object RecordThreshold {

    /** 留空 / 非法 / 负数时回填的默认值 */
    const val DEFAULT = 50f

    /**
     * 把输入框的原始文本归一成阈值。
     * - 空串、空白、非数字、负数、NaN / 无穷 → [DEFAULT]
     * - `0` 合法：表示「记录所有大于 0 的值」
     * - 允许小数（如 55.5）
     */
    fun parse(raw: String?): Float =
        normalize(raw?.trim()?.toFloatOrNull() ?: DEFAULT)

    /** 把任意 Float 归一：负数 / NaN / 无穷一律回到 [DEFAULT] */
    fun normalize(value: Float): Float = when {
        value.isNaN() -> DEFAULT
        value.isInfinite() -> DEFAULT
        value < 0f -> DEFAULT
        else -> value
    }

    /**
     * 显示用文本：整数不带多余的小数点（50.0 → "50"），小数原样（55.5 → "55.5"）。
     * 设置页回填空值、历史卡空态文案都用这一个格式。
     */
    fun label(value: Float): String {
        val v = normalize(value)
        return if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
    }
}

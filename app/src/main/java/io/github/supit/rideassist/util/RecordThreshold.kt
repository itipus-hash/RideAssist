package io.github.supit.rideassist.util

/**
 * 「记录阈值」的**唯一口径**（用户 2026-11-10）。
 *
 * 连接设置页有四项可填数字：
 * - 压差记录阈值（mV），默认 50
 * - 速度记录阈值（Km/h），默认 50
 * - 电流记录阈值（A），默认 50
 * - 功率记录阈值（W），默认 1000
 *
 * 用户要求「没填写或者留空，填充默认数值」—— 归一逻辑只放这里，
 * 避免设置页、偏好存储、记录器、空态文案四处各写一份导致对不上。
 *
 * ⚠️ 每项**默认值不同**（功率 1000W 与压差 50mV 不是一个量级），
 *    所以 [parse] / [normalize] / [label] 都带 `default` 参数，
 *    调用方必须传自己那一项的默认值。
 *
 * 纯 Kotlin、无 Android 依赖，便于 JVM 单测。
 */
object RecordThreshold {

    /** 压差（mV）/ 极速（Km/h）/ 电流（A）的默认阈值 */
    const val DEFAULT = 50f

    /** 功率（W）的默认阈值：1000W 在 48V 系统约 20A，属于明显大功率 */
    const val DEFAULT_POWER = 1000f

    /**
     * 把输入框的原始文本归一成阈值。
     * - 空串、空白、非数字、负数、NaN / 无穷 → [default]
     * - `0` 合法：表示「记录所有大于 0 的值」
     * - 允许小数（如 55.5）
     */
    fun parse(raw: String?, default: Float = DEFAULT): Float =
        normalize(raw?.trim()?.toFloatOrNull() ?: default, default)

    /** 把任意 Float 归一：负数 / NaN / 无穷一律回到 [default] */
    fun normalize(value: Float, default: Float = DEFAULT): Float = when {
        value.isNaN() -> default
        value.isInfinite() -> default
        value < 0f -> default
        else -> value
    }

    /**
     * 显示用文本：整数不带多余的小数点（50.0 → "50"），小数原样（55.5 → "55.5"）。
     * 设置页回填空值、历史卡空态文案都用这一个格式。
     */
    fun label(value: Float, default: Float = DEFAULT): String {
        val v = normalize(value, default)
        return if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
    }
}

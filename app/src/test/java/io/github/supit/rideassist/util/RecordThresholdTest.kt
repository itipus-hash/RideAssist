package io.github.supit.rideassist.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 两项「记录阈值」的归一化口径（用户 2026-11-10）。
 *
 * 用户原话：「没填写或者留空，填充默认数值」。
 * 这里的「没填写」不止空串 —— 空格、纯符号、负数、`NaN`、`∞` 都属于
 * 用户不可能有意输入的脏值，统一回落到默认值 50，避免出现
 * 「阈值变成 0 → 每条行程都记」或「阈值变成 NaN → 一条都不记」这类暗坑。
 */
class RecordThresholdTest {

    @Test
    fun `留空或空白回落到默认值`() {
        assertEquals(50f, RecordThreshold.parse(""), 0f)
        assertEquals(50f, RecordThreshold.parse("   "), 0f)
        assertEquals(50f, RecordThreshold.parse(null), 0f)
    }

    @Test
    fun `非法数字回落到默认值`() {
        assertEquals(50f, RecordThreshold.parse("abc"), 0f)
        assertEquals(50f, RecordThreshold.parse("5O"), 0f)   // 字母 O 冒充 0
        assertEquals(50f, RecordThreshold.parse("--5"), 0f)
        assertEquals(50f, RecordThreshold.parse("."), 0f)
    }

    @Test
    fun `负数回落到默认值`() {
        assertEquals(50f, RecordThreshold.parse("-1"), 0f)
        assertEquals(50f, RecordThreshold.parse("-0.5"), 0f)
    }

    @Test
    fun `0 与小数按输入值生效`() {
        assertEquals(0f, RecordThreshold.parse("0"), 0f)
        assertEquals(55.5f, RecordThreshold.parse("55.5"), 0f)
        // 前后空格是输入法常见残留，不应影响结果
        assertEquals(30f, RecordThreshold.parse(" 30 "), 0f)
    }

    @Test
    fun `NaN 与无穷回落到默认值`() {
        assertEquals(50f, RecordThreshold.normalize(Float.NaN), 0f)
        assertEquals(50f, RecordThreshold.normalize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(50f, RecordThreshold.normalize(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun `label 整数不带小数点 小数原样显示`() {
        assertEquals("50", RecordThreshold.label(50f))
        assertEquals("55.5", RecordThreshold.label(55.5f))
        assertEquals("0", RecordThreshold.label(0f))
        // 非法值先归一，再显示 → 用户看到的就是实际生效值
        assertEquals("50", RecordThreshold.label(Float.NaN))
        assertEquals("50", RecordThreshold.label(-3f))
    }

    @Test
    fun `功率项用 1000 作为默认值 不受 50 影响`() {
        assertEquals(1000f, RecordThreshold.parse("", RecordThreshold.DEFAULT_POWER), 0f)
        assertEquals(1000f, RecordThreshold.parse("abc", RecordThreshold.DEFAULT_POWER), 0f)
        assertEquals(1000f, RecordThreshold.parse("-5", RecordThreshold.DEFAULT_POWER), 0f)
        assertEquals(1200f, RecordThreshold.parse("1200", RecordThreshold.DEFAULT_POWER), 0f)
        assertEquals("1000", RecordThreshold.label(Float.NaN, RecordThreshold.DEFAULT_POWER))
        // 传了 default 就不会退回到 50
        assertEquals("1000", RecordThreshold.label(-1f, RecordThreshold.DEFAULT_POWER))
    }
}

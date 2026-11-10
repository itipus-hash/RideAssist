package io.github.supit.rideassist.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「实时卡片胎压显示」策略单测（用户 2026-11-10）。
 *
 * 三个选项：始终显示 / 始终不显示 / 胎压异常时显示。
 * 这段逻辑同时被实时页渲染与连接设置页下拉框共用，
 * 所以必须是**纯函数**（不碰 Android Context）才能测。
 */
class TpmsDisplayModeTest {

    @Test
    fun `始终显示 无论是否异常都显示`() {
        assertTrue(TpmsDisplayMode.shouldShow(TpmsDisplayMode.ALWAYS, false))
        assertTrue(TpmsDisplayMode.shouldShow(TpmsDisplayMode.ALWAYS, true))
    }

    @Test
    fun `始终不显示 无论是否异常都不显示`() {
        assertFalse(TpmsDisplayMode.shouldShow(TpmsDisplayMode.NEVER, false))
        assertFalse(TpmsDisplayMode.shouldShow(TpmsDisplayMode.NEVER, true))
    }

    @Test
    fun `异常时才显示 只在异常为真时显示`() {
        assertFalse(TpmsDisplayMode.shouldShow(TpmsDisplayMode.ABNORMAL, false))
        assertTrue(TpmsDisplayMode.shouldShow(TpmsDisplayMode.ABNORMAL, true))
    }

    @Test
    fun `非法或空值按始终显示处理`() {
        assertEquals(TpmsDisplayMode.ALWAYS, TpmsDisplayMode.normalize(null))
        assertEquals(TpmsDisplayMode.ALWAYS, TpmsDisplayMode.normalize(""))
        assertEquals(TpmsDisplayMode.ALWAYS, TpmsDisplayMode.normalize("garbage"))
        assertTrue(TpmsDisplayMode.shouldShow("garbage", false))
        assertTrue(TpmsDisplayMode.shouldShow(null as String?, false))
    }

    @Test
    fun `下拉框顺序固定为 始终显示 始终不显示 异常时显示`() {
        assertEquals(
            listOf(
                TpmsDisplayMode.ALWAYS,
                TpmsDisplayMode.NEVER,
                TpmsDisplayMode.ABNORMAL,
            ),
            TpmsDisplayMode.ALL,
        )
        assertEquals(TpmsDisplayMode.ALWAYS, TpmsDisplayMode.DEFAULT)
    }
}

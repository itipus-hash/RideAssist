package io.github.supit.rideassist.util

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * ⭐ 温度卡探头槽位合并判据（用户：数值一样只显示一个，不一样才显示两个）。
 *
 * 实机在「未连接保护板」时温度全 null，截图验证不了这条逻辑，
 * 所以判据抽到 [TempSlotDedup] 用纯 JVM 单测锁住。
 * 下标 0 固定是 MOS，按用户要求不参与去重。
 */
class TempSlotDedupTest {

    @Test fun `两路探头读数相同时只隐藏第二个`() {
        // [MOS, T1, T2]
        assertArrayEquals(
            booleanArrayOf(false, false, true),
            TempSlotDedup.hiddenIndices(listOf("30.0℃", "25.0℃", "25.0℃"))
        )
    }

    @Test fun `两路探头读数不同时两个都显示`() {
        assertArrayEquals(
            booleanArrayOf(false, false, false),
            TempSlotDedup.hiddenIndices(listOf("30.0℃", "25.0℃", "26.0℃"))
        )
    }

    @Test fun `MOS 不参与去重 即使与探头读数完全相同`() {
        assertArrayEquals(
            booleanArrayOf(false, false, false),
            TempSlotDedup.hiddenIndices(listOf("25.0℃", "25.0℃", "26.0℃"))
        )
    }

    @Test fun `三路探头里后两路相同只隐藏最后一个`() {
        assertArrayEquals(
            booleanArrayOf(false, false, false, true),
            TempSlotDedup.hiddenIndices(listOf("30.0℃", "22.0℃", "23.0℃", "23.0℃"))
        )
    }

    @Test fun `三路全相同只留第一个`() {
        assertArrayEquals(
            booleanArrayOf(false, false, true, true),
            TempSlotDedup.hiddenIndices(listOf("30.0℃", "24.0℃", "24.0℃", "24.0℃"))
        )
    }

    @Test fun `无读数不参与去重也不被隐藏`() {
        assertArrayEquals(
            booleanArrayOf(false, false, false, false),
            TempSlotDedup.hiddenIndices(listOf(null, null, null, null))
        )
        assertArrayEquals(
            booleanArrayOf(false, false, false, false),
            TempSlotDedup.hiddenIndices(listOf("30.0℃", null, "25.0℃", null))
        )
    }

    @Test fun `按显示值比较 0_1 度的差异不合并`() {
        assertArrayEquals(
            booleanArrayOf(false, false, false),
            TempSlotDedup.hiddenIndices(listOf("30.0℃", "25.0℃", "25.1℃"))
        )
    }

    @Test fun `只有 MOS 或空列表时全部显示不越界`() {
        assertArrayEquals(booleanArrayOf(), TempSlotDedup.hiddenIndices(emptyList()))
        assertArrayEquals(booleanArrayOf(false), TempSlotDedup.hiddenIndices(listOf("30.0℃")))
    }
}

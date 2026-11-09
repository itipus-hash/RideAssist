package io.github.supit.rideassist.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 钉死「快捷导航排序后长按编辑错按钮」的回归口径（用户 2026-11-10 实测）。
 *
 * 根因：拖动排序走 `notifyItemMoved`，ViewHolder 不重新 onBind，
 * 但 `bindingAdapterPosition` 已变；手势回调里用绑定时的 position 快照
 * 就会把旧下标交给编辑对话框。
 */
class QuickNavIndexTest {

    /** 与 QuickNavAdapter.onMove 一致的相邻交换 */
    private fun swap(list: MutableList<String>, from: Int, to: Int) {
        list[from] = list[to].also { list[to] = list[from] }
    }

    @Test
    fun `排序后必须用 holder 新位置，旧位置指向别的按钮`() {
        val entries = mutableListOf("A", "B", "C", "D")
        // 把显示 A 的按钮从位置 0 拖到 2：ItemTouchHelper 连续两次相邻 onMove
        swap(entries, 0, 1)   // [B, A, C, D]
        swap(entries, 1, 2)   // [B, C, A, D]
        val holderPosition = 2   // 这个 ViewHolder 现在的 bindingAdapterPosition

        val idx = resolveQuickNavIndex(entries.size, holderPosition)
        assertEquals(2, idx)
        assertEquals("A", entries[idx!!])       // 实时位置 = 用户按住的那个按钮
        assertEquals("B", entries[0])           // 旧快照 0 已经变成了别的按钮
    }

    @Test
    fun `来回拖动后按当前位置仍编辑到同一个按钮`() {
        val entries = mutableListOf("A", "B", "C")
        var holderPosition = 0                  // 显示 A 的 ViewHolder 初始在 0
        listOf(0 to 1, 1 to 2, 2 to 1).forEach { (from, to) ->
            swap(entries, from, to)
            // notifyItemMoved 后 ViewHolder 的位置跟着变
            if (holderPosition == from) holderPosition = to
            else if (holderPosition == to) holderPosition = from
        }

        assertEquals(1, holderPosition)
        val idx = resolveQuickNavIndex(entries.size, holderPosition)
        assertEquals(1, idx)
        assertEquals("A", entries[idx!!])
    }

    @Test
    fun `NO_POSITION 越界和空列表都不解析出下标`() {
        // -1 是 RecyclerView.NO_POSITION；绝不能传进 editQuickNav(-1)（那里 -1 = 新增）
        assertNull(resolveQuickNavIndex(4, -1))
        assertNull(resolveQuickNavIndex(4, 4))
        assertNull(resolveQuickNavIndex(4, 99))
        assertNull(resolveQuickNavIndex(0, 0))
        assertEquals(0, resolveQuickNavIndex(1, 0))
    }
}

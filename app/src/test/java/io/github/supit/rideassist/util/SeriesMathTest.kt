package io.github.supit.rideassist.util

import java.util.ArrayDeque
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 曲线队列的取数工具。
 *
 * 背景：采样队列改成**会话内全量保留**之后，不能再每帧 `toFloatArray()`
 * 把几万个点全拷一遍；实时曲线只需要尾部窗口。
 */
class SeriesMathTest {

    private fun dequeOf(vararg v: Float) = ArrayDeque<Float>(v.size).apply { v.forEach { addLast(it) } }

    @Test fun `取尾部 n 个点保持时间顺序`() {
        val q = dequeOf(1f, 2f, 3f, 4f, 5f)
        assertArrayEquals(floatArrayOf(4f, 5f), SeriesMath.tail(q, 2), 0f)
    }

    @Test fun `n 超过长度时返回全部`() {
        val q = dequeOf(1f, 2f, 3f)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), SeriesMath.tail(q, 100), 0f)
    }

    @Test fun `空队列与 n 为 0 返回空数组`() {
        assertEquals(0, SeriesMath.tail(dequeOf(), 10).size)
        assertEquals(0, SeriesMath.tail(dequeOf(1f, 2f), 0).size)
    }

    @Test fun `all 等价于取全部`() {
        val q = dequeOf(7f, 8f, 9f)
        assertArrayEquals(floatArrayOf(7f, 8f, 9f), SeriesMath.all(q), 0f)
    }
}

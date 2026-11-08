package io.github.supit.rideassist.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐ 历史曲线「颜色 = 数值」的唯一判据。
 *
 * shader 的 0f 在画布顶部（暖色）、1f 在底部（冷色），
 * 所以最大值必须映射到顶部、最小值映射到底部，
 * 否则整张图会「高处绿、低处红」—— 能看，但语义全错。
 */
class HistoryCurveTest {

    @Test fun `最大值画在顶部`() {
        assertEquals(1f, HistoryCurve.verticalRatio(67f, 0f, 67f), 1e-6f)
    }

    @Test fun `最小值画在底部`() {
        assertEquals(0f, HistoryCurve.verticalRatio(0f, 0f, 67f), 1e-6f)
    }

    @Test fun `中值居中`() {
        assertEquals(0.5f, HistoryCurve.verticalRatio(50f, 0f, 100f), 1e-6f)
    }

    @Test fun `负值区间同样成立 电流最小值在底部`() {
        // 电流 -17A ~ 78A
        assertEquals(0f, HistoryCurve.verticalRatio(-17f, -17f, 78f), 1e-6f)
        assertEquals(1f, HistoryCurve.verticalRatio(78f, -17f, 78f), 1e-6f)
    }

    @Test fun `全平数据贴底 不伪装成满值`() {
        assertEquals(0f, HistoryCurve.verticalRatio(5f, 5f, 5f), 1e-6f)
    }

    @Test fun `超出量程的值被夹紧`() {
        assertEquals(1f, HistoryCurve.verticalRatio(999f, 0f, 10f), 1e-6f)
        assertEquals(0f, HistoryCurve.verticalRatio(-999f, 0f, 10f), 1e-6f)
    }
}

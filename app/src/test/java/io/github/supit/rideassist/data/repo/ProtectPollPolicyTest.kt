package io.github.supit.rideassist.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ 回归锁：用户实测「连上保护板阈值反而读不到了」。
 *
 * 根因是第一版把「不要反复读取」写成了「只在第一轮读一次 + 之后每 5 分钟」，
 * 连上时首条请求一丢包就要干等 5 分钟。
 *
 * 正确语义：没读到就每轮重试；读到了才降频。
 */
class ProtectPollPolicyTest {

    @Test fun `本次会话还没读到时 每轮都读`() {
        assertTrue(ProtectPollPolicy.shouldRead(readThisSession = false, sinceLastReadMs = 0))
        assertTrue(ProtectPollPolicy.shouldRead(readThisSession = false, sinceLastReadMs = 1))
    }

    @Test fun `已经读到后 校准周期内不再读`() {
        assertFalse(ProtectPollPolicy.shouldRead(readThisSession = true, sinceLastReadMs = 0))
        assertFalse(
            ProtectPollPolicy.shouldRead(
                readThisSession = true, sinceLastReadMs = ProtectPollPolicy.CALIBRATE_MS - 1
            )
        )
    }

    @Test fun `已经读到后 超过校准周期要重新对表`() {
        assertTrue(
            ProtectPollPolicy.shouldRead(
                readThisSession = true, sinceLastReadMs = ProtectPollPolicy.CALIBRATE_MS
            )
        )
    }

    @Test fun `校准周期可注入 便于按需调整`() {
        assertFalse(ProtectPollPolicy.shouldRead(true, 1_000, calibrateMs = 60_000))
        assertTrue(ProtectPollPolicy.shouldRead(true, 60_000, calibrateMs = 60_000))
    }
}

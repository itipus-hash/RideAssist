package com.yanyang.yybms.core.protocol

import android.util.Log

/**
 * 回包切分器（依据实机抓包与官方 `BMSDataClass` 交叉验证）。
 *
 * ## 回包结构（实机抓包实证）
 * ```
 * [0] 0x01
 * [1] 0x03功能码
 * [2] L    数据长度×2（= 回包总长 − 5）
 * [3..]    数据区
 * [n-2..n-1] CRC16
 * ```
 * ## ⭐ 关键：**总长 = frame[2] + 5**
 * 实测全部吻合：
 * | 帧总长 | frame[2] | data_len×2 | 还原 |
 * |---|---|---|---|
 * | 13  | 0x08 | 4×2=8| 8+5=13 ✓ |
 * | 45  | 0x28 | 20×2=40   | 40+5=45 ✓ |
 * | 163 | 0x9E | 79×2=158  | 158+5=163 ✓ |
 * | 165 | 0xA0 | 80×2=160  | 160+5=165 ✓ |
 * | 167 | 0xA2 | 81×2=162  | 162+5=167 ✓ |
 *
 * 因长度可从帧内容自推导，**无需依赖请求-回包的顺序配对**，
 * 彻底解决 BLE 通知异步到达导致的错位问题。
 */
class FrameAssembler {

    private var buffer = ByteArray(0)

    var totalReceived: Int = 0
        private set

    fun reset() {
        buffer = ByteArray(0)
    }

    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        totalReceived += chunk.size
        buffer += chunk
        val frames = mutableListOf<ByteArray>()

        while (true) {
            val start = indexOfHeader()
            if (start < 0) {
                if (buffer.isNotEmpty()) buffer = buffer.copyOfRange(buffer.size - 1, buffer.size)
                break
            }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            if (buffer.size < 3) break

            // ⭐ 长度自推导：总长 = frame[2] + 5
            val total = ((buffer[2].toInt() and 0xFF) + 5)
            if (total < 7 || total > YybmsProtocol.MAX_FRAME_SIZE) {
                // 长度非法 → 帧头误判，前进 1 字节重新同步
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            if (buffer.size < total) break   // 等后续分片

            val frame = buffer.copyOfRange(0, total)
            buffer = buffer.copyOfRange(total, buffer.size)
            //⚠️ 必须 min(total, 8)：设备回 ACK（如 `01 03 02 01 00 xx xx` 只有 7 字节），
            // 直接 copyOfRange(0,8) 会抛 IndexOutOfBoundsException 打断整条解析链
            val head = frame.copyOfRange(0, minOf(total, 8))
            if (YybmsProtocol.checkFrame(frame)) {
                frames.add(frame)
                Log.d(TAG, "切出帧 ${total} 字节: ${YybmsProtocol.toHex(head)}...")
            } else {
                Log.w(TAG, "丢弃非法帧 ${total} 字节: ${YybmsProtocol.toHex(head)}...")
            }
        }
        return frames
    }

    /** 查找 0x01 0x03 帧头 */
    private fun indexOfHeader(): Int {
        for (i in 0 until buffer.size - 1) {
            if ((buffer[i].toInt() and 0xFF) == 0x01 &&
                (buffer[i + 1].toInt() and 0xFF) == 0x03
            ) return i
        }
        return -1
    }

    companion object {
        private const val TAG = "FrameAssembler"
    }
}

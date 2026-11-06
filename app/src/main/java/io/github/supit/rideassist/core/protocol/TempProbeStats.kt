package io.github.supit.rideassist.core.protocol

/**
 * ⭐ 温度探头独立性统计（临时诊断工具，验证 T1/T2 是否为两路独立探头）。
 *
 * 背景：协议里 T1/T2 是相邻的两个字节（偏移 66/65），实机上数值一直相同（27.0℃）。
 * 两种解释：① 两颗独立探头同环境同精度（1℃ 粒度）读数相同——正常；
 *          ② 设备只接了 1 个探头，固件把同一值复制进两个字节。
 * 本统计记录**每一帧** T1/T2 原始字节，出现分化的帧数 > 0 即为独立实锤；
 * 长期（>50 帧，温度有自然波动）仍为 0 则同源嫌疑大。
 */
object TempProbeStats {
    @Volatile var totalFrames: Int = 0
    @Volatile var divergedFrames: Int = 0
    @Volatile var lastRawT1: Int? = null
    @Volatile var lastRawT2: Int? = null
}

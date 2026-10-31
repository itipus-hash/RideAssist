package io.github.supit.rideassist.core.ble.tpms

import android.util.Log

/**
 * TPMS 胎压传感器的**广播包解析**。
 *
 * ## 协议来源（逆向 DJTPMS 1.6.7，见 `TPMS_REVERSE.md`）
 * 传感器是纯广播 beacon（设备名含 `TPMS`），手机只扫不连。
 * 广播原始字节布局（`L = record[4]`，`i2 = L + 5`）：
 *
 * | 偏移 | 含义 | 换算 |
 * |---|---|---|
 * | i2+2 .. i2+7 | 6B 传感器 ID（CRC 校验源） | —— |
 * | i2+3 | VBAT 标志 | bit0=1 低电 |
 * | i2+4 | 电压 | ÷10 → V |
 * | i2+5 | 温度 | 原值即 ℃ |
 * | i2+6..i2+7 | 胎压，大端 u16 | **kPa + 101 偏移** |
 * | i2+9 | CRC8（校验 ID 6 字节） | init 0xDF / poly 0x2F |
 * | i2+10..i2+15 | 6B SN（绑定身份） | hex 串 |
 *
 * ⚠️ 偏移来自 jadx 反编译，**未实机核对**。CRC 校验是第一道闸：
 * 校验不过的包一律丢弃并打出原始 hex（供实机核对偏移用，见 MEMORY 第 22 条）。
 */
object TpmsPacket {

    private const val TAG = "TpmsPacket"

    /**
     * ⭐ 观测日志钩子：由 TpmsMonitor 注入（写入私有文件，logcat 缓冲会被
     * BMS 高频日志滚动冲掉，靠 logcat 排查「后轮收不到包」不可靠）。
     * 排查完成后置 null 关闭。
     */
    var debugSink: ((String) -> Unit)? = null

    private fun d(msg: String) {
        Log.d(TAG, msg)
        debugSink?.invoke(msg)
    }

    /** 扫描名过滤关键词（与 DJTPMS 一致：名字含 TPMS 即收） */
    const val NAME_KEYWORD = "TPMS"

    /** 胎压原始值下限（raw < 101 按 101 处理，即实际 0 kPa） */
    private const val KPA_OFFSET = 101

    data class Reading(
        /** 6B SN 的 hex 串 —— 绑定的身份标识 */
        val sn: String,
        /** 实际胎压 kPa（已减 101 偏移） */
        val kpa: Int,
        /** 温度 ℃ */
        val tempC: Int,
        /** 电池电压 V */
        val voltage: Double,
        /** 低电标志 */
        val lowBattery: Boolean,
        val timestampMs: Long,
    ) {
        val bar: Double get() = kpa / 100.0
        val snTail: String get() = sn.takeLast(4)
    }

    /**
     * CRC8：初值 0xDF，多项式 0x2F，MSB first，无反转、无终值异或。
     * （对应 DJTPMS `CYUtils.calculateChecksum`）
     */
    fun crc8(id: ByteArray): Int {
        var c = 0xDF
        for (b in id) {
            c = c xor (b.toInt() and 0xFF)
            repeat(8) {
                c = if (c and 0x80 != 0) (c shl 1) xor 0x2F else c shl 1
            }
        }
        return c and 0xFF
    }

    /**
     * 解析一条广播。任何一步不满足都返回 null：
     * - 名字不含 [NAME_KEYWORD]
     * - 原始字节为空 / 太短
     * - CRC 不匹配（⚠️ 同时打出原始 hex 供实机核对，不要静默吞掉）
     */
    fun parse(deviceName: String?, record: ByteArray?, nowMs: Long): Reading? {
        if (deviceName?.contains(NAME_KEYWORD, ignoreCase = true) != true) return null
        if (record == null || record.size < 24) {
            d("包过短: name=$deviceName len=${record?.size}")
            return null
        }

        val l = record[4].toInt() and 0xFF
        val base = l + 5
        // 最后一字节在 base+18，即至少需要 base+19 字节
        if (base + 19 > record.size) {
            d("长度不够: name=$deviceName L=$l base=$base len=${record.size} hex=${record.toHex()}")
            return null
        }

        val id = record.copyOfRange(base + 2, base + 8)
        val expected = record[base + 9].toInt() and 0xFF
        val actual = crc8(id)
        if (actual != expected) {
            d("CRC 不过 expect=$expected actual=$actual name=$deviceName hex=${record.toHex()}")
            return null
        }

        var rawKpa = ((record[base + 6].toInt() and 0xFF) shl 8) or
            (record[base + 7].toInt() and 0xFF)
        if (rawKpa < KPA_OFFSET) rawKpa = KPA_OFFSET

        val sn = record.copyOfRange(base + 10, base + 16).toHex()
        d("解析成功 sn=$sn kpa=${rawKpa - KPA_OFFSET} temp=${record[base + 5].toInt()} rssi标记无")

        return Reading(
            sn = sn,
            kpa = rawKpa - KPA_OFFSET,
            tempC = record[base + 5].toInt(), // 有符号：低温负值要保住
            voltage = (record[base + 4].toInt() and 0xFF) / 10.0,
            lowBattery = (record[base + 3].toInt() and 1) == 1,
            timestampMs = nowMs,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        "%02X".format(it)
    }
}

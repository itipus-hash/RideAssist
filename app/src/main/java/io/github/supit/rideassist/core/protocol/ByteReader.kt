package io.github.supit.rideassist.core.protocol

/**
 * 大端序（Big-Endian）字节读取工具。
 *
 * 所有方法做越界保护：数据不足返回 **null**，
 * 由调用方决定如何处理（避免把"读不到"误当成"值是 0"）。
 */
class ByteReader(
    val data: ByteArray,
    val start: Int = 0
) {
    /** 剩余可读字节数 */
    fun size(): Int = data.size - start

    fun has(offset: Int, length: Int): Boolean = size() >= offset + length

    /** 无符号 8 位 */
    fun u8(offset: Int): Int? {
        val i = start + offset
        return if (i in data.indices) data[i].toInt() and 0xFF else null
    }

    /** 无符号 16 位大端（官方 BMSDataClass.byte2short 同款）*/
    fun u16(offset: Int): Int? {
        if (!has(offset, 2)) return null
        return ((u8(offset)!! shl 8) or u8(offset + 1)!!)
    }

    /** 语义更清晰的别名：本协议部分 16 位字段为大端 */
    fun be16(offset: Int): Int? = u16(offset)

    /**
     * 无符号 16 位**小端**（低字节在前）。
     * 彦阳协议中单体电压、容量等字段使用小端，寄存器地址则是大端。
     */
    fun le16(offset: Int): Int? {
        if (!has(offset, 2)) return null
        return ((u8(offset + 1)!! shl 8) or u8(offset)!!)
    }

    /** 无符号 32 位**小端**（低字节在前） */
    fun le32(offset: Int): Int? {
        if (!has(offset, 4)) return null
        val b0 = u8(offset)!!
        val b1 = u8(offset + 1)!!
        val b2 = u8(offset + 2)!!
        val b3 = u8(offset + 3)!!
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    /** 有符号 32 位小端（电流可能为负放电） */
    fun le32s(offset: Int): Int? {
        val v = le32(offset) ?: return null
        // 最高位为 1 表示负数
        return if (v and 0x80000000.toInt() != 0) v - 0x100000000L.toInt() else v
    }

    /** 有符号 16 位大端（电流可能为负） */
    fun i16(offset: Int): Int? {
        val v = u16(offset) ?: return null
        return v.toShort().toInt()
    }

    /** 无符号 32 位大端 */
    fun u32(offset: Int): Long? {
        if (!has(offset, 4)) return null
        return (u8(offset)!!.toLong() shl 24) or
                (u8(offset + 1)!!.toLong() shl 16) or
                (u8(offset + 2)!!.toLong() shl 8) or
                u8(offset + 3)!!.toLong()
    }
}
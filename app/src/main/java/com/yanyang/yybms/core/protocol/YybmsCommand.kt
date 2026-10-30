package com.yanyang.yybms.core.protocol

/**
 * 彦阳 BMS 只读寄存器集。
 *
 * ## ⭐ 全部来自官方 APK 反编译（`com.yybms` v3.4.05）
 * `com.yybms.app.util.BleDataUtils` 第 726~739 行的 `paramA` 定义：
 * ```java
 * paramA.add(new CmdParamBean(CmdGetDataMsg((byte)1,(short)63,(short)4),  1, 63, 4));
 * paramA.add(new CmdParamBean(CmdGetDataMsg((byte)1,(short)1, (short)47), 1, 1, 47));
 * paramA.add(new CmdParamBean(CmdGetDataMsg((byte)1,(short)48,(short)42), 2, 48, 42));
 * paramA.add(new CmdParamBean(CmdGetDataMsg((byte)1,(short)90,(short)22), 3, 90, 22));
 * ```
 * 构造签名：`CmdGetDataMsg(byte ID, short Addr, short data_len)`，
 * 其中 ID 对应首字节（addr=0 时官方传 0），data_len 决定回包总长 `len*2+5`。
 *
 * frameId 决定用哪个解析器（官方 `DecodingMobusFrameA`）：
 * - frameId=1 → `decodeFrameA1`：**实时数据**（总压/电流/SOC/单体电压/温度）
 * - frameId=2 → `decodeFrameA2`：保护参数（过压/欠压阈值等）
 * - frameId=3 → `decodeFrameA3`：硬件配置（霍尔增益/序列号/蓝牙名/密码）
 *
 * ⚠️ 只读，不含任何写入/配置/均衡控制命令。
 */
enum class YybmsCommand(
    val addr: Int,
    val dataLen: Int,
    val frameId: Int,
    val label: String,
    val saAdd: Int = 1
) {
    /**
     * addr=0x01 实时数据（本 APP 唯一需要解析的）。
     * data_len=79（0x4F）：官方 paramB 的定义，抓包实证请求帧为
     * `00/01 03 00 01 00 4F 55 FE`，回包总长 = 79*2+5 = 163字节。
     * 注：官方 paramA 对addr=0x01 用len=47，但实机走的是 79，故取 79。
     */
    READ_REALTIME(0x0001, 79, 1, "实时数据"),

    /** addr=0x3F, len=4 → 版本探测帧（官方据此判定走 paramA/paramB） */
    READ_VERSION(0x003F, 4, 0, "版本探测"),

    /** addr=0x50, len=80, frameId=1 → **单体电压数组**（165 字节回包）*/
    READ_CELL_VOLTAGE(0x0050, 80, 1, "单体电压"),

    /** addr=0xA0, len=81 → 保护/告警参数（167 字节回包） */
    READ_PROTECT_PARAM(0x00A0, 81, 2, "保护参数"),

    /** addr=0xF0, len=20, frameId=4 → 序列号/均衡参数（45 字节回包） */
    READ_SERIAL(0x00F0, 20, 4, "序列号");

    fun buildFrame(): ByteArray = YybmsProtocol.buildReadFrame(addr, dataLen, saAdd)

    /** 期望的回包总长（官方规则：len*2+5） */
    val expectedResponse: Int get() = YybmsProtocol.expectedResponseLength(dataLen)

    /** 轮询顺序：实时数据最优先 */
    val pollOrder: Int get() = when (this) {
        READ_CELL_VOLTAGE -> 0    // 单体电压最优先
        READ_REALTIME -> 1        // 串数/设备信息
        READ_PROTECT_PARAM -> 2
        READ_SERIAL -> 3
        READ_VERSION -> 4
    }

    companion object {
        fun fromAddr(a: Int): YybmsCommand? = entries.firstOrNull { it.addr == a }

        /**
         * 按**回包总长**反查对应命令。
         *
         * ⚠️ 回包帧头 `[2] b3` 并**不是 addr**（实测 165 字节帧的 b2b3 = 0xA000，
         * 而真实 addr 是 0x50）——b2 是「数据长度×2」= 总长−5。
         * 由于本 APP 轮询的5 个命令 data_len 互不相同，
         * 用总长反查是**唯一可靠**的对应方式。
         */
        fun fromResponseLength(total: Int): YybmsCommand? =
            entries.firstOrNull { it.expectedResponse == total }
    }
}
package io.github.supit.rideassist.core.protocol.model

/** 充放电状态 */
enum class ChargeState(val label: String) {
    CHARGING("充电"),
    DISCHARGING("放电"),
    IDLE("静置"),
    UNKNOWN("未知")
}

/**
 * BMS 告警标志 —— 来自 **paramB（addr=0x50，165B 帧）** 的 32 位告警位域
 * `byte2intB(bArr, 152)`。
 *
 * ## ⚠️ 曾踩的坑：错把 paramA 的 `ErrIndex` 用在这里
 * 上一版读的是 `bArr[87]`，来源是官方 `decodeFrameA1`（**paramA**）里的
 * `WarnStruct.ErrIndex = copyOfRange(frame,80,98)[10]`。
 * 但本设备实际走 `decodeFrameB2`（paramB），**该函数里根本没有 ErrIndex**，
 * `bArr[87]` 只是「充满剩余分钟」的高字节 → 恒为 0x00
 * → 位域全 0 → **连上电也永远显示「正常」**，等于变相写死。
 *
 * ## 权威来源
 * `com.yybms.app.util.BMSDataClass.decodeFrameB2` 第 2835~2974 行：
 * ```java
 * int f = byte2intB(bArr, 152);   // KEY_APPLIED_PACKAGE_CHECK_LIB_META = 152
 * if ((f &1)   != 0) WarnStruct.Over_Vol_Warn= 1;   // 单体过压
 * if ((f &2)   != 0) WarnStruct.Low_Vol_Warn  = 1;   // 单体欠压
 * if ((f &8)   != 0) WarnStruct.Over_SumVol_Warn=1;  // 总压过压
 * ...
 * if ((f & 256)!= 0) WarnStruct.scdFlg= 1;            // 短路
 * if ((f & 134217728)!=0) WarnStruct.vInvild= 1;      // 电压采样失效
 * ```
 *
 * ## 注意：bit15「充满」/ bit16「放空」不是故障
 * 那是正常状态标记，故**不纳入**告警列表，避免充电满时误报异常。
 */
enum class AlarmFlag(val bit: Int, val label: String) {
    // ---- 电压类（bit0~4）----
    CELL_OVER_VOLTAGE(0, "单体过压"),
    CELL_UNDERVOLTAGE(1, "单体欠压"),
    UV_KILL(2, "欠压关断"),
    PACK_OVER_VOLTAGE(3, "总压过压"),
    PACK_UNDERVOLTAGE(4, "总压欠压"),

    // ---- 电流类（bit5~8）----
    OVER_CURRENT_CHARGE(5, "充电过流"),
    OVER_CURRENT_DISCHARGE(6, "放电过流"),
    HW_OVER_CURRENT(7, "硬件过流"),
    SHORT_CIRCUIT(8, "短路"),

    // ---- 温度类（bit9~14）----
    OVER_TEMP_CHARGE(9, "充电过温"),
    UNDER_TEMP_CHARGE(10, "充电低温"),
    OVER_TEMP_DISCHARGE(11, "放电过温"),
    UNDER_TEMP_DISCHARGE(12, "放电低温"),
    OVER_TEMP_MOS(13, "MOS 过温"),
    SOC_ABNORMAL(14, "SOC 异常"),

    // ---- 采样/执行器失效（bit25~31）----
    BALANCE_INVALID(25, "均衡失效"),
    BALANCE2_INVALID(26, "均衡模块失效"),
    VOLT_SENSOR_FAIL(27, "电压失效"),
    TEMP_SENSOR_FAIL(28, "温度失效"),
    CURRENT_SENSOR_FAIL(29, "电流失效"),
    DISCHARGE_SWITCH_FAIL(30, "放电开关失效"),
    CHARGE_SWITCH_FAIL(31, "充电开关失效");

    /**
     * ⭐ 是否属于「电压类」告警 —— 这类**可以用 0xA0 帧读到的阈值**做物理判定。
     *
     * 判据是 bit 区间而不是手写清单：位域是协议定义的，
     * 将来枚举扩充时不会漏判。
     */
    val isVoltageClass: Boolean get() = bit <= 4

    /** ⭐ 电流类（bit5~8）—— 0xA0 帧有充/放电过流阈值，可做物理判定 */
    val isCurrentClass: Boolean get() = bit in 5..8

    /** ⭐ 温度类（bit9~13）—— 0xA0 帧有充/放电过温低温阈值，可做物理判定 */
    val isTempClass: Boolean get() = bit in 9..13

    /**
     * ⭐⭐ 是否**能够**用实测值 vs 阈值做物理判定。
     *
     * `effectiveAlarms()` 用它决定「重新判定」还是「照搬硬件位」。
     *
     * - 电压类 bit0/1（单体）、bit3/4（总压）→ 能
     * - 电流类 bit5/6 → 能（bit7 硬件过流、bit8 短路只能借阈值近似）
     * - 温度类 bit9~13 → 能
     * - 其余（欠压关断、SOC 异常、采样/执行器失效）→ **不能**，无阈值可读
     */
    val isPhysicallyJudged: Boolean
        get() = bit <= 6 || bit in 9..13

    /** 本类是否有独立阈值字段（bit7 硬件过流 / bit8 短路借用放电阈值，不算独立） */
    val hasThreshold: Boolean
        get() = bit == 0 || bit == 1 || bit == 3 || bit == 4 ||
            bit == 5 || bit == 6 || bit in 9..13

    companion object {
        /** 按 32 位位域解析；0 = 全部正常 → 空列表 */
        fun fromMask(mask: Int): List<AlarmFlag> =
            entries.filter { mask and (1 shl it.bit) != 0 }

        /**
         * 位域里非故障的状态位，解析时**主动排除**。
         * bit15 = 充满、bit16 = 放空、bit17~24 = 官方 hwOvFlg/hwUvFlg/hwOcFlg
         * 等硬件标志与tmpycyh，属于状态而非需要提醒的告警。
         */
        private const val NON_FAULT_MASK =
            (1 shl 15) or (1 shl 16) or
                (1 shl 17) or (1 shl 18) or (1 shl 19) or
                (1 shl 20) or (1 shl 21) or (1 shl 22) or
                (1 shl 23) or (1 shl 24)

        /** 只要有告警位被置起就说明异常 */
        fun hasFault(mask: Int): Boolean =
            (mask and NON_FAULT_MASK.inv()) != 0
    }
}

/** 一次完整的读周期结果 */
data class BmsRealtimeData(
    // ---- 总参数 ----
    val totalVoltage: Float = 0f,        // V
    val totalCurrent: Float = 0f,        // A，⭐实测：充电为负(-9.25A)、放电为正
    val remainingCapacity: Float = 0f,    // Ah
    val soc: Float = 0f,                 // %
    val soh: Float = 0f,                 // %
    val ratedCapacity: Float = 0f,       // Ah
    val chargeState: ChargeState = ChargeState.UNKNOWN,

    // ---- 单体电芯 ----
    val cellCount: Int = 0,
    val cellVoltages: List<Float> = emptyList(),   // V，按索引 1..N
    val maxCellVoltage: Float = 0f,
    val minCellVoltage: Float = 0f,
    val cellDiff: Float = 0f,            // mV

    // ---- 温度 ----
    val temp1: Float? = null,            // ℃ null 表示未接探头
    val temp2: Float? = null,
    val temp3: Float? = null,
    /** MOS 温度（℃），实测偏移 bArr[64]，原始值−40 */
    val mosTemp: Float? = null,

    // ---- 保护&告警 ----
    /**
     * 告警列表（来自 paramB 165B 帧 `bArr[152..155]` 32 位位域）。
     * 空列表 + [hasDeviceData]=true 才代表「设备真的正常」。
     */
    val alarmFlags: List<AlarmFlag> = emptyList(),
    /**
     * 均衡是否开启 —— 由 `bArr[118..121]` 逐串均衡位是否**任一置起**决定。
     * ⚠️ 之前是硬编码默认值 false，等于永远显示「均衡：关闭」。
     */
    val balanceOn: Boolean = false,
    /** 正在均衡的单体下标（1..N），空表示无 */
    val balancingCells: List<Int> = emptyList(),
    /** 均衡截止电压（V），来自 165B 帧 `bArr[122..123]` /1000 */
    val balanceCutoffVolt: Float = 0f,
    /** 均衡压差阈值（mV），来自 165B 帧 `bArr[124..125]` */
    val balanceDiffThreshold: Float = 0f,

    // ---- 保护参数阈值（来自 0xA0 保护参数帧）----

    /**
     * ⭐ 单体**过压保护阈值**（V），来自 0xA0 帧 `Over_Vol_Set`。
     *
     * ## 权威来源（官方 APK com.yybms v3.4.05）
     * `BMSDataClass.decodeFrameB3`（addr=160=0xA0）：
     * ```java
     * byte[] bArr = Arrays.copyOfRange(frame3, 3, ...);
     * WarnStruct.Over_Vol_Set = byte2shortB(bArr, 18);   // 本字段
     * WarnStruct.Over_Vol_Rec = byte2shortB(bArr, 20);   // 恢复阈值
     * ```
     *
     * ## 单位 = **mV**（官方显示处 `GetOverVolSet()` = `Over_Vol_Set / 1000.0`）
     * 佐证：官方 `GetHB_BatteryType()` 用 `Over_Vol_Set >= 3900` 区分铅酸/铁锂，
     * 3900mV = 3.9V —— 正是铅酸/铁锂的分界电压，量纲自洽。
     *
     * ⭐ **判定过压必须用它，而不是 `Over_Vol_Rec`（恢复阈值）**：
     * 恢复阈值比保护阈值低（比如保护 4.20V / 恢复 4.10V），
     * 用恢复阈值判会「还没到保护点就报警」；用保护阈值判才与硬件行为一致。
     *
     * 0 = 尚未读到该帧（见[hasProtectParams]）。
     */
    val overVoltSet: Float = 0f,

    /** 单体欠压保护阈值（V），来自 `Low_Vol_Set`（0xA0 帧偏移 24） */
    val underVoltSet: Float = 0f,

    /** 单体过压**恢复**阈值（V），来自 `Over_Vol_Rec`（0xA0 帧偏移 20） */
    val overVoltRecover: Float = 0f,

    /** 单体欠压**恢复**阈值（V），来自 `Low_Vol_Rec`（0xA0 帧偏移 26） */
    val underVoltRecover: Float = 0f,

    // ---------- ⭐ 电流类阈值（0xA0 帧，本轮新增）----------

    /** 充电过流阈值（A），偏移 62，原值 ÷100；官方存负值，此处已取绝对值 */
    val overCurrentCharge: Float = 0f,
    /** 放电过流**一级**阈值（A），偏移 68，原值 ÷100 */
    val overCurrentDischarge: Float = 0f,
    /**
     * 放电过流**二级**阈值（A），偏移 70，原值 ÷100。
     *
     * ⚠️ 修正记录：原名 `overCurrentDischargeRecover`（放电过流**恢复**）是**错的**。
     * 官方 0xA0 帧没有放电过流恢复字段；`[70]` 是 `Over_DisChargeCur2_Set`，
     * 即第二档（更严的一级）过流阈值。
     */
    val overCurrentDischarge2: Float = 0f,
    /** 短路电流阈值（A），偏移 92，**原值即安培不缩放** */
    val shortCircuitCurrent: Float = 0f,

    // ---------- ⭐ 总压类阈值（0xA0 帧，本轮新增）----------

    /** 总压过压阈值（V），偏移 36，原值 ÷100 */
    val packOverVoltSet: Float = 0f,
    /** 总压过压恢复阈值（V），偏移 38，原值 ÷100 */
    val packOverVoltRecover: Float = 0f,
    /** 总压欠压阈值（V），偏移 42，原值 ÷100 */
    val packUnderVoltSet: Float = 0f,
    /** 总压欠压恢复阈值（V），偏移 44，原值 ÷100 */
    val packUnderVoltRecover: Float = 0f,

    // ---------- ⭐ 温度类阈值（0xA0 帧，本轮新增，单位 ℃）----------

    /** 充电过温阈值，偏移 80 */
    val overTempCharge: Float = 0f,
    val overTempChargeRecover: Float = 0f,
    /** 充电低温阈值，偏移 82 */
    val underTempCharge: Float = 0f,
    val underTempChargeRecover: Float = 0f,
    /** 放电过温阈值，偏移 84 */
    val overTempDischarge: Float = 0f,
    val overTempDischargeRecover: Float = 0f,
    /** 放电低温阈值，偏移 86 */
    val underTempDischarge: Float = 0f,
    val underTempDischargeRecover: Float = 0f,
    /** MOS 过温阈值，偏移 88 */
    val mosOverTemp: Float = 0f,

    /**
     * 是否已读到保护参数帧。
     *
     * ⚠️ 必须单独一个标志位：阈值「恰好是 0」与「没读到」是两回事，
     * 用 `overVoltSet > 0` 判断会在协议异常时误判成"没保护"。
     */
    val hasProtectParams: Boolean = false,

    // ---- 电池化学体系 ----

    /**
     * ⭐ 电池类型（来自 0x01 实时帧 `bArr[149]`的 `batType`）。
     *
     * ## 权威来源（官方 APK com.yybms v3.4.05）
     * `BMSDataClass.decodeFrameB1(addr=1)`：
     * ```java
     * VersionDataStruct.CellNum = bArr[148];   // 串数
     * VersionDataStruct.batType = bArr[149];   // 电池类型
     * ```
     * `GetBatteryTypeB()` 的映射：
     * ```java
     * if (b == 0) return "三元";
     * if (b == 1) return "铁锂";
     * if (b == 2) return "铅酸";
     * if (b == 3) return "钠离子";
     * return (b == 4) ? "钛酸锂" : "--";
     * ```
     *
     * ⚠️ **必须用 paramB 的 `batType`（本设备走这条）**。
     * paramA 走的是 `SNnum[1] >> 3 & 7`，两个字段**值不同**：
     * 实机抓包同一设备 batType=0（三元）、SNnum[1]路径算出 4（钛酸锂）——
     * 官方APP 显示「三元」，证明 paramB 才是对的。
     *
     * -1 = 未读到。
     */
    val batteryType: Int = -1,

    /**
     * ⭐ 是否真的收到过设备数据。
     *
     * 断连/未连接时模型里全是默认值，**和"设备真的正常"无法区分**。
     * 之前就因为这个导致断连时仍显示「电池状态：正常」——
     * 属于误导性显示，不可接受。
     */
    val hasDeviceData: Boolean = false,

    // ---- JBD 0x03 扩展字段 ----
    val cycleCount: Int = 0,             // 循环次数
    /** 充满剩余分钟数，0 表示不适用（未充电或未提供） */
    val chargeMinutesLeft: Int = 0,
    val chargeMosOn: Boolean = false,    // 充电 MOS 导通
    val dischargeMosOn: Boolean = false,  // 放电 MOS 导通

    // ---- 本轮新增：协议里已上报但此前未采集的字段 ----

    /**
     * ⭐ 最大支持串数（0x01 帧偏移 111）。
     *
     * 用途：显示「17 串 / 最大支持 24 串」，换电池规格时能一眼看出上限。
     * 0 = 未读到。
     */
    val maxCellNumSupported: Int = 0,

    /**
     * ⭐ 温度探头路数（0x01 帧偏移 103 三个 u8 之和，官方 `TmpNum` 口径）。
     *
     * 用途：**决定显示几路温度**，而不是靠 `tempN != null` 猜 ——
     * 探头没接时读数也是合法数字，只靠 null 判断会显示不存在的通道。
     */
    val tempProbeCount: Int = 0,

    /**
     * ⭐ 版本三件套，来自 163B 帧：硬件 `[74]`(16位) / Boot `[122]` / SW `[123]`(各1字节)。
     *
     * 格式：`"HW 1.01  Boot 1.04  SW 1.43"`，未读到为 `--`。
     * ⚠️ 三者**位宽与除数都不同**：HW 是 le16÷100，Boot/SW 是 u8÷100。
     * 官方 Boot 在源码里有 `/10` 与 `/100` 两套（`selParam` 选择），实机走 `/100`。
     */
    val firmwareVersion: String = "--",

    // ---------- ⭐ 设备身份与通信参数（对齐官方「服务→电池信息」页）----------

    /**
     * 产品型号 / 电池编码，163B 帧 `[80..91]` 连续 12 字节 ASCII
     * （官方 `BMSDataClass:2456` 逐字节拼串，遇 0 截断）。
     * 实机示例：`YY-BCU12-MOS-20S150A-MUX`
     */
    val productName: String = "",

    /** 485 协议类型，偏移 114（官方 `RS485Proto`） */
    val rs485Proto: Int = 0,
    /** 485 波特率，偏移 115（官方 `RS485Baut`；官方按档位映射成 9600/19200/…） */
    val rs485Baud: Int = 0,
    /** CAN 协议类型，偏移 116（官方 `CANProto`；255 = 无） */
    val canProto: Int = 0,
    /** CAN 波特率，偏移 117（官方 `CANBaut`） */
    val canBaud: Int = 0,

    /**
     * 休眠时间（秒），0xA0 帧偏移 140（官方 `idleSleepTime = byte2shortB(140)`）。
     * 官方首页显示「休眠时间 60S」。
     */
    val sleepTimeSec: Int = 0,

    /**
     * SN 序列，45B 帧（`0x00F0`）`[20..35]` 共 16 字节 ASCII
     * （官方 `decodeFrameB4` 的 `copyOfRange(bArr, 20, 36)`）。
     * 官方界面示例：`5911010163411EC0`
     */
    val serialNumber: String = "",

    /**
     * 低压待机阈值（V）—— ⚠️ **偏移未确认，故意留空**。
     *
     * 官方 `LVI = byte2shortB(bArr, 20)` 与同帧 SN 起点完全重叠，源码自相矛盾，
     * 判定为 jadx 还原错误。未抓到实机字节前不猜偏移（见方法论第 22 条）。
     * 显示 `--` 比显示一个错的阈值安全。
     */
    @Suppress("unused")
    val lowVoltStandby: Float = 0f,

    val timestamp: Long = System.currentTimeMillis()
) {
    /** 功率 W = 总压 × 电流（派生量，充电为正） */
    val power: Float get() = totalVoltage * totalCurrent

    /**
     * 是否有告警。
     * ⚠️ **必须在 [hasDeviceData] 为 true 时才可信**——
     * 没数据时 alarmFlags 为空，但那是"不知道"而非"正常"。
     */
    val isNormal: Boolean get() = hasDeviceData && alarmFlags.isEmpty()

    /** 单体串数是否越界（协议自适应 4~24 串） */
    val cellCountValid: Boolean get() = cellCount in 4..24

    /** 当前最大单体电压（V）；无单体数据时返回 0 */
    val maxCellVolt: Float get() = cellVoltages.maxOrNull() ?: 0f

    /** 当前最小单体电压（V）；无单体数据时返回 0 */
    val minCellVolt: Float get() = cellVoltages.minOrNull() ?: 0f

    companion object {
        /** 三元锂 */
        const val TYPE_TERNARY = 0
        /** 磷酸铁锂 */
        const val TYPE_LFP = 1
        /** 铅酸 */
        const val TYPE_LEAD = 2
        /** 钠离子 */
        const val TYPE_SODIUM = 3
        /** 钛酸锂 */
        const val TYPE_TITANATE = 4

        /**
         * 电池类型值 → 中文名。
         *
         * ⭐ 严格照抄官方 `GetBatteryTypeB()` + `aapt2 dump resources` 实证：
         * `sy`=三元 / `tl`=铁锂 / `tsl`=钛酸锂 / `na`=钠离子。
         *
         * ⚠️ **不搞「电压推断」**（曾实现过，按用户要求删除）：
         * 单体电压会随 SOC/负载浮动，铁锂充满后可升到 3.65V 撞上三元区间 ——
         * 这种靠瞬时电压猜化学体系的做法不可靠，
         * **设备上报的 batType 字段才是唯一可信来源**。
         */
        fun typeName(v: Int): String = when (v) {
            TYPE_TERNARY -> "三元"
            TYPE_LFP -> "铁锂"
            TYPE_LEAD -> "铅酸"
            TYPE_SODIUM -> "钠离子"
            TYPE_TITANATE -> "钛酸锂"
            else -> "--"
        }

        /**
         * 各化学体系的**标称单体电压**（V）。
         *
         * ⭐ 这是「算剩余能量」必须用的电压，**不能用实时总压**。
         * 实时总压会随负载波动：放电时压降、停车/卸载时回弹，
         * 拿它当基准会让「停车时里程反而上涨」这种荒谬现象出现。
         *
         * 标称电压是电池的固有属性，不随工况变化。
         */
        fun nominalCellVolt(v: Int): Float = when (v) {
            TYPE_TERNARY -> 3.7f
            TYPE_LFP -> 3.2f
            TYPE_LEAD -> 2.0f
            TYPE_SODIUM -> 3.1f
            TYPE_TITANATE -> 2.3f
            else -> 0f
        }
    }

    /**
     * 电池类型中文名 —— **只用协议上报的 `batType` 字段**，不做任何推断。
     *
     * - [batteryType] = -1（未读到）→ `--`
     * - 官方 `GetBatteryTypeB()` 同样的 0/1/2/3/4 分支
     */
    val batteryTypeName: String get() = typeName(batteryType)

    /**
     * ⭐ **标称总压**（V）= 串数 × 该化学体系的标称单体电压。
     *
     * 0 表示读不到（电池类型未上报或串数为 0），调用方需据此隐藏相关估算值。
     *
     * ⚠️ 这不是「当前总压」—— 当前总压见 [totalVoltage]，
     * 它会随负载抖动，不能用来算能量。
     */
    val nominalTotalVolt: Float
        get() {
            val unit = nominalCellVolt(batteryType)
            if (unit <= 0f || cellCount <= 0) return 0f
            return unit * cellCount
        }

    /**
     * ⭐ 是否真的「单体过压」—— **用实测最大单体与设备阈值比较**。
     *
     * ## 为什么不用告警位直接下结论
     * 硬件的告警位是**保护动作**（已经在切断 MOS），可能带延时、
     * 迟滞、或因单体压差/均衡逻辑而提前置位。用户看到的
     * 「SOC 100%、电流 0、最高单体 4.172V 却报过压」就是这个错位。
     *
     * 改为「实测最大单体 > 保护阈值」后：
     * - 只有**真的超过阈值**才报警，与保护参数的物理含义对齐
     * - 阈值从 0xA0 帧实时读取，不写死常量，换设备/改配置都自动跟随
     *
     * @return null = 阈值未读到（无法判断）；true/false = 明确结论
     */
    fun isCellOverVoltage(): Boolean? {
        if (!hasProtectParams || overVoltSet <= 0f) return null
        val maxV = maxCellVolt
        if (maxV <= 0f) return null
        return maxV > overVoltSet
    }

    /**
     * 是否「单体欠压」—— 判据与 [isCellOverVoltage] 对称：
     * `实测最小单体电压 < 设备欠压保护阈值`。
     *
     * @return null = 阈值未读到（无法判断）
     */
    fun isCellUnderVoltage(): Boolean? {
        if (!hasProtectParams || underVoltSet <= 0f) return null
        val minV = cellVoltages.minOrNull() ?: return null
        if (minV <= 0f) return null
        return minV < underVoltSet
    }

    /**
     * ⭐⭐⭐ **最终生效的告警列表** —— 首页状态卡与弹窗共用同一判据。
     *
     * ## 统一模型（原来只有一半，现在三类齐全）
     *
     * | 类别 | 阈值来源 | 判据 |
     * |---|---|---|
     * | 电压类 bit0~4 | 0xA0 帧 | `实测 vs 阈值`（单体 max/min + 总压） |
     * | 电流类 bit5~8 | 0xA0 帧 | `abs(I) vs 阈值`（按充放电方向取对应阈值） |
     * | 温度类 bit9~13 | 0xA0 帧 | `实测温度 vs 阈值`（MOS + 全部探头取最高）|
     * | 采样失效 bit25~31 | — | 只用硬件位（无阈值可读）|
     *
     * ## 三条铁律
     * 1. **阈值已读到 → 用实测值重新判定**，不采信硬件位
     *    （硬件位带迟滞与延时，会在未超阈值时误报）
     * 2. 实测超阈值 → **即使硬件位没置位也要报**
     *    （保护动作有延时，等硬件动作就晚了）
     * 3. 阈值未读到 → **退回用硬件位**。宁可多报不可漏报，
     *    不能因为读不到阈值就变成「永远显示正常」这种安全性倒退。
     */
    fun effectiveAlarms(): List<AlarmFlag> {
        if (!hasProtectParams) return alarmFlags

        val result = LinkedHashSet<AlarmFlag>()
        // ⭐ 统一规则：**有独立阈值的**（hasThreshold）一律重新物理判定，
        // 硬件位只作为「未超阈值时不报」的反向依据；
        // **无独立阈值的**（欠压关断、SOC 异常、采样/执行器失效）照搬硬件位。
        result.addAll(alarmFlags.filterNot { it.hasThreshold })

        // ---------- 电压类：物理判据 ----------
        if (isCellOverVoltage() == true) result.add(AlarmFlag.CELL_OVER_VOLTAGE)
        if (isCellUnderVoltage() == true) result.add(AlarmFlag.CELL_UNDERVOLTAGE)
        if (isPackOverVoltage() == true) result.add(AlarmFlag.PACK_OVER_VOLTAGE)
        if (isPackUnderVoltage() == true) result.add(AlarmFlag.PACK_UNDERVOLTAGE)

        // ---------- 电流类：物理判据 ----------
        isOverCurrentCharge()?.let { if (it) result.add(AlarmFlag.OVER_CURRENT_CHARGE) }
        isOverCurrentDischarge()?.let { if (it) result.add(AlarmFlag.OVER_CURRENT_DISCHARGE) }
        // 硬件过流 bit7 没有独立阈值，但**实测超放电阈值**同样说明硬件过流成立，
        // 所以这里额外补上 —— 否则会出现「硬件报了但物理判据把它滤掉」的空档。
        if (isOverCurrentDischarge() == true) result.add(AlarmFlag.HW_OVER_CURRENT)

        // ---------- 温度类：物理判据 ----------
        isOverTemp()?.let { if (it) result.add(AlarmFlag.OVER_TEMP_DISCHARGE) }
        isUnderTemp()?.let { if (it) result.add(AlarmFlag.UNDER_TEMP_DISCHARGE) }
        isMosOverTemp()?.let { if (it) result.add(AlarmFlag.OVER_TEMP_MOS) }

        return result.toList()
    }

    // ==================== 各类物理判据 ====================

    /** 充电过流（负电流）：`|I| > 充电过流阈值` */
    fun isOverCurrentCharge(): Boolean? {
        if (overCurrentCharge <= 0f) return null
        val amps = kotlin.math.abs(totalCurrent)
        if (amps <= 0f) return null
        return totalCurrent < 0f && amps > overCurrentCharge
    }

    /** 放电过流（正电流）：`I > 放电过流阈值` */
    fun isOverCurrentDischarge(): Boolean? {
        if (overCurrentDischarge <= 0f) return null
        if (totalCurrent <= 0f) return null
        return totalCurrent > overCurrentDischarge
    }

    /** 短路：`I > 短路电流阈值`（短路电流远大于过流阈值，故用独立字段） */
    fun isShortCircuit(): Boolean? {
        if (shortCircuitCurrent <= 0f) return null
        if (totalCurrent <= 0f) return null
        return totalCurrent > shortCircuitCurrent
    }

    /** 总压过压：`实测总压 > 总压过压阈值` */
    fun isPackOverVoltage(): Boolean? {
        if (packOverVoltSet <= 0f || totalVoltage <= 0f) return null
        return totalVoltage > packOverVoltSet
    }

    /** 总压欠压：`实测总压 < 总压欠压阈值` */
    fun isPackUnderVoltage(): Boolean? {
        if (packUnderVoltSet <= 0f || totalVoltage <= 0f) return null
        return totalVoltage < packUnderVoltSet
    }

    /**
     * 电池过温（取 MOS 与全部探头的**最高温**，按充放电方向选阈值）。
     *
     * ⚠️ 只看**放电**方向：充电时 BMS 通常由充电器的 CV 阶段限温，
     * 阈值口径与放电不同，所以充电过温只用硬件位。
     */
    fun isOverTemp(): Boolean? {
        val th = if (totalCurrent < 0f) overTempCharge else overTempDischarge
        if (th <= 0f) return null
        val maxT = maxMeasuredTemp()
        if (maxT == null) return null
        return maxT > th
    }

    /** MOS 过温：`MOS 温度 > MOS 过温阈值` */
    fun isMosOverTemp(): Boolean? {
        if (mosOverTemp <= 0f) return null
        val t = mosTemp ?: return null
        return t > mosOverTemp
    }

    /** 电池低温（取**最低温**，按充放电方向选阈值） */
    fun isUnderTemp(): Boolean? {
        val th = if (totalCurrent < 0f) underTempCharge else underTempDischarge
        if (th <= 0f) return null
        val minT = minMeasuredTemp()
        if (minT == null) return null
        return minT < th
    }

    /** 所有已接探头的最高温（℃）；无探头时返回 null */
    fun maxMeasuredTemp(): Float? =
        listOfNotNull(mosTemp, temp1, temp2, temp3).maxOrNull()

    /** 所有已接探头的最低温（℃）；无探头时返回 null */
    fun minMeasuredTemp(): Float? =
        listOfNotNull(mosTemp, temp1, temp2, temp3).minOrNull()

    /**
     * ⭐⭐ **告警诊断报告** —— 每一类告警的「实测值 / 阈值」并排（等宽对齐）。
     *
     * ## 存在的理由
     * 电压类曾经出现「SOC 100%、电流 0、最高单体 4.172V 却弹单体过压」。
     * 光看「弹了哪些告警」无法判断是硬件位错了还是阈值读错了。
     * 报告把两列并排，**一眼就能定位是哪一列出问题**。
     *
     * ## 排版
     * 等宽字体下按**显示宽度**对齐（中文算 2 格），三列全部对齐到各自最宽值。
     * 曾用 `｜` 分隔，因各行长度不同显得参差不齐，已改为空格补齐。
     */
fun alarmDiagnostics(): List<List<String>> {
        // ⚠️ 原先有「硬件位」一列（硬件位置位/硬件位未置），实测**全是「未置」**，
        // 整列零信息量还占宽，已删除。现在只留三列：告警项 / 实测值 / 阈值。
        data class Row(val name: String, val measured: String, val limit: String)
        val rows = mutableListOf<Row>()
        fun row(name: String, measured: String, threshold: String) {
      rows.add(Row(name, measured, threshold))
        }
   row(
      "单体过压",
            "%.3fV".format(maxCellVolt), "%.3fV".format(overVoltSet),
        )
        row(
"单体欠压",
    "%.3fV".format(minCellVolt), "%.3fV".format(underVoltSet),
  )
 row(
      "总压过压",
"%.2fV".format(totalVoltage), "%.2fV".format(packOverVoltSet),
        )
        row(
       "总压欠压",
            "%.2fV".format(totalVoltage), "%.2fV".format(packUnderVoltSet),
        )
   row(
            "放电过流",
 "%.2fA".format(totalCurrent), "%.1fA".format(overCurrentDischarge),
        )
   row(
"充电过流",
"%.2fA".format(totalCurrent), "%.1fA".format(overCurrentCharge),
        )
        row(
     "短路",
  "%.2fA".format(totalCurrent), "%.1fA".format(shortCircuitCurrent),
    )
        val hi = maxMeasuredTemp()
        row(
     "电池过温",
 if (hi == null) "无探头" else "%.1f℃".format(hi),
            "%.1f℃".format(if (totalCurrent < 0f) overTempCharge else overTempDischarge),
    )
        row(
     "MOS 过温",
      mosTemp?.let { "%.1f℃".format(it) } ?: "无探头",
            "%.1f℃".format(mosOverTemp),
        )
        val lo = minMeasuredTemp()
        row(
            "电池低温",
            if (lo == null) "无探头" else "%.1f℃".format(lo),
            "%.1f℃".format(if (totalCurrent < 0f) underTempCharge else underTempDischarge),
        )

// ⚠️ 只返回**原始单元格数据**，不在这里拼字符串。
        // 对齐由 UI 层的隐形表格控件（DiagTable）按列宽保证，
        // 见 DiagActivity.fillTable()。这里补空格或画线都会因字体非严格等宽而失效。
 return rows.map { listOf(it.name, it.measured, it.limit) }
    }

    /**
     * ⭐ **协议偏移自检锚点** —— 量纲明确的字段，读数合理就证明偏移没错位。
     *
     * ## 方法论（踩坑最多的一条）
     * 某字段读出来明显离谱（0、几万、几百伏），基本是**偏移错位或读错帧**，
     * 而不是设备故障。均衡截止 3.9V、压差阈值 20mV 这类是**量纲明确的好锚点**，
     * 优先看它们；全对不上就别急着调 UI，先回去核对官方代码。
     */
fun protocolAnchors(): List<List<String>> {
        val rows = mutableListOf<Pair<String, String>>()
  fun row(label: String, value: String) { rows.add(label to value) }
        if (balanceCutoffVolt > 0f) row("均衡截止电压", "%.3fV".format(balanceCutoffVolt))
     if (balanceDiffThreshold > 0f) row("均衡压差阈值", "%.1fmV".format(balanceDiffThreshold))
      if (overVoltSet > 0f) row("单体过压阈值", "%.0fmV".format(overVoltSet * 1000f))
        if (underVoltSet > 0f) row("单体欠压阈值", "%.0fmV".format(underVoltSet * 1000f))
  if (overVoltRecover > 0f) row("单体过压恢复", "%.0fmV".format(overVoltRecover * 1000f))
        if (underVoltRecover > 0f) row("单体欠压恢复", "%.0fmV".format(underVoltRecover * 1000f))
     if (packOverVoltSet > 0f) row("总压过压阈值", "%.2fV".format(packOverVoltSet))
        if (packOverVoltRecover > 0f) row("总压过压恢复", "%.2fV".format(packOverVoltRecover))
     if (packUnderVoltSet > 0f) row("总压欠压阈值", "%.2fV".format(packUnderVoltSet))
        if (overCurrentCharge > 0f) row("充电过流阈值", "%.1fA".format(overCurrentCharge))
 if (overCurrentDischarge > 0f) row("放电过流阈值", "%.1fA".format(overCurrentDischarge))
        if (overCurrentDischarge2 > 0f) row("放电过流二级", "%.1fA".format(overCurrentDischarge2))
        if (shortCircuitCurrent > 0f) row("短路电流", "%.0fA".format(shortCircuitCurrent))
        if (overTempCharge > 0f) row("充电过温阈值", "%.0f℃".format(overTempCharge))
        if (underTempCharge > 0f) row("充电低温阈值", "%.0f℃".format(underTempCharge))
  if (overTempDischarge > 0f) row("放电过温阈值", "%.0f℃".format(overTempDischarge))
      if (underTempDischarge > 0f) row("放电低温阈值", "%.0f℃".format(underTempDischarge))
      if (mosOverTemp > 0f) row("MOS 过温阈值", "%.0f℃".format(mosOverTemp))
// ⚠️ 只返回原始两列数据，对齐交给 UI 层隐形表格（见 DiagActivity.fillTable）
        return rows.map { listOf(it.first, it.second) }
    }

/**
     * ⭐ 设备身份与通信参数。
     *
     * 只列**本机真实读到**的项，读不到的一律不出现 ——
     * 界面是给用户看自己这块板的，不是对照表，不该出现任何第三方参照值。
     */
fun deviceIdentity(): List<List<String>> {
     val rows = mutableListOf<Pair<String, String>>()
        fun row(label: String, value: String) { rows.add(label to value) }
        if (productName.isNotEmpty()) row("型号", productName)
   if (firmwareVersion != "--") row("版本", firmwareVersion)
   if (serialNumber.isNotEmpty()) row("SN", serialNumber)
   if (batteryType >= 0) row("电池类型", batteryTypeName)
   if (maxCellNumSupported > 0) row("最大串数", "$maxCellNumSupported")
        if (tempProbeCount > 0) row("温度探头", "$tempProbeCount 路")
        if (sleepTimeSec > 0) row("休眠时间", "${sleepTimeSec}S")
    return rows.map { listOf(it.first, it.second) }
    }

    /** 协议类型：255/0 视为「无」 */
    private fun fmtProto(v: Int): String =
        if (v == 0 || v == 255) "无" else "$v"
}
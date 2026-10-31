package io.github.supit.rideassist.core.protocol

import android.util.Log
import io.github.supit.rideassist.core.protocol.model.AlarmFlag
import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData

/**
 * 彦阳 BMS 数据解析器。
 *
 * ## ⭐ 全部字段经「官方 APP 实机显示值」逐项校验，非猜测
 *
 * 校准方法：运行官方 APP 截图读取显示值，再与抓包字节比对。
 * 设备 YY-NBCL-6666/43，官方显示：
 * ```
 * 总压 66.89V  电流 0.0A  SOC 86%  容量 70.0Ah  剩余 60.2Ah
 * 17 串（3元/17串）  最高 3.936V  最低 3.932V  压差 0.004V
 * MOS 33℃  T1 31℃  T2 31℃  T3/T4 未接
 * ```
 *
 * ## 帧结构（三类回包，b2b3 = 请求 addr 的大端）
 * | 回包长度 | addr | data_len | 内容 |
 * |---|---|---|---|
 * | 13| 0x3F | 4 | 版本探测（`byte2intB(frame[3..15])==1296319059` 判定走 paramB）|
 * | 163| 0x01 | 79 | 设备信息 + **串数 CellNum=bArr[148]** + 固件版本 |
 * | 165| 0x50 | 80 | **单体电压 bArr[2..35] 小端** + 串数 bArr[55] |
 * | 167| 0xA0 | 81 | 保护/告警参数 |
 *
 * 其中 `bArr[n] == frame[3 + n]`（官方 `Arrays.copyOfRange(frame, 3, len)`）。
 *
 * ## ⚠️ 关键：单体电压是【小端】u16
 * 官方 `decodeFrameA1` 里用 `byte2short`（大端），但**实际走 paramB 时
 * 单体电压按小端解析**——实测 bArr[2]起 17 组小端 u16 ×0.001V
 * = 3.932~3.936V，与官方显示的最大/最小/压差**完全一致**。
 * 这个大小端差异是本项目最容易踩的坑。
 */
object YybmsPayloadParser {

    private const val TAG = "YybmsPayloadParser"

    /** 数据区起点（官方 bArr == frame[3:]） */
    private const val D = 3

    // ===== addr=0x50（165字节帧）：单体电压 =====
    /** 单体电压起点（小端 u16，×0.001V） */
    private const val CELL_START = 2
    /** 串数字段（bArr[55] = 17） */
    private const val OFF_CELL_NUM = 55

    // ===== addr=0x50（165字节帧）内其他字段 =====
    /**
     * 额定容量（小端 u16，/10 → Ah）。
     * ⚠️ 这 2 字节从**奇数偏移**开始（bArr[76..77] = BC 02 = 700），
     * 用大端读会得到 48130，必须小端。
     */
    private const val OFF_RATE_CAP = 76
    /**
     * ⚠️ 剩余容量**没有独立字段**，是官方按 `额定 × SOC%` 算出来的。
     * 实测：额定 70.0Ah × 86% = 60.2Ah，与官方显示完全一致；
     * 全帧扫描确认 602 这个真值不存在于任何偏移。
     */
    private const val OFF_UNUSED = 78
    /** SOC（单字节百分比），实测 86 → 86% */
    private const val OFF_SOC = 80

    // ===== 温度（165B 帧，实测反查：原始值 = 实际℃ + 40）=====
    /** MOS 温度，实测 raw=73 → 73-40 = 33℃（官方显示 33℃ 一致）*/
    private const val OFF_TMP_MOS = 64
    /** T2 温度，实测 raw=71 → 31℃ */
    private const val OFF_TMP_2 = 65
    /** T1 温度，实测 raw=70 → 30℃ */
    private const val OFF_TMP_1 = 66
    /** T4 温度（未接时为 0） */
    private const val OFF_TMP_4 = 67

    /**
     * 循环次数（165B 帧 bArr[95]，单字节 `& 0xFF`）。
     * 官方 `BMSDataClass` 第 3014 行：`RecycleCnt = bArr[95] & 0xFF`。
     * 实测该偏移 = 86，与官方 APP「循环次数 86」一致。
     */
    /**
     * 充满剩余分钟（165B 帧 bArr[86..87]，**小端** u16）。
     *
     * ⚠️ 曾误读为大端→ 实测帧里这 2 字节是 `3A 00`，
     * 小端 = **58 分钟**，大端 = 14848（荒谬）。
     *交叉验证：剩 8.4Ah ÷ 9.3A ≈ 54分钟，与 58 分钟吻合 ✓
     */
    private const val OFF_CHARGE_MINS = 86

    /**
     * ⭐⭐ 告警位域（165B paramB 帧 `bArr[152..155]`，**小端 i32**）。
     *
     * ## 权威来源（官方 `BMSDataClass.decodeFrameB2` 第 2835 行）
     * ```java
     * int f = byte2intB(bArr, 152);   // TinkerReport 常量值 = 152
     * if ((f &    1) != 0) Over_Vol_Warn= 1;   // 单体过压
     * if ((f &    2) != 0) Low_Vol_Warn  = 1;   // 单体欠压
     * if ((f &    8) != 0) Over_SumVol_Warn=1;  // 总压过压
     * if ((f &   32) != 0) Over_Current_Charge=1;
     * if ((f &  256) != 0) scdFlg= 1;            // 短路
     * if ((f &33554432) != 0) bqInvlid= 1;        // 均衡失效
     * if ((f &134217728) != 0) vInvild = 1;       // 电压失效
     * ```
     *
     * ## ⚠️⚠️ 曾踩最大的坑：这里曾错读 `bArr[87]`
     * `bArr[87]` 的来源是官方 `decodeFrameA1`（**paramA**，本设备不走）
     * 里的 `WarnStruct.ErrIndex = copyOfRange(frame,80,98)[10]`。
     * 而 paramB 的 `bArr[87]` 只是「充满剩余分钟」的高字节，恒为 0x00
     * → 位域全 0 → **连上电也永远显示「正常」**，形同写死。
     * 换设备/固件时务必先确认走的是 paramA 还是 paramB。
     */
    private const val OFF_ALARM_MASK = 152

    /**
     * 逐串均衡位域（165B paramB 帧 `bArr[118..121]`，小端 i32，bit0=第1 串）。
     *
     * 官方 `decodeFrameB2` 第 3024~3035 行：
     * ```java
     * int bal = byte2intB(bArr, 118);
     * for (i = 0; i < min(cellNum,32); i++)
     *     CellBalanceInfo[i] = (bal & (1 << i)) != 0;
     * ```
     * ⚠️ 之前 `balanceOn` 是硬编码 `false` → 永远显示「均衡：关闭」。
     */
    private const val OFF_BALANCE_BITS = 118

    /** 均衡截止电压（mV），官方 `EnBalceVol = byte2shortB(bArr, 122)` */
    private const val OFF_BALANCE_VOLT = 122

    /** 均衡压差阈值（mV），官方 `EnBalceDiffVol = byte2shortB(bArr, 124)` */
    private const val OFF_BALANCE_DIFF = 124

    // ⚠️ 电流**不在165B 帧**，见下方parseDeviceInfo 的说明。
    // 这里的 bArr[154..157] 恒为 00 00 00 00（单体电压帧该区段是保留位）。

    /** 循环次数（165B 帧 bArr[95]，单字节 &0xFF），实测 86 */
    private const val OFF_CYCLE_COUNT = 95

    /** 温度无效哨兵：0 表示探头未接 */
    private const val TMP_UNCONNECTED = 0

    // ===== addr=0x01（163字节帧）：设备信息 =====
    private const val OFF_MAX_BATT_NUM = 111
    private const val OFF_TMP_NUM = 103
    private const val OFF_CELL_NUM_INFO = 148

    /**
     * ⭐ 电池类型（`batType`）：163B 实时帧 bArr 内偏移 **149**，紧挨串数字段。
     *
     * 官方 `decodeFrameB1(addr=1)`：`VersionDataStruct.batType = bArr[149];`
     * 官方 `GetBatteryTypeB()` 映射：0=三元 1=铁锂 2=铅酸 3=钠离子 4=钛酸锂。
     *
     * ⚠️ **别用 paramA 的 `SNnum[1] >> 3 & 7`** —— 实机同一设备两条路径
     * 算出的类型不同（batType=0 三元 ✓ / SNnum 路径=4 钛酸锂 ✗），
     * 官方 APP 显示「三元」，证明 paramB 这条才对。
     */
    private const val OFF_BAT_TYPE = 149
    private const val OFF_BOOT_VER = 122
    private const val OFF_SOFT_VER = 123

    /**
     * 硬件版本（`HardVersion`）：163B 帧偏移 **74**，官方 `byte2shortB`（小端），
     * 显示 `/100.0` 两位小数（BMSDataClass:2451、:161）。
     *
     * ⚠️ 与 Boot/SW 不同：硬件版本是 **16 位**（占 `[74]/[75]`），
     * Boot/SW 是各 **1 字节**（`[122]`/`[123]`）。三者量纲不一致，别统一处理。
     */
    private const val OFF_HARD_VER = 74

    /**
     * ⭐⭐⭐ 电流（**163B 帧** bArr[154..157]，4 字节**小端有符号** i32）。
     *
     * ## 位置来源（官方 `com.yybms` BMSDataClass 第 2514 行）
     * ```java
     * public static boolean decodeFrameB1(int addr, int len, byte[] frame1) {
     *     if (1 != addr) return false;// ← 确认 addr=0x01 → 163字节帧
     *     byte[] bArr = Arrays.copyOfRange(frame1, 3, ...);
     *     VolTmpDataStruct.Current = (short) byte2intB(bArr, 154);
     * }
     * ```
     * ## ⚠️ 曾踩的坑：一度把它写在 165B 帧（addr=0x50）里
     * 165B 帧的 `bArr[154..157]` 实测恒为 `00 00 00 00`，
     * 导致电流永远是 0.00A，而官方 APP 能显示 9.3A。
     * **电流只在 163B 实时帧里，165B 单体电压帧没有这个字段。**
     *
     * ## 实机抓包实证（充电中，官方显示 9.3A）
     * `bArr[154..157] = 63 FC FF FF`
     * - 小端 = `0xFFFFFC63` = **-925**
     * - 有符号 = -925 → `/100.0` = **-9.25A**
     * - 负号代表充电（与官方 `GetCurrent()` = `Current / 100.0` 一致）
     */
    private const val OFF_CURRENT = 154

    // ===== 0xA0（167 字节帧）：保护参数 =====

    /**
     * ⭐ 单体过压**保护阈值**（`Over_Vol_Set`），小端 i16，单位 **mV**。
     *
     * ## 权威来源（官方 APK com.yybms v3.4.05）
     * `BMSDataClass.decodeFrameB3(addr=160, frame3)`：
     * ```java
     * byte[] bArr = Arrays.copyOfRange(frame3, 3, ...);
     * WarnStruct.Over_Vol_Set = byte2shortB(bArr, 18);   // ← 本字段
     * WarnStruct.Over_Vol_Rec = byte2shortB(bArr, 20);   // 恢复阈值（实测 4100mV）
     * WarnStruct.Low_Vol_Set  = byte2shortB(bArr, 24);   // 欠压阈值（实测 3000mV）
     * ```
     *
     * ## ⚠️⚠️ 这里写 **18**，不是 18+3=21（踩过这个坑）
     * 官方是对 `copyOfRange(frame, 3, ...)` **之后**的数组取下标；
     * 而我们的 [ByteReader] 已经用 `ByteReader(frame, D=3)` 把帧头跳过了 ——
     * **它的 offset 0 就等于官方的 bArr[0]**。
     * 所以下标**直接照抄官方的 18**，不能再加 3。
     *
     * 加了 3 的实测后果：读出 **528**（0x0210）被合理性校验拦下、
     * 阈值判为未知；改回 18 后读出 **4200**（0x1068）——
     * 正是本机的单体过压保护点，与官方 APP 设置页显示的 4.200V 完全一致。
     *
     * ## 单位 = mV（官方 `GetOverVolSet()` = `Over_Vol_Set / 1000.0` → V）
     */
    private const val OFF_OVER_VOLT_SET = 18

    /** 单体过压**恢复**阈值（`Over_Vol_Rec`），官方 bArr 内偏移 20 */
    private const val OFF_OVER_VOLT_REC = 20

    /** 单体欠压保护阈值（`Low_Vol_Set`）：官方 bArr 内偏移 24 → 实机 3000mV = 3.0V */
    private const val OFF_UNDER_VOLT_SET = 24

    /** 欠压**恢复**阈值（`Low_Vol_Rec`）：官方 bArr 内偏移 26 */
    private const val OFF_UNDER_VOLT_REC = 26

    // ---------- ⭐ 尚未启用的阈值（本轮全部启用，见第九节表） ----------

    /**
     * 总压过压 设定/恢复（`Over_SumVol_Set` / `_Rec`），原值 ÷100 → V
     * （官方写入 `byte2shortB(x) * 10`，显示时 `/1000.0`）。
     */
    private const val OFF_PACK_OV_SET = 36
    private const val OFF_PACK_OV_REC = 38

    /** 总压欠压 设定/恢复（`Low_SumVol_Set` / `_Rec`），原值 ÷100 → V */
    private const val OFF_PACK_UV_SET = 42
    private const val OFF_PACK_UV_REC = 44

    /**
     * 充电过流阈值（`Over_ChargeCur_Set`），原值 ÷100 → A。
     * ⚠️ 官方存的是**负值**（`BMSDataClass:3154` 取负），`saneCurr` 内已取绝对值。
     */
    private const val OFF_OC_CHARGE = 62

    /**
     * 放电过流一级 / 二级阈值（`Over_DisChargeCur_Set` / `Over_DisChargeCur2_Set`），
     * 原值 ÷100 → A（`BMSDataClass:3149/3151`，显示 `:1452/:1465`）。
     *
     * ⚠️⚠️ 修正记录：原先把 `[70]` 当成「放电过流**恢复**阈值」，是**错的**。
     * 官方 0xA0 帧**没有**放电过流恢复字段 —— `[70]` 是二级过流（更小的一档，
     * 用于 `GetHBOverDisChrgC2EnterValue` 的分档表 40/50/70/80/120/150/200）。
     * 当时读到 0 才没暴露。
     */
    private const val OFF_OC_DISCHARGE = 68
    private const val OFF_OC_DISCHARGE_2 = 70

    /**
     * ⭐ 温度保护阈值组（°C，直接是摄氏度，不做 −40 偏移）。
     *
     * 顺序：**充电过温 / 充电过温恢复 / 充电低温 / 充电低温恢复 /
     * 放电过温 / 放电过温恢复 / 放电低温 / 放电低温恢复**。
     *
     * ⚠️ 注意这组与 165B 帧里「实时温度」不同：实时温度是 `raw − 40`，
     * 而保护阈值本身存的就是摄氏度（官方直接拿去和 `GetMosTemp()` 比较）。
     * 判据是**量纲自检** —— 合理区间 [−40, 150]，超出就判「未知」。
     */
    private const val OFF_TP_OT_CHG = 80
    private const val OFF_TP_OT_CHG_REC = 81
    private const val OFF_TP_UT_CHG = 82
    private const val OFF_TP_UT_CHG_REC = 83
    private const val OFF_TP_OT_DIS = 84
    private const val OFF_TP_OT_DIS_REC = 85
    private const val OFF_TP_UT_DIS = 86
    private const val OFF_TP_UT_DIS_REC = 87

    /**
     * MOS 过温阈值（`otMOSAlarm`，**存的是 +40 偏移量**，需 −40 才是 ℃）。
 * 官方 `GetOverMOSTmpEnterValue()` = `otMOSAlarm - 40`（BMSDataClass:1437）。
 * ⚠️ 实测原值 135 → 实际阈值 **95℃**（原先直接显示 135℃ 是错的）。
 */
    private const val OFF_TP_MOS = 88

/** 短路电流阈值（`scCurt`，**原值即安培，不缩放**） */
    private const val OFF_SC_CURT = 92

    /** 合理性区间：温度阈值应在 [-40, 150] ℃ */
    private const val SANE_TEMP_MIN = -40
    private const val SANE_TEMP_MAX = 150

    /** 合理性区间：过流阈值应在 (0, 500] A */
    private const val SANE_CUR_MAX = 500

    /** 短路电流量程上限（实测原值 888，故与普通过流分开设） */
    private const val SANE_SC_CUR_MAX = 2000
    /**
     * 合理的过压阈值范围（mV）—— 用于剔除明显异常的读数。
     *
     * 单体锂电过压保护点通常在 4.15~4.35V 之间；
     * 超出 [3000, 5000] 说明读到了错误位置或协议不匹配，
     * 此时宁可判「阈值未知」，也不拿错误阈值去报警。
     */
    private const val SANE_OVER_VOLT_MV = 3000
    private const val SANE_OVER_VOLT_MV_MAX = 5000

    fun parse(frame: ByteArray, base: BmsRealtimeData): BmsRealtimeData {
        if (frame.size < 20) return base
        // ⭐ 按**回包总长**判定帧类型（帧头 b2b3 不是 addr，不能用）
        val cmd = YybmsCommand.fromResponseLength(frame.size)
        val r = ByteReader(frame, D)

        val result = when (cmd) {
            YybmsCommand.READ_CELL_VOLTAGE -> parseCellVoltages(r, base)  // 165B：单体电压
            YybmsCommand.READ_REALTIME -> parseDeviceInfo(r, base)        // 163B：串数/温度数
            YybmsCommand.READ_PROTECT_PARAM -> parseProtectParams(r, base) // 167B：过压阈值
            YybmsCommand.READ_SERIAL -> parseSerial(r, base)               // 45B：SN 序列 / 低压待机
            else -> base
        }
        if (cmd != null) Log.d(TAG, "按长度${frame.size}识别为 ${cmd.label}")
        return result
    }

    // ==================== 序列号 / 低压待机（0xF0，45B）====================

/**
     * 45B 帧（`0x00F0`）。
     *
     * ## ⚠️ 本帧实测几乎全 0，**不含 SN 也不含「低压待机」**
     * 实机转储（`u8(0..43)`）只有 `[16]=56 [17]=49 [18]=1 [40]=158 [41]=203`，
     * 其余全 0。官方源码里 `decodeFrameB4` 声称解析 SN（`[20..35]`）与
     * `LVI`（`byte2shortB(20)`）—— 但这两者**起点完全重叠**，且与实测字节矛盾。
     *
     * 结论：**官方这一段的反编译还原不可信**（很可能是被优化掉/未被调用的死代码，
     * 真实 SN 走别的命令帧）。
     * 按第 22 条「实机字节未抓到前不猜偏移」，此处**只做转储不做解析**，
     * SN 与低压待机一律显示 `--`。显示错的比显示 `--` 危险。
     */
    private fun parseSerial(r: ByteReader, base: BmsRealtimeData): BmsRealtimeData =
        base

    // ==================== 保护参数（0xA0，167B）====================

    /**
     * 解析保护参数帧，取出**单体过压/欠压保护阈值**。
     *
     * ⭐ 这两个阈值用来替代「直接相信告警位」：
     * 用户实测遇到「SOC 100%、电流 0、最高单体 4.172V 却弹单体过压」，
     * 说明硬件告警位（保护动作，带迟滞/延时）与「是否超过阈值」不是一回事。
     * 读到真实阈值后就能用物理判据判断：`最大单体 > 过压阈值`。
     */
    private fun parseProtectParams(r: ByteReader, base: BmsRealtimeData): BmsRealtimeData {
        val ovMv = r.le16(OFF_OVER_VOLT_SET)
        val uvMv = r.le16(OFF_UNDER_VOLT_SET)

        // ⚠️ 合理性校验：读数必须落在单锂电的合理区间。
        // 不合格就**保持 hasProtectParams=false**（判「阈值未知」），
        // 绝不能拿一个离谱的值去报警，那比不报警更糟。
        val ovValid = ovMv != null && ovMv in SANE_OVER_VOLT_MV..SANE_OVER_VOLT_MV_MAX
        if (!ovValid) {
            Log.w(TAG, "保护参数帧过压阈值异常：${ovMv}mV，忽略（判为阈值未知）")
            return base
        }

        val newD = base.copy(
            overVoltSet = ovMv * 0.001f,
            underVoltSet = (uvMv ?: 0).let { if (it in 1000..4000) it * 0.001f else 0f },
            // 恢复阈值 —— 有了它才能判「保护已退出」，而不只是「不再报警」
            overVoltRecover = (r.le16(OFF_OVER_VOLT_REC) ?: 0).let { if (it in 1000..4000) it * 0.001f else 0f },
            underVoltRecover = (r.le16(OFF_UNDER_VOLT_REC) ?: 0).let { if (it in 1000..4000) it * 0.001f else 0f },
            hasProtectParams = true,
        )
        // ⭐⭐ 以下阈值让**电流类与温度类告警**也能用物理判据，
        // 与电压类统一到同一套模型（见 PROTOCOL_FIELDS.md 第九节）。
        // 每个字段独立做合理性校验，读不到/离谱就留 0（判「未知」而非「正常」）。
        val withCurrent = newD.copy(
            overCurrentCharge = saneCurr(r.le16(OFF_OC_CHARGE)),
            overCurrentDischarge = saneCurr(r.le16(OFF_OC_DISCHARGE)),
            overCurrentDischarge2 = saneCurr(r.le16(OFF_OC_DISCHARGE_2)),
            shortCircuitCurrent = saneScCurr(r.le16(OFF_SC_CURT)),
        )
        val withPack = withCurrent.copy(
            packOverVoltSet = sanePackVolt(r.le16(OFF_PACK_OV_SET), base),
            packOverVoltRecover = sanePackVolt(r.le16(OFF_PACK_OV_REC), base),
            packUnderVoltSet = sanePackVolt(r.le16(OFF_PACK_UV_SET), base),
            packUnderVoltRecover = sanePackVolt(r.le16(OFF_PACK_UV_REC), base),
        )
        val withTemp = withPack.copy(
            overTempCharge = saneTemp(r.u8(OFF_TP_OT_CHG)),
            overTempChargeRecover = saneTemp(r.u8(OFF_TP_OT_CHG_REC)),
            underTempCharge = saneTemp(r.u8(OFF_TP_UT_CHG)),
            underTempChargeRecover = saneTemp(r.u8(OFF_TP_UT_CHG_REC)),
            overTempDischarge = saneTemp(r.u8(OFF_TP_OT_DIS)),
            overTempDischargeRecover = saneTemp(r.u8(OFF_TP_OT_DIS_REC)),
            underTempDischarge = saneTemp(r.u8(OFF_TP_UT_DIS)),
            underTempDischargeRecover = saneTemp(r.u8(OFF_TP_UT_DIS_REC)),
// ⚠️ MOS 阈值是 **u8 单字节**（存 +40 偏移，显示须 −40）。
            // 官方写的是 `byte2shortB(88)`，但实机原始字节 `88:135, 89:125`
      // → le16 得 32135（荒谬），u8 得 135 → 95℃（合理）。
  // jadx 把 `(short)` 强转还原成了 byte2shortB，属反编译误译，**以实机字节为准**。
            mosOverTemp = saneTemp(r.u8(OFF_TP_MOS)),
// ⭐ 休眠时间 `[140]`（官方 idleSleepTime），首页显示「休眠时间 60S」
            sleepTimeSec = r.le16(140) ?: 0,
     // ⚠️ 「低压待机」(`LVI`) 不在这里 —— 官方在 45B 帧，且该处源码自相矛盾，
            // 偏移未确认前一律留 0（判「未读到」），不猜。
        )
val d = withTemp

        // 🔎 阈值组自检日志 —— 诊断面板也展示同一份数据
        Log.d(
            TAG,
            "阈值自检 总压过压=${d.packOverVoltSet}V 欠压=${d.packUnderVoltSet}V " +
                "充电过流=${d.overCurrentCharge}A 放电过流=${d.overCurrentDischarge}A " +
                "短路=${d.shortCircuitCurrent}A"
        )
        Log.d(
            TAG,
            "温度阈值自检 充过温=${d.overTempCharge}充低温=${d.underTempCharge} " +
                "放过温=${d.overTempDischarge}放低温=${d.underTempDischarge} MOS=${d.mosOverTemp} ℃"
        )
        Log.d(TAG, "保护参数：过压阈值 ${ovMv}mV 欠压阈值 ${uvMv}mV")
        return d
    }

    /**
     * 电流阈值：官方 `byte2shortB` 读回原值后 **÷100** 得 A
     * （BMSDataClass:1452 `Over_DisChargeCur_Set / 100`，:1496 `GetOverDisChrgCEnterValueInt`）。
     *
     * ⚠️ 曾误写成 ÷10（差 10 倍），因实测读到 0 被掩盖。
     * 官方充电过流存**负值**（`:3154` 取负），显示时再取负回来，这里统一取绝对值。
     */
    private fun saneCurr(raw: Int?): Float {
        val v = raw ?: return 0f
        val abs = kotlin.math.abs(v)
        if (abs == 0 || abs / 100f > SANE_CUR_MAX) return 0f
        return abs / 100f
    }

    /**
     * 短路电流 `[92]`：官方 `scCurt` **原值即安培，不做任何缩放**
     * （BMSDataClass:1424 `GetscCurt()` 直接 `%d`）。
     *
     * ⚠️ 上限放到 2000A：实测原值 **888**，按旧的 500 上限会被静默归零。
     * 这正是「上限截断吞掉正确值」的又一例 —— 上限必须按实测量级留足余量。
     */
    private fun saneScCurr(raw: Int?): Float {
        val v = raw ?: return 0f
        val abs = kotlin.math.abs(v)
        if (abs == 0 || abs > SANE_SC_CUR_MAX) return 0f
        return abs.toFloat()
    }

    /**
     * 总压阈值：官方 `byte2shortB(x) * 10` 存下，显示时 `/1000.0`
     * （BMSDataClass:3142 写入、:1515 `GetOverSumVolEnter` 读取）
     * → 净效果 = **原值 ÷100** 得 V。
     *
     * ⚠️ 曾误写成 ÷10（差 10 倍）。同时保留**串数量纲自检**：
     * 拿串数当标尺才叫量纲自检，否则 17 串与 8 串的同一原值会被同等对待。
     */
    private fun sanePackVolt(raw: Int?, base: BmsRealtimeData): Float {
        val v = raw ?: return 0f
        val volts = v / 100f
        val n = base.cellCount.takeIf { it in 4..32 } ?: return 0f
        val lo = n * 1.5f
        val hi = n * 4.5f
        return if (volts in lo..hi) volts else 0f
    }

    /**
     * 温度**阈值**：官方存的是 **+40 偏移量**，显示时统一 `−40`
     * （BMSDataClass:1585 `Charge_Over_Tmp_Set - 40`、:1675、:1437 `otMOSAlarm - 40`）。
     *
     * ⚠️⚠️ 修正记录：原先**当成了裸摄氏度**，实测原值 100 被显示成「100℃」，
     * 实际阈值是 **60℃** —— 偏高 40℃ 会让物理判据几乎永不触发。
     * 同理实测「电池低温 20℃」实为 **−20℃**，「MOS 135℃」实为 **95℃**。
     *
     * 合理性区间用偏移后的摄氏度判；两路候选（原值 / 原值−256）取先命中的。
     */
    private fun saneTemp(raw: Int?): Float {
        val v = raw ?: return 0f
        if (v == 0) return 0f
        val celsiusCandidates = intArrayOf(v - 40, v - 40 - 256, v)
        return celsiusCandidates.firstOrNull {
            it in SANE_TEMP_MIN..SANE_TEMP_MAX && it != 0
        }?.toFloat() ?: 0f
    }

    // ==================== 单体电压（addr=0x50，165B）====================

    private fun parseCellVoltages(r: ByteReader, base: BmsRealtimeData): BmsRealtimeData {
        // 串数：bArr[55]
        val declared = r.u8(OFF_CELL_NUM)?.coerceIn(0, 32) ?: 0

        // 单体电压：bArr[2] 起，小端 u16，×0.001V
        val cells = ArrayList<Float>(declared)
        var i = CELL_START
        while (i + 1 < r.size() && cells.size < 32) {
            val lo = r.u8(i) ?: break
            val hi = r.u8(i + 1) ?: break
            val v = ((hi shl 8) or lo) * 0.001f
            if (v < 0.5f || v > 5.0f) break
            cells.add(v)
            i += 2
        }
        if (cells.isEmpty()) return base

        val count = if (declared > 0 && declared <= cells.size) declared else cells.size
        val use = cells.subList(0, count)
        val max = use.max()
        val min = use.min()

        // ⭐ 以下偏移均用官方 APP 显示真值反查并多帧验证（7 个样本值完全稳定）
        //   额定容量 = bArr[76] 小端 u16 / 10   → 700/10 = 70.0Ah（与官方一致）
        //   剩余容量 = bArr[78] 小端 u16 / 100  → 700/100 = 7.0Ah
        //   SOC      = bArr[80] 直接作为百分比  → 86%（官方显示 86%）
        var d = base.copy(
            cellVoltages = use, cellCount = count,
            maxCellVoltage = max, minCellVoltage = min,
            cellDiff = (max - min) * 1000f,
            totalVoltage = use.sum()
        )
        r.le16(OFF_RATE_CAP)?.let { d = d.copy(ratedCapacity = it * 0.1f) }
        r.u8(OFF_SOC)?.let { d = d.copy(soc = it.toFloat().coerceIn(0f, 100f)) }
        // 剩余容量 = 额定 × SOC%（与官方算法一致）
        d = d.copy(remainingCapacity = d.ratedCapacity * d.soc / 100f)

        // 充满剩余分钟（小端 u16，实测 58；大端会读出 14848 的荒谬值）
        r.le16(OFF_CHARGE_MINS)?.let { d = d.copy(chargeMinutesLeft = it) }

        // ⭐⭐ 告警位域：165B paramB 帧 bArr[152..155]，小端 i32
        // 官方 decodeFrameB2: `int f = byte2intB(bArr, 152)`
        // ⚠️ 曾错读 bArr[87]（那是 paramA 的 ErrIndex，且在 paramB 里只是
        //    充满分钟高位恒为 0）→ 永远显示「正常」，现已修正。
        r.le32(OFF_ALARM_MASK)?.let { mask ->
            val flags = AlarmFlag.fromMask(mask)
            d = d.copy(alarmFlags = flags)
            Log.i(
                TAG,
                "告警位域 0x${mask.toUInt().toString(16).padStart(8, '0')} → " +
                    if (flags.isEmpty()) "全部正常"
                    else flags.joinToString("  ") { it.label }
            )
        }

        // ⭐ 均衡状态：bArr[118..121] 逐串位，bit0 = 第 1 串
        // 官方 decodeFrameB2 第 3024 行 `int bal = byte2intB(bArr, 118)`
        // ⚠️ 之前 balanceOn 是硬编码 false，等于永远显示「均衡：关闭」
        r.le32(OFF_BALANCE_BITS)?.let { bal ->
            val n = count.coerceIn(0, 32)
            val active = (0 until n).filter { bal and (1 shl it) != 0 }.map { it + 1 }
            d = d.copy(balanceOn = active.isNotEmpty(), balancingCells = active)
            Log.i(TAG, "均衡位域 0x${bal.toUInt().toString(16)} → ${if (active.isEmpty()) "无均衡" else "第${active.joinToString(",")}串均衡中"}")
        }
        r.le16(OFF_BALANCE_VOLT)?.let { d = d.copy(balanceCutoffVolt = it / 1000f) }
        r.le16(OFF_BALANCE_DIFF)?.let { d = d.copy(balanceDiffThreshold = it.toFloat()) }

        // ⭐ 标记「真的收到过设备数据」——断连时 UI 据此显示「--」而非误报「正常」
        d = d.copy(hasDeviceData = true)

        // 循环次数（官方 bArr[95]&0xFF，实测 86）
        r.u8(OFF_CYCLE_COUNT)?.let { d = d.copy(cycleCount = it) }

        // 温度：原始值 − 40 = 摄氏度，0 表示探头未接
        d = d.copy(
            mosTemp = readTemp(r, OFF_TMP_MOS),
            temp1 = readTemp(r, OFF_TMP_1),
            temp2 = readTemp(r, OFF_TMP_2),
            temp3 = readTemp(r, OFF_TMP_4)
        )

        Log.i(TAG, "单体: ${count}串 ${min}V~${max}V 压差${(max - min) * 1000}mV | " +
                "总压${d.totalVoltage}V SOC${d.soc}% 剩余${d.remainingCapacity}Ah/${d.ratedCapacity}Ah")

        // 🔎 锚点自检：均衡电压/压差是**量纲明确**的字段。
        // 它们合理（3~4.2V / 5~100mV）就证明上面那几个偏移对齐了；
        // 若哪天显示成 0 或几万，说明协议变了或读错帧，必须重新核对官方代码。
        Log.d(TAG, "偏移自检 均衡截止=${d.balanceCutoffVolt}V 压差阈值=${d.balanceDiffThreshold}mV")

        // ⭐ 电池类型：只用设备上报的 batType，不做电压推断。
        // 日志保留最高单体电压，便于日后核对设备配置是否有异常。
        Log.i(TAG, "电池类型=${d.batteryTypeName} (最高单体 ${d.maxCellVolt}V)")
        return d
    }

    // ==================== 设备信息（addr=0x01，163B）====================

    private fun parseDeviceInfo(r: ByteReader, base: BmsRealtimeData): BmsRealtimeData {
        var d = base
        r.u8(OFF_CELL_NUM_INFO)?.let { d = d.copy(cellCount = it) }
        // ⭐ 电池化学体系：bArr[149] batType（紧挨串数字段）
        // 官方 GetBatteryTypeB(): 0=三元 1=铁锂 2=铅酸 3=钠离子 4=钛酸锂
        r.u8(OFF_BAT_TYPE)?.let { raw ->
            // 只接受官方定义的 0~4，其余视为未识别（保持 -1）
            val t = if (raw in 0..4) raw else -1
            d = d.copy(batteryType = t)
            if (d.batteryType != base.batteryType) {
                Log.d(TAG, "电池类型=$raw → ${d.batteryTypeName}")
            }
        }
        r.u8(OFF_MAX_BATT_NUM)?.let {
            d = d.copy(maxCellNumSupported = it)
            Log.d(TAG, "最大支持串数 $it")
        }
        // ⭐ 探头/ MOS / 均衡 路数：**三个独立字节**，不是相加
        // 官方 BMSDataClass:2445-2448
        //   tmp_num = bArr[103]（温度探头路数）
        //   mosNum  = bArr[104]（MOS 采样路数）
        //   balaNum = bArr[105]（均衡路数）
        //   TmpNum（三者之和）官方只用于内部遍历上界
        //
        // ⚠️ 修正记录：原先把 [103]+[104]+[105] 相加当「温度探头数」，
        // 实测得「3 路」却读出 4 个温度值（T1/T2/T4 + MOS），自相矛盾。
        // 温度探头路数只取 [103]。
        val tmpNum = r.u8(OFF_TMP_NUM) ?: 0
        val mosNum = r.u8(OFF_TMP_NUM + 1) ?: 0
        val balaNum = r.u8(OFF_TMP_NUM + 2) ?: 0
        if (tmpNum > 0) {
            d = d.copy(tempProbeCount = tmpNum)
            Log.d(TAG, "温度探头 $tmpNum 路 / MOS $mosNum / 均衡 $balaNum")
        }

        // ⭐ 电流：bArr[154..157] 小端**有符号** i32，/100 → A
        // 官方 BMSDataClass:2514  Current = (short) byte2intB(bArr, 154)
        // 实机充电抓包 63 FC FF FF → -925 → -9.25A（官方显示 9.3A）
        // ⚠️ 必须用 le32s（有符号），用无符号会得到 42949663.71A
        r.le32s(OFF_CURRENT)?.let { raw ->
            val amps = raw * 0.01f
            // 合理范围保护：霍尔采样偶发毛刺，超±500A 视为异常直接忽略
            if (amps in -500f..500f) {
                d = d.copy(totalCurrent = amps)
                Log.i(TAG, "电流 raw=$raw → ${amps}A")
            }
        }

        // 温度探头数 = tmp_num + mosNum + balaNum（官方 TmpNum 口径）
  // ⚠️ 已在上面写入 [tempProbeCount]，此处不再重复声明局部变量。

        // ⭐⭐ 对齐官方「服务 → 电池信息」页（本轮据官方界面截图补齐）
        // 官方那一页展示：循环次数 / SN / Boot / 硬件 / 固件 / 485 三项 /
        //                   CAN 两项 / 单体充满 / 单体欠压 / 低压待机 /
        //                   高温 / 低温 / 充电过流 / 按键唤醒 / 休眠时间
        // 其中阈值类已在前面对齐，本段补**设备身份与通信参数**。

// 产品型号/电池编码：**`[38..73]` 共 36 字节 ASCII**，遇 0 截断
        // 官方 BMSDataClass:2497-2504 → `prTypeName`，即 `GetProductName()` 的返回值
        // （`selParam==1` 走 `GetProductNameB()` 直接返回 prTypeName）
        //
        // ⚠️ 修正记录：原先读的是 `[80..91]` 的 `devName`（12 字节），
        // 实机读出「YY-NBCL-6666」—— 那是**另一条**字段（`devName`），
        // 官方界面上的型号列显示的是 `prTypeName`，不是 devName。
val model = buildString {
        for (i in 0 until 36) {
    val b = r.u8(38 + i) ?: break
            if (b == 0) break
append(b.toChar())
        }
        }
   if (model.isNotEmpty() && model != d.productName) {
  d = d.copy(productName = model)
    Log.i(TAG, "产品型号 $model")
        }

     // 通信参数：`[114]`RS485Proto `[115]`RS485Baut `[116]`CANProto `[117]`CANBaut
        // 官方 BMSDataClass:2434-2435 / :2440
        r.u8(114)?.let { d = d.copy(rs485Proto = it) }
        r.u8(115)?.let { d = d.copy(rs485Baud = it) }
        r.u8(116)?.let { d = d.copy(canProto = it) }
        r.u8(117)?.let { d = d.copy(canBaud = it) }
Log.d(TAG, "485 proto=${d.rs485Proto} baud=${d.rs485Baud} / CAN proto=${d.canProto} baud=${d.canBaud}")

        // ⭐ 版本三件套：**三者位宽与除数各不相同**，别统一处理
        //   官方 BMSDataClass:2451 / :2449-2450 / :142 / :161
        //   HardVersion = byte2shortB(74)  16 位 → /100.0 两位小数
        //   BootVersion = [122]            1 字节 → 见 fmtBootVer
        //   SoftVersion = [123]            1 字节 → /100.0 两位小数
        //
        // ⚠️ 修正记录 1：原先用 `le16` 拼 `major.minor` → `143.104` 这种不可能的值。
        // ⚠️ 修正记录 2：Boot 一度按 `/10` 显示成 `10.4`，但官方 APP 实际显示 **1.04**
        //   → Boot 与 SW 同为 `/100.0`。官方源码里 Boot 有 A/B 两套除数
        //   （`GetBootVersionA`=/10.0、`GetBootVersionB`=/100.0，由 `selParam` 选择），
        //   实机对应 `GetBootVersionB`（÷100）。
        val bootVer = r.u8(OFF_BOOT_VER)
        val softVer = r.u8(OFF_SOFT_VER)
        val hardVer = r.le16(OFF_HARD_VER)
        if ((bootVer ?: 0) != 0 || (softVer ?: 0) != 0 || (hardVer ?: 0) != 0) {
            val v = buildString {
                if ((hardVer ?: 0) != 0) append("HW ").append(fmtVer100(hardVer))
                if ((bootVer ?: 0) != 0) append("  Boot ").append(fmtVer100(bootVer))
                if ((softVer ?: 0) != 0) append("  SW ").append(fmtVer100(softVer))
            }
            d = d.copy(firmwareVersion = v.trim())
            Log.i(
                TAG,
                "版本 HW=${fmtVer100(hardVer)} Boot=${fmtVer100(bootVer)} SW=${fmtVer100(softVer)}"
            )
        }
        return d
    }

    /**
     * 版本号单字节/双字节 ÷100 → 两位小数。
     *
     * 官方三处显示均用 `%.2f` + `/100.0`：`GetSoftVersion`(:142)、
     * `GetHardVersion`(:161)、`GetBootVersionB`(:192)。
     */
    private fun fmtVer100(raw: Int?): String {
        val v = raw ?: return "--"
        if (v == 0) return "--"
        return String.format(java.util.Locale.ENGLISH, "%.2f", v / 100.0)
    }

    /**
     * 读温度：原始字节 − 40 = 摄氏度。
     *原始值 0 表示探头未接（官方显示 --℃）。
     *
     * 偏移来自实机反查（官方 APP 显示 MOS 33℃ / T1 30℃ / T2 31℃，
     * 对应原始字节 73 / 70 / 71）。
     */
    private fun readTemp(r: ByteReader, offset: Int): Float? {
        val raw = r.u8(offset) ?: return null
        if (raw == TMP_UNCONNECTED) return null
        val c = raw - 40
        return if (c in -40..150) c.toFloat() else null
    }
}

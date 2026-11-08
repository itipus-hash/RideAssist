package io.github.supit.rideassist.core.protocol.model

/**
 * ⭐⭐ **保护板阈值的本地快照**（可持久化）。
 *
 * ## 为什么需要它
 * 用户实测：**充电结束后连上保护板会误报一次「单体过压」**。
 * 原因是连上的头一两秒还没收到 0xA0 保护参数帧，`hasProtectParams=false`，
 * 判定只能照搬保护板硬件告警位 —— 而充电末期硬件位带迟滞，充电结束后
 * 还残留着 bit0，于是「实测 4.145V、阈值未读到」却弹了单体过压。
 *
 * 把阈值落到本机后，第一次连上就能用**上次读到并校验过的阈值**做物理判定，
 * 这个空窗期连同误报一起消失；诊断面板也不会再出现整列空白。
 *
 * ## 编码格式（纯 JVM，不依赖 org.json）
 * `f0,f1,…,f20|savedAtMs`
 *
 * - 字段顺序 = [values] 的顺序，**只能追加、不能重排**（旧缓存按位置解析）
 * - 解析时数量必须等于 [FIELD_COUNT]，否则视为无效缓存（返回 null），
 *   宁可当作"没缓存"也不要拿错位的数据去报警
 */
data class ProtectThresholds(
    val overVoltSet: Float = 0f,
    val underVoltSet: Float = 0f,
    val overVoltRecover: Float = 0f,
    val underVoltRecover: Float = 0f,
    val overCurrentCharge: Float = 0f,
    val overCurrentDischarge: Float = 0f,
    val overCurrentDischarge2: Float = 0f,
    val shortCircuitCurrent: Float = 0f,
    val packOverVoltSet: Float = 0f,
    val packOverVoltRecover: Float = 0f,
    val packUnderVoltSet: Float = 0f,
    val packUnderVoltRecover: Float = 0f,
    val overTempCharge: Float = 0f,
    val overTempChargeRecover: Float = 0f,
    val underTempCharge: Float = 0f,
    val underTempChargeRecover: Float = 0f,
    val overTempDischarge: Float = 0f,
    val overTempDischargeRecover: Float = 0f,
    val underTempDischarge: Float = 0f,
    val underTempDischargeRecover: Float = 0f,
    val mosOverTemp: Float = 0f,
    /** 写入本机的时刻（墙钟 ms），诊断面板用来显示"缓存于何时" */
    val savedAtMs: Long = 0L,
) {

    /** 仅比较阈值本身（忽略保存时间），用于"有变化才写盘" */
    fun differsFrom(other: ProtectThresholds): Boolean =
        !values().contentEquals(other.values())

    /**
     * 把本地阈值注入到实时数据里。
     *
     * ⚠️ 会置 `hasProtectParams = true`（判定层据此走物理判据），
     * 同时置 `protectParamsFromCache = true`，让诊断面板能区分
     *「本次从设备读到的」和「本机缓存的」。
     */
    fun applyTo(d: BmsRealtimeData): BmsRealtimeData = d.copy(
        overVoltSet = overVoltSet,
        underVoltSet = underVoltSet,
        overVoltRecover = overVoltRecover,
        underVoltRecover = underVoltRecover,
        overCurrentCharge = overCurrentCharge,
        overCurrentDischarge = overCurrentDischarge,
        overCurrentDischarge2 = overCurrentDischarge2,
        shortCircuitCurrent = shortCircuitCurrent,
        packOverVoltSet = packOverVoltSet,
        packOverVoltRecover = packOverVoltRecover,
        packUnderVoltSet = packUnderVoltSet,
        packUnderVoltRecover = packUnderVoltRecover,
        overTempCharge = overTempCharge,
        overTempChargeRecover = overTempChargeRecover,
        underTempCharge = underTempCharge,
        underTempChargeRecover = underTempChargeRecover,
        overTempDischarge = overTempDischarge,
        overTempDischargeRecover = overTempDischargeRecover,
        underTempDischarge = underTempDischarge,
        underTempDischargeRecover = underTempDischargeRecover,
        mosOverTemp = mosOverTemp,
        hasProtectParams = true,
        protectParamsFromCache = true,
        protectParamsSavedAtMs = savedAtMs,
    )

    fun values(): FloatArray = floatArrayOf(
        overVoltSet, underVoltSet, overVoltRecover, underVoltRecover,
        overCurrentCharge, overCurrentDischarge, overCurrentDischarge2, shortCircuitCurrent,
        packOverVoltSet, packOverVoltRecover, packUnderVoltSet, packUnderVoltRecover,
        overTempCharge, overTempChargeRecover, underTempCharge, underTempChargeRecover,
        overTempDischarge, overTempDischargeRecover, underTempDischarge, underTempDischargeRecover,
        mosOverTemp,
    )

    fun encode(): String = values().joinToString(",") + "|" + savedAtMs

    companion object {
        /** 编码里的字段个数；**只增不减**，否则旧缓存会被判为无效 */
        const val FIELD_COUNT = 21

        /** 从实时数据里摘出阈值（保存前调用） */
        fun of(d: BmsRealtimeData, savedAtMs: Long): ProtectThresholds = ProtectThresholds(
            overVoltSet = d.overVoltSet,
            underVoltSet = d.underVoltSet,
            overVoltRecover = d.overVoltRecover,
            underVoltRecover = d.underVoltRecover,
            overCurrentCharge = d.overCurrentCharge,
            overCurrentDischarge = d.overCurrentDischarge,
            overCurrentDischarge2 = d.overCurrentDischarge2,
            shortCircuitCurrent = d.shortCircuitCurrent,
            packOverVoltSet = d.packOverVoltSet,
            packOverVoltRecover = d.packOverVoltRecover,
            packUnderVoltSet = d.packUnderVoltSet,
            packUnderVoltRecover = d.packUnderVoltRecover,
            overTempCharge = d.overTempCharge,
            overTempChargeRecover = d.overTempChargeRecover,
            underTempCharge = d.underTempCharge,
            underTempChargeRecover = d.underTempChargeRecover,
            overTempDischarge = d.overTempDischarge,
            overTempDischargeRecover = d.overTempDischargeRecover,
            underTempDischarge = d.underTempDischarge,
            underTempDischargeRecover = d.underTempDischargeRecover,
            mosOverTemp = d.mosOverTemp,
            savedAtMs = savedAtMs,
        )

        /** 解析失败 / 数量不符一律返回 null（当作"没有缓存"） */
        fun decode(raw: String?): ProtectThresholds? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('|')
            val nums = parts.getOrNull(0)?.split(',') ?: return null
            if (nums.size != FIELD_COUNT) return null
            val f = nums.map { it.toFloatOrNull() ?: return null }
            if (f.size != FIELD_COUNT) return null
            val savedAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            return ProtectThresholds(
                overVoltSet = f[0], underVoltSet = f[1],
                overVoltRecover = f[2], underVoltRecover = f[3],
                overCurrentCharge = f[4], overCurrentDischarge = f[5],
                overCurrentDischarge2 = f[6], shortCircuitCurrent = f[7],
                packOverVoltSet = f[8], packOverVoltRecover = f[9],
                packUnderVoltSet = f[10], packUnderVoltRecover = f[11],
                overTempCharge = f[12], overTempChargeRecover = f[13],
                underTempCharge = f[14], underTempChargeRecover = f[15],
                overTempDischarge = f[16], overTempDischargeRecover = f[17],
                underTempDischarge = f[18], underTempDischargeRecover = f[19],
                mosOverTemp = f[20],
                savedAtMs = savedAt,
            )
        }
    }
}

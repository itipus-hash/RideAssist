package com.yanyang.yybms.data.repo

import com.yanyang.yybms.core.ble.BleManager
import com.yanyang.yybms.core.ble.ConnectionState
import com.yanyang.yybms.core.protocol.YybmsCommand
import com.yanyang.yybms.core.protocol.YybmsPayloadParser
import com.yanyang.yybms.core.protocol.model.AlarmFlag
import com.yanyang.yybms.core.protocol.model.BmsRealtimeData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 监控仓库：连接 → 轮询 → 解析 → 聚合 → 对外暴露。
 *
 * 只读 APP 的中枢，UI 层只读这里的状态流，不接触协议细节。
 */
class MonitorRepository(
    private val bleManager: BleManager,
    private val scope: CoroutineScope
) {

    companion object {
        /** 需求：1 秒刷新一次 */
        const val POLL_INTERVAL_MS = 1_000L

        /**
         * 命令间隔 200ms——与官方 `InfoActivity_YY` 的 `Thread.sleep(200)` 一致。
         * BLE 写入队列有吞吐上限，无间隔连发会导致 `status!=GATT_SUCCESS`。
         */
        const val FRAME_GAP_MS = 200L

        /** 近 5 分钟曲线采样点：300 个 */
        const val TREND_WINDOW_SIZE = 300
    }

    private val _realtime = MutableStateFlow(BmsRealtimeData())
    val realtime: StateFlow<BmsRealtimeData> = _realtime.asStateFlow()

    /** 曲线缓存：SOC / 总压 / 总流 */
    private val _trend = MutableStateFlow(TrendData())
    val trend: StateFlow<TrendData> = _trend.asStateFlow()

    /** 新告警事件（用于弹窗 + 震动） */
    private val _alarmEvents = MutableSharedFlow<AlarmEvent>(extraBufferCapacity = 16)
    val alarmEvents: SharedFlow<AlarmEvent> = _alarmEvents.asSharedFlow()

    val messages: SharedFlow<String> get() = bleManager.messages

    val connectionState: StateFlow<ConnectionState> get() = bleManager.connectionState

    private var pollJob: Job? = null
    private var collectorJob: Job? = null

    init {
        startFrameCollector()
        startPollWatcher()
    }

    /** 订阅 BLE 帧，合并解析进实时数据 */
    private fun startFrameCollector() {
        collectorJob = scope.launch {
            bleManager.receivedFrames.collect { frame ->
                val previous = _realtime.value
                val merged = YybmsPayloadParser.parse(frame, previous)
                _realtime.value = merged

                appendTrend(merged)

                emitAlarmsIfNeeded(previous, merged)
            }
        }
    }

    /**
     * 判定并派发告警事件。
     *
     * ⭐⭐ **电压类告警改用「实测值 vs 设备阈值」判定**，不直接采信硬件告警位。
     *
     * ## 为什么不用告警位直接下结论
     * 硬件告警位表示「**保护动作已触发**」，它带迟滞与延时，
     * 和「数值是否超过保护阈值」不是同一件事。
     * 用户实测反例：SOC 100%、电流 0A、最高单体 4.172V，却弹「单体过压」——
     * 4.172V 根本没到典型锂电保护点（4.20~4.35V）。
     *
     * ## 新判据（物理意义明确，与保护参数对齐）
     * 统一走 [BmsRealtimeData.effectiveAlarms]：
     * - 阈值从 0xA0 帧**动态读取**，不写死常量，换设备/改配置自动跟随
     * - 实测超阈值才报；未超阈值即便硬件位置位也不报（消除迟滞误报）
     * - 阈值读不到时退回告警位（安全性不倒退）
     */
    private fun emitAlarmsIfNeeded(previous: BmsRealtimeData, merged: BmsRealtimeData) {
        val curr = merged.effectiveAlarms().toSet()
        val prev = previous.effectiveAlarms().toSet()
        //⚠️ 用「集合差异」而不是「alarmFlags 变化」——
        // 电压类结论可能因实测电压变化而翻转，即使硬件位没动。
        if (curr.isEmpty() || curr == prev) return
        curr.forEach { flag ->
            _alarmEvents.tryEmit(
                AlarmEvent(
                    type = flag.label,
                    detail = buildAlarmDetail(merged, flag),
                    timestamp = merged.timestamp,
                )
            )
        }
    }

    /** 连接成功时启动轮询，断开时停止 */
    private fun startPollWatcher() {
        scope.launch {
            bleManager.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) startPolling() else stopPolling()
                // ⚠️⚠️ 断开时必须清空，否则会一直展示**断线前的旧数据**，
                // 用户会以为电池现在还是那个状态（这比显示「--」危险得多）。
                // 注意：**曲线历史不清空**（用户要求断连保留记录，见 SessionSpeedTracker）。
                if (state != ConnectionState.CONNECTED) {
                    _realtime.value = BmsRealtimeData()
                }
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                // ⚠️ 逐条下发且**每条之间留间隔**。
                // 官方 `InfoActivity_YY` 在两条命令之间 `Thread.sleep(200)`，
                // 无间隔连发会写爆 GATT 队列（日志实测：`写入被拒绝 status!=GATT_SUCCESS`），
                // 导致设备不回包、数据全为0。
                YybmsCommand.entries.forEach { cmd ->
                    if (!isActive) return@launch
                    bleManager.sendReadCommand(cmd)
                    delay(FRAME_GAP_MS)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 环形缓冲：只保留近 5 分钟数据，避免内存无限增长 */
    private fun appendTrend(data: BmsRealtimeData) {
        val old = _trend.value
        _trend.value = TrendData(
            soc = (old.soc + data.soc).takeLast(TREND_WINDOW_SIZE),
            voltage = (old.voltage + data.totalVoltage).takeLast(TREND_WINDOW_SIZE),
            current = (old.current + data.totalCurrent).takeLast(TREND_WINDOW_SIZE),
            timeLabels = (old.timeLabels + formatMinute(data.timestamp)).takeLast(TREND_WINDOW_SIZE)
        )
    }

    private fun formatMinute(ts: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        return String.format(
            "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }

    /**
 * 告警详情文案 —— ⭐ **每项一行**，不要拼成一长串。
 *
 * 原来挤在一行里「总压 70.87V 电流 0.00A SOC 100% 压差 7mV」，
 * 在 AlertDialog 里横向铺满、要靠空格猜哪段是什么，很难读。
 * 分行后每项独立，扫一眼就清楚。
 */
private fun buildAlarmDetail(data: BmsRealtimeData, flag: AlarmFlag): String = buildString {
        append("总压　${"%.2f".format(data.totalVoltage)} V\n")
        append("电流　${"%.2f".format(data.totalCurrent)} A\n")
        append("SOC　${data.soc.toInt()} %")
        if (data.cellDiff > 0f) append("\n压差　${data.cellDiff.toInt()} mV")

        // ⭐ 电压类告警附上阈值，让用户能自己核对是不是真超了
        when (flag) {
            AlarmFlag.CELL_OVER_VOLTAGE -> {
                append("\n最高单体　${"%.3f".format(data.maxCellVolt)} V")
                if (data.hasProtectParams) {
                    append("　（阈值 ${"%.3f".format(data.overVoltSet)} V）")
                } else {
                    append("　（阈值未读到）")
                }
            }
            AlarmFlag.CELL_UNDERVOLTAGE -> {
                val minV = data.cellVoltages.minOrNull() ?: 0f
                append("\n最低单体　${"%.3f".format(minV)} V")
                if (data.hasProtectParams && data.underVoltSet > 0f) {
                    append("　（阈值 ${"%.3f".format(data.underVoltSet)} V）")
                }
            }
            else -> Unit
        }
    }
}

/** 曲线数据 */
data class TrendData(
    val soc: List<Float> = emptyList(),
    val voltage: List<Float> = emptyList(),
    val current: List<Float> = emptyList(),
    val timeLabels: List<String> = emptyList()
)

/** 告警事件 */
data class AlarmEvent(
    val type: String,
    val detail: String,
    val timestamp: Long
)
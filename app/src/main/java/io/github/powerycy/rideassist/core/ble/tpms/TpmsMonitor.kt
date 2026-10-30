package io.github.powerycy.rideassist.core.ble.tpms

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import io.github.powerycy.rideassist.data.prefs.AppPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ⭐ 胎压监测器 —— **应用级单例**（与 [io.github.powerycy.rideassist.ui.realtime.SessionSpeedTracker]
 * 同一个作用域思路），管理「占空比扫描 + 广播解析 + 轮位绑定」。
 *
 * ## 与 BMS 连接的关系
 * 传感器是纯广播 beacon（见 `TPMS_REVERSE.md`），**不建立 GATT 连接**，
 * 与 `BleManager` 的 BMS 连接零冲突。唯一共享的是射频：
 * BMS 正在连接时扫描会让路（沿用 `BleManager` 注入 `BleScanner` 做
 * 射频互斥的同一思路 —— 这里反过来，由 BMS 侧在连接前调 [pauseFor] / 恢复 [resume]）。
 *
 * ## 占空比
 * 传感器几秒一条广播，不需要持续监听：扫 4s / 停 8s 循环。
 * 停 8s 期间包会积压在空中丢失几条 —— 无所谓，胎压是慢变量。
 *
 * ## 绑定语义（与 DJTPMS 相同 + 一处改进）
 * 绑定 = 把「倒计时窗口内出现的**第一颗**未绑定传感器的 SN」记到轮位（纯本地）。
 * DJTPMS 的坑：车上有两颗未绑传感器时会绑错。本类只保证「不绑到已绑在
 * 另一个轮位的传感器」（SN 去重），仍可能绑错 —— 由 UI 层弹窗向用户说明
 * 「绑定窗口内出现的第一颗」，让用户确认只留一颗未绑。
 */
class TpmsMonitor(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val prefs: AppPrefs,
) {

    companion object {
        private const val TAG = "TpmsMonitor"

        /** 轮位编号（与 DJTPMS 的 POS 语义对齐：0=前 1=后） */
        const val POS_FRONT = 0
        const val POS_REAR = 1

        /** 扫描重建周期：防个别 ROM 偷偷停掉无过滤扫描（官方每 61s 重启同款思路） */
        private const val SCAN_RESTART_MS = 30_000L

        /** 绑定倒计时 */
        const val BINDING_TIMEOUT_MS = 30_000L

        /** 绑定有效最小胎压 kPa（静止/未装车时传感器可能报 0，别把空气绑进去） */
        private const val BIND_MIN_KPA = 20

        /** 保留的最近传感器数上限（防脏数据累积） */
        private const val MAX_TRACKED = 8

        /** RSSI 领先阈值：均值差 ≥6dB 视为「明显更近」（两轮相距 1m+ 时实测差异远超此值） */
        private const val RSSI_LEAD_DB = 6.0

        /** 立即绑定的最少样本数（多候选时防单包噪声） */
        private const val RSSI_MIN_SAMPLES = 4

        private const val KEY_READINGS = "readings"
    }

    /** 绑定进行中的 UI 状态（供卡片显示倒计时） */
    data class BindingUi(val pos: Int, val remainSec: Int)

    data class UiState(
        /** 全部最近见过的传感器，key = SN */
        val readings: Map<String, TpmsPacket.Reading> = emptyMap(),
        val binding: BindingUi? = null,
    ) {
        val lastUpdateMs: Long get() = readings.maxOfOrNull { it.value.timestampMs } ?: 0L
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** 一次性提示（绑定成功/超时等），UI 层 collect 后 toast */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService()

    private var scanJob: Job? = null

    /** 当前绑定中的轮位，-1 = 未在绑定 */
    private var bindPos = -1
    private var bindJob: Job? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }
    }

    // ==================== 生命周期 ====================

    /** 幂等启动：已在跑就不重复 */
    fun start() {
        if (scanJob?.isActive == true) return
        setupDebugFile()
        scanJob = scope.launch {
            try {
                var lastScanRestart = 0L
                while (isActive) {
                    val ok = startScanner()
                    if (!ok) {
                        debugLog("startScanner 失败，5s 后重试")
                        delay(5_000L)
                        continue
                    }
                    debugLog("扫描开始")
                    // ⭐⭐ **持续扫描**（对齐官方 DJTPMS：startLeScan 常开）——
                    // 实测静止 TPMS 广播间隔可达 2~5 分钟一条（前轮 75s 一包），
                    // 占空比扫4停8 的命中率只有 1/3，稀疏的后轮广播会长期撞不到，
                    // 表现为「后轮永远无数据」。每 30s 重建扫描是防个别 ROM
                    // 偷偷停掉无过滤扫描（官方每 61s 重启同款思路）。
                    delay(SCAN_RESTART_MS)
                    stopScanner()
                    debugLog("${SCAN_RESTART_MS / 1000}s 重建扫描（心跳）")
                    lastScanRestart = System.currentTimeMillis()
                }
            } finally {
                stopScanner()
            }
        }
        restoreReadings()
        debugLog("TPMS 扫描启动（持续模式，30s 重建），已恢复 ${_state.value.readings.size} 颗传感器读数")
    }

    // ==================== 观测日志（落盘） ====================

    /**
     * ⭐ TPMS 事件落盘到私有文件（logcat 缓冲会被 BMS 高频日志滚动冲掉，
     * 排查「收不到包」靠 logcat 不可靠）。用 `adb shell run-as` 读取。
     */
    private var debugFile: java.io.File? = null

    private fun setupDebugFile() {
        val f = java.io.File(context.filesDir, "tpms_debug.log")
        if (f.length() > 200_000) f.delete()  // 防无限增长，截断重来
        debugFile = f
        TpmsPacket.debugSink = { msg -> debugLog(msg) }
    }

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            debugFile?.appendText(
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " $msg\n"
            )
        } catch (e: Exception) {
            Log.w(TAG, "debugLog 写入失败: ${e.message}")
        }
    }

    /** 完全停止（App 退后台时由 MainActivity 调） */
    fun stop() {
        scanJob?.cancel()
        scanJob = null
        stopScanner()
    }

    /**
     * BMS 连接前的射频让路：暂停扫描若干毫秒后自动恢复。
     * 与 `BleScanner.stopScan` 的互斥思路一致 —— 扫描占用射频会让 GATT 连接必然失败。
     */
    fun pauseFor(ms: Long = 10_000L) {
        if (scanJob?.isActive != true) return
        scope.launch {
            stopScanner()
            delay(ms)
        }
    }

    fun resume() {
        start()
    }

    private fun startScanner(): Boolean {
        val scanner = try {
            bluetoothManager?.adapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            Log.w(TAG, "扫描权限未授予: ${e.message}")
            null
        } ?: return false
        return try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startScan SecurityException: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "蓝牙未开启: ${e.message}")
            false
        }
    }

    private fun stopScanner() {
        val scanner = try {
            bluetoothManager?.adapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            return
        } ?: return
        try {
            scanner.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan: ${e.message}")
        }
    }

    // ==================== 数据处理 ====================

    private fun handle(result: ScanResult) {
        val name = try {
            result.scanRecord?.deviceName ?: result.device.name
        } catch (e: SecurityException) {
            null
        }
        // ⭐ 观测：名字非空但不含 TPMS 的设备打一条（每个名字只打一次），
        // 验证「后轮广播名不含 TPMS 被静默过滤」的假设
        if (!name.isNullOrBlank() && !name.contains(TpmsPacket.NAME_KEYWORD, true)) {
            if (seenNonTpmsNames.add(name)) {
                debugLog("非TPMS名广播: name=$name addr=${result.device.address}")
            }
        }
        val record = result.scanRecord?.bytes ?: return
        val reading = TpmsPacket.parse(name, record, System.currentTimeMillis())
        if (reading == null) return

        // ⭐ 观测日志（排查「后轮收不到数据」）：每包一条，量级 = 传感器数 × 广播频率，
        // 一分钟几条，可接受。能看到每颗传感器的收包频率与内容。
        debugLog("收到包 sn=${reading.sn} kpa=${reading.kpa} temp=${reading.tempC} " +
            "volt=${reading.voltage} rssi=${result.rssi}")

        // 只保留最近 MAX_TRACKED 颗，按时间淘汰
        val map = _state.value.readings.toMutableMap()
        map[reading.sn] = reading
        if (map.size > MAX_TRACKED) {
            map.entries.sortedBy { it.value.timestampMs }
                .take(map.size - MAX_TRACKED)
                .forEach { map.remove(it.key) }
        }
        _state.value = _state.value.copy(readings = map)
        persistReadings(map)

        if (bindPos >= 0) tryBind(reading, result.rssi)
    }

    // ==================== 读数持久化（用户要求：重启 APP 立即显示，不出现「无数据」） ====================

    private val readingsSp by lazy {
        context.getSharedPreferences("tpms_readings", android.content.Context.MODE_PRIVATE)
    }

    /**
     * 收包即落盘。频率 = 传感器数 × 广播间隔（约每 1~3 分钟 1~2 次 SP 写入），
     * 无需节流。格式：sn|kpa|temp|volt|lowBat|ts，多条以分号分隔。
     */
    private fun persistReadings(map: Map<String, TpmsPacket.Reading>) {
        val joined = map.values.joinToString(";") {
            "${it.sn}|${it.kpa}|${it.tempC}|${it.voltage}|${if (it.lowBattery) 1 else 0}|${it.timestampMs}"
        }
        readingsSp.edit().putString(KEY_READINGS, joined).apply()
    }

    private fun restoreReadings() {
        val raw = readingsSp.getString(KEY_READINGS, null) ?: return
        val map = raw.split(";").mapNotNull { seg ->
            val f = seg.split("|")
            if (f.size < 6) return@mapNotNull null
            TpmsPacket.Reading(
                sn = f[0],
                kpa = f[1].toIntOrNull() ?: return@mapNotNull null,
                tempC = f[2].toIntOrNull() ?: return@mapNotNull null,
                voltage = f[3].toDoubleOrNull() ?: 0.0,
                lowBattery = f[4] == "1",
                timestampMs = f[5].toLongOrNull() ?: return@mapNotNull null,
            )
        }.associateBy { it.sn }
        if (map.isNotEmpty()) _state.value = _state.value.copy(readings = map)
    }

    // ==================== 绑定 ====================

    /**
     * 开始绑定指定轮位（重复调用 = 重新倒计时）。
     *
     * ## ⭐ 两颗传感器同时在线的识别策略（用户要求：不用拆下来就能绑）
     *
     * 旧规则「窗口内第一颗未绑传感器入位」在两颗同时发包时是**随机**的，
     * 绑反了还看不出来。新规则按 **RSSI（信号强度）** 识别：
     * 窗口开始时 UI 提示「把手机靠近目标车轮」，靠近的那颗信号明显更强，
     * - 单候选：窗口内只出现一颗未绑传感器 → 连续 2 包即绑（重绑场景无歧义）
     * - 多候选：某颗样本 ≥4 包且 RSSI 均值领先其他候选 ≥6 dB → 立即绑定
     * - 窗口结束仍分不出 → 绑定失败，提示重新靠近重试
     * 另有「交换前后轮」兜底（UI 层），万一绑反一键对调。
     */
    fun startBinding(pos: Int) {
        cancelBinding()
        bindPos = pos
        bindSamples.clear()
        bindJob = scope.launch {
            val deadline = System.currentTimeMillis() + BINDING_TIMEOUT_MS
            while (isActive) {
                val remain = ((deadline - System.currentTimeMillis()) / 1000L).toInt()
                if (remain <= 0) {
                    finishBindingByRssi()
                    return@launch
                }
                _state.value = _state.value.copy(binding = BindingUi(pos, remain))
                delay(1_000L)
            }
        }
        Log.i(TAG, "开始绑定轮位 $pos，倒计时 ${BINDING_TIMEOUT_MS / 1000}s（RSSI 识别）")
    }

    fun cancelBinding() {
        bindJob?.cancel()
        bindJob = null
        bindPos = -1
        bindSamples.clear()
        _state.value = _state.value.copy(binding = null)
    }

    /** 绑定窗口内各候选传感器的 RSSI 样本（key = SN） */
    private val bindSamples = mutableMapOf<String, MutableList<Int>>()

    /** 非 TPMS 名字去重集合（观测用，进程内有效） */
    private val seenNonTpmsNames = mutableSetOf<String>()

    private fun tryBind(reading: TpmsPacket.Reading, rssi: Int) {
        val pos = bindPos
        if (pos < 0) return
        val otherSn = if (pos == POS_FRONT) prefs.tpmsRearSn else prefs.tpmsFrontSn
        // 已绑在另一轮位的那颗不是候选
        if (reading.sn == otherSn) return
        // 静止/未装车时胎压可能为 0，别把空气绑进去
        if (reading.kpa < BIND_MIN_KPA) return

        val samples = bindSamples.getOrPut(reading.sn) { mutableListOf() }
        samples.add(rssi)

        val candidates = bindSamples.keys
        if (candidates.size == 1) {
            // 单候选：无歧义，**1 包即绑**（绑定期间已持续扫描，
            // 且去重规则保证绝不会绑到另一轮位已绑的那颗 —— 多等没有意义）
            bindCandidate(reading.sn, pos)
            return
        }
        // 多候选：样本够且信号明显更强 → 立即绑定（不用等窗口结束）
        if (samples.size >= RSSI_MIN_SAMPLES) {
            val myAvg = samples.average()
            val bestOther = bindSamples.entries
                .filter { it.key != reading.sn }
                .maxOfOrNull { it.value.average() } ?: return
            if (myAvg >= bestOther + RSSI_LEAD_DB) bindCandidate(reading.sn, pos)
        }
    }

    /** 窗口到期：按累计 RSSI 裁决 */
    private fun finishBindingByRssi() {
        val pos = bindPos
        bindPos = -1
        _state.value = _state.value.copy(binding = null)
        val candidates = bindSamples.filter { it.value.isNotEmpty() }
        when {
            candidates.isEmpty() -> _events.tryEmit(
                context.getString(io.github.powerycy.rideassist.R.string.tpms_bind_timeout)
            )
            candidates.size == 1 -> {
                val sn = candidates.keys.first()
                bindCandidate(sn, pos)
            }
            else -> {
                val ranked = candidates.entries
                    .map { it.key to it.value.average() }
                    .sortedByDescending { it.second }
                val lead = ranked[0].second - ranked[1].second
                if (lead >= RSSI_LEAD_DB) {
                    bindCandidate(ranked[0].first, pos)
                } else {
                    Log.i(TAG, "绑定歧义：${ranked.map { "${it.first}=${it.second}" }}")
                    _events.tryEmit(
                        context.getString(io.github.powerycy.rideassist.R.string.tpms_bind_ambiguous)
                    )
                }
            }
        }
    }

    private fun bindCandidate(sn: String, pos: Int) {
        val posName = context.getString(
            if (pos == POS_FRONT) io.github.powerycy.rideassist.R.string.tpms_front
            else io.github.powerycy.rideassist.R.string.tpms_rear
        )
        if (pos == POS_FRONT) prefs.tpmsFrontSn = sn else prefs.tpmsRearSn = sn
        cancelBinding()
        Log.i(TAG, "绑定成功 pos=$pos sn=$sn")
        _events.tryEmit(
            context.getString(io.github.powerycy.rideassist.R.string.tpms_bind_done, posName, sn.takeLast(4))
        )
    }

    /** 交换前后轮的绑定（绑反了的一键修正，不用拆传感器） */
    fun swapPositions() {
        val f = prefs.tpmsFrontSn
        val r = prefs.tpmsRearSn
        if (f.isNullOrBlank() || r.isNullOrBlank()) return
        prefs.tpmsFrontSn = r
        prefs.tpmsRearSn = f
        Log.i(TAG, "交换前后轮：front=${r.takeLast(4)} rear=${f.takeLast(4)}")
    }

    /** 解绑轮位（SN 置空） */
    fun unbind(pos: Int) {
        if (pos == POS_FRONT) prefs.tpmsFrontSn = null else prefs.tpmsRearSn = null
        Log.i(TAG, "解绑轮位 $pos")
    }
}

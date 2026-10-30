package com.yanyang.yybms.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.yanyang.yybms.BuildConfig
import com.yanyang.yybms.core.protocol.FrameAssembler
import com.yanyang.yybms.core.protocol.YybmsProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BLE 连接管理器（应用级单例）。
 *
 * 关键行为：
 *  - **严格只读**：对外只暴露 [sendReadCommand]，内部用 [com.yanyang.yybms.core.protocol.YybmsCommand]
 *    中的只读命令集组帧，不提供任何写保护板/改配置/均衡控制的方法。
 *  - **开机自动连接**：单次超时 5s，最多 3 次，失败即终止；全程仅通过 [isAutoConnecting] 反映状态，
 *    不弹窗、不阻塞 UI，可随时 [cancelAutoConnect]。
 *  - **断连重连**：3 秒间隔，最多 3 次。
 *  - **粘包处理**：通知数据先经 [FrameAssembler]，只向上层抛 CRC 校验通过的完整帧。
 */
@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * 注入扫描器只为做**射频互斥**：连接前必须同步停掉扫描。
     * 可空（测试场景可传 null），为 null 时跳过互斥。
     */
    private val scanMutex: BleScanner? = null
) {

    companion object {
        private const val TAG = "BleManager"

        /**
         * 连接超时 **6秒**。
         *
         * 官方是 3000ms（`setConnectTimeout(3000)`），但那是在
         * 「4次 × 1秒固定间隔」的重试配合下工作的——单次超时短、整体窗口长。
         * 我们早期用 3s + 指数退避（3s/6s）+ 只试 2 次，实测出现
         *「设备明明在连接、一会儿就成功了，但APP 已显示连接失败」——
         * 根因是**第一次尝试的 3s 窗口内没连上就判失败**。
         *
         * 华为 TAS-AN00 上 GATT 协商偶尔需 4~5s，故单次放宽到 6s，
         * 配合下面的 4 次 × 1s 重试，整体窗口约 30s，覆盖官方的成功率。
         */
        const val AUTO_CONNECT_TIMEOUT_MS = 6_000L

        /**
         * 首次连接尝试次数（含），失败立即反馈；重试交给用户再点，避免长时间无响应。
         *
         * ⭐ 取值 **4**，与官方 `setConnectRetry(3)`（= 3+1 = 4 次）完全一致。
         * 之前是 2 次 —— 成功率明显低于官方的主因之一。
         */
        const val AUTO_CONNECT_MAX_RETRY = 4

        /**
         * 重试间隔基数：官方是**固定 1000ms**（`sendEmptyMessageDelayed(1, 1000L)`），
         * 不是指数退避。
         *
         * ⭐ 之前用指数退避是错的：信号弱时重试过密反而互相干扰，
         * 但退避到 6s/12s 时用户早就失去耐心、APP 也已判失败。
         * 固定 1s 才能在总窗口内多做几次尝试，**用总尝试次数换成功率**。
         */
        const val CONNECT_RETRY_INTERVAL_MS = 1_000L

        /**
         * 断线重连：固定间隔 + 5 次尝试。
         * 之前用指数退避 1s/2s/4s/8s/16s，设备信号偏弱时（如实测 -94dBm）
         * 极易失败并直接置为"连接失败"，用户看到已连接又变失败。
         */
        const val RECONNECT_MAX_RETRY = 5

        /** 断连重连间隔（固定 1s，与官方一致） */
        const val RECONNECT_BASE_MS = 1_000L

        /**
         * Client Characteristic Configuration Descriptor 标准 UUID。
         * 框架的 BluetoothGattDescriptor.UUID_CLIENT_CHARACTERISTIC_CONFIG 常量
         * 在 API 36 SDK 中已移除，这里直接用标准值，全版本兼容。
         */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    // ==================== 对外状态 ====================

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isAutoConnecting = MutableStateFlow(false)
    val isAutoConnecting: StateFlow<Boolean> = _isAutoConnecting.asStateFlow()

    private val _receivedFrames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedFrames: SharedFlow<ByteArray> = _receivedFrames.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ==================== 内部状态 ====================

    private val assembler = FrameAssembler()
    private val bluetoothManager: BluetoothManager? =
        ContextCompat.getSystemService(context, BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var currentDevice: BluetoothDevice? = null

    private var connectJob: Job? = null
    private var reconnectJob: Job? = null

    /** 本次连接尝试的结果信号，由 gattCallback 完成 */
    private var connectSignal: CompletableDeferred<Boolean>? = null

    @Volatile
    private var userCancelled = false

    /**
     * 连续多少轮「重试全部失败」。用于把「偶发一次失败」与「设备真的连不上」
     * 区分开：偶发失败应保持 CONNECTING 继续下一轮，而不是立刻弹"连接失败"。
     *
     * ⭐ 这正是用户反馈「进入APP 默认显示连接失败」的根因：
     * 启动后第一轮扫描尚未完成（或第一轮连接刚失败），状态就被置为 FAILED，
     * 而实际上设备就在旁边、连接正在进行中。
     */
    @Volatile
    private var connectFailureStreak = 0

    /** 写入失败只记一次，避免每秒 8 帧把日志刷爆 */
    private var writeFailureLogged = false

    // ==================== 权限 ====================

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ==================== 手动连接 ====================

    /**
     * 手动连接设备。
     * @param onResult 连接结果回传，UI 据此决定是否覆盖已保存设备记录
     */
    fun connect(macAddress: String, onResult: (Boolean) -> Unit = {}) {
        // ⚠️ 同步停扫 + 同步置CONNECTING，都必须在 launch **之外**。
        // 放进协程里会有一帧延迟，UI 层 `doScan()` 读到的仍是旧状态
        // （DISCONNECTED/FAILED），拦不住扫描，导致扫描与连接抢射频。
        scanMutex?.stopScan()
        _connectionState.value = ConnectionState.CONNECTING

        scope.launch {
            val device = prepareDevice(macAddress)
            if (device == null) {
                _connectionState.value = ConnectionState.FAILED
                onResult(false)
                return@launch
            }
            userCancelled = false
            reconnectJob?.cancel()
            currentDevice = device
            connectFailureStreak = 0

            val ok = connectWithRetry(
                device, AUTO_CONNECT_MAX_RETRY,
                AUTO_CONNECT_TIMEOUT_MS, CONNECT_RETRY_INTERVAL_MS
            )
            if (ok) _connectionState.value = ConnectionState.CONNECTED
            else if (!userCancelled) _connectionState.value = ConnectionState.FAILED
            onResult(ok)
        }
    }

    // ==================== 自动连接 ====================

    /**
     * 开机自动连接上次设备。
     *
     * ⭐ **完全对齐官方 `InfoActivity_YY.autoConnectBle()`**：
     * ```
     * String mac = SPStaticUtils.getString("bt_device_address");  // 上次设备 MAC
     * if (TextUtils.isEmpty(mac) || !Get_Permission()) return;   // 空 or 没权限 → 直接放弃
     * connectBtDevice(mac, isAutoConn);                          // 直接连，不经过扫描
     * ```
     *
     * 官方把「记住上次设备」当**主路径**，扫描只是降级路径（选新设备）时的选项。
     * 它敢直连是因为 legacy `connectGatt` + 自己实现的重试，
     * 直连失败率远低于「先扫到再连」——**先扫描天然慢一拍**，
     * 而且模块广播间隔不规律时可能根本扫不到。
     *
     * @return Job，供 UI 绑定"取消自动连接"按钮
     */
    fun autoConnect(macAddress: String): Job {
        userCancelled = false
        _isAutoConnecting.value = true
        connectJob?.cancel()
        connectFailureStreak = 0

        /**
         * ⚠️⚠️ 连接前**同步**停掉扫描，否则 BLE 扫描占用射频会让 GATT 必然失败。
         *
         * 实测日志（08:39:49）：自动连接刚启动的同一毫秒内，
         * BleScanner 连续打了 6 次 `startScan`（UI 层的 collect 重入 + 生命周期回调），
         * 紧接着 4 次连接尝试全部 `1000ms 内未完成`。
         *
         * 根因：UI 层 `doScan()` 靠读 `connectionState` 判断能否扫描，
         * 但 `StateFlow` 是**异步**更新的——连接刚发起时 UI 读到的还是
         * DISCONNECTED/FAILED，拦不住扫描。这里在管理层做同步互斥才可靠。
         */
        scanMutex?.stopScan()
        // ⚠️ 同步置 CONNECTING：必须在 launch 外，否则 UI 有窗口期读到旧状态
        _connectionState.value = ConnectionState.CONNECTING

        connectJob = scope.launch {
            var success = false
            var gaveUp = false
            try {
                val device = prepareDevice(macAddress)
                if (device == null) {
                    gaveUp = true
                } else {
                    currentDevice = device
                    // 一轮 = 4 次尝试，单次 6s 超时、间隔 1s，整体窗口约 27s。
                    // 期间状态一直是 CONNECTING，不会中途跳 FAILED。
                    success = connectWithRetry(
                        device, AUTO_CONNECT_MAX_RETRY,
                        AUTO_CONNECT_TIMEOUT_MS, CONNECT_RETRY_INTERVAL_MS
                    )
                }
            } finally {
                _isAutoConnecting.value = false
                when {
                    success -> _connectionState.value = ConnectionState.CONNECTED
                    userCancelled -> _connectionState.value = ConnectionState.DISCONNECTED
                    gaveUp -> _connectionState.value = ConnectionState.FAILED
                    else -> {
                        // ⚠️ 这里才置 FAILED —— 只有**整轮重试都失败**才算失败。
                        // 之前每轮失败都立刻置FAILED，用户看到的就是
                        //「明明在连接、一会儿就成功了，但 APP 先显示连接失败」。
                        Log.w(TAG, "autoConnect: ${AUTO_CONNECT_MAX_RETRY} 次重试全部失败")
                        _connectionState.value = ConnectionState.FAILED
                    }
                }
            }
        }
        return connectJob!!
    }

    /** 取消自动连接（不弹窗、不阻塞） */
    fun cancelAutoConnect() {
        userCancelled = true
        _isAutoConnecting.value = false
        connectJob?.cancel()
        closeGatt()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun disconnect() {
        userCancelled = true
        connectJob?.cancel()
        reconnectJob?.cancel()
        closeGatt()
        currentDevice = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ==================== 连接内部实现 ====================

    private fun prepareDevice(macAddress: String): BluetoothDevice? {
        if (!hasConnectPermission()) {
            _messages.tryEmit("缺少蓝牙权限，无法连接")
            return null
        }
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _messages.tryEmit("蓝牙未开启，请先开启蓝牙")
            return null
        }
        return try {
            btAdapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            _messages.tryEmit("设备地址无效：$macAddress")
            null
        }
    }

    /**
     * 按最大次数连接，**固定间隔**重试（与官方 `BleConnectRequest` 一致）。
     *
     * ⚠️⚠️ `timeoutMs` 与 `retryIntervalMs` 是**两个不同的值，绝不能混用**：
     * - `timeoutMs`：**单次** connectGatt 等待多久算失败（6s）
     * - `retryIntervalMs`：两次尝试之间等多久再重试（1s）
     *
     * 之前误把 interval 当timeout 传进去，日志实测变成
     * `tryConnectOnce: 1000ms 内未完成` —— 单次只等1 秒，
     * 华为机型 GATT 协商根本来不及，4 次尝试 7 秒内全被判失败。
     * 这正是「明明在建立连接、一会儿就成功」的另一个根因。
     *
     * @param timeoutMs 单次尝试的超时（6s）
     * @param retryIntervalMs 两次尝试之间的固定间隔（1s）
     */
    private suspend fun connectWithRetry(
        device: BluetoothDevice,
        maxRetry: Int,
        timeoutMs: Long,
        retryIntervalMs: Long
    ): Boolean {
        var attempt = 0
        while (attempt < maxRetry && !userCancelled) {
            attempt++
            Log.d(TAG, "连接尝试 $attempt/$maxRetry（单次超时 ${timeoutMs}ms）: ${device.address}")
            if (tryConnectOnce(device, timeoutMs)) {
                connectFailureStreak = 0
                return true
            }
            if (attempt < maxRetry && !userCancelled) {
                // ⭐ 固定间隔，不是指数退避。
                // 官方 `BleConnectRequest.retryConnectLater()` 固定 1000ms。
                Log.d(TAG, "第 $attempt 次失败，${retryIntervalMs}ms 后重试")
                delay(retryIntervalMs)
            }
        }
        connectFailureStreak++
        Log.w(TAG, "connectWithRetry: $maxRetry 次全部失败（连续第 $connectFailureStreak 轮）")
        return false
    }

    /**
     * 单次连接尝试，内部超时判定。超时或失败一律返回 false，绝不挂起。
     *
     * ⭐ `connectGatt` 参数严格对齐官方（`BleConnectWorker.openGatt`）：
     * ```
     * connectGatt(context, false, callback, 2)   // 2 = TRANSPORT_LE
     * ```
     * - `autoConnect = false`：不做系统级自动重连，重试完全由我们控制（可观测、可退避）
     * - `transport = TRANSPORT_LE(2)`：**显式指定 LE**，避免 TRANSPORT_AUTO
     *   在双模机型上先做 BT/BLE 协商往返，额外增加延迟与失败率
     * - **不传 handler**：回调落在 Binder 线程由框架转发。传
     *   `Handler(Looper.getMainLooper())` 会让主线程繁忙时回调排队，
     *   拖慢整个连接状态机（部分华为机型上尤其明显）
     */
    private suspend fun tryConnectOnce(device: BluetoothDevice, timeoutMs: Long): Boolean {
        closeGatt()
        assembler.reset()

        val signal = CompletableDeferred<Boolean>()
        connectSignal = signal

        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            _messages.tryEmit("连接失败：权限不足")
            return false
        }

        // 等 gattCallback 完成信号；服务/特征就绪才算真正连通
        val connected = withTimeoutOrNull(timeoutMs) { signal.await() } ?: false
        if (!connected) {
            Log.w(TAG, "tryConnectOnce: ${timeoutMs}ms 内未完成，关闭 GATT 重试")
            // ⭐ 官方在服务发现失败时会先refreshDeviceCache() 再重试
            // （`BleConnectRequest.onServiceDiscoverFailed`）。
            // 不清缓存的话，系统缓存的过期 GATT 表会让后续每次 discoverServices 都失败。
            refreshDeviceCache()
            closeGatt()
        }
        return connected
    }

    /**
     * 清空系统 GATT 缓存。
     *
     * 官方 `BleConnectRequest.refreshDeviceCache()` 的等价实现。
     * 必须在**已关闭 GATT 且系统回调结束后**调用，否则华为机型上极易 ANR。
     *
     * ⚠️ 实测日志：`refreshDeviceCache 失败（忽略）: android.bluetooth.BluetoothDevice.refreshDeviceCache []`
     * 说明 `getMethod` 拿得到方法，但 `invoke` 抛异常——
     * 华为 TAS-AN00 上这个隐藏 API 被系统限制（`ReflectiveCallNotAllowedException`
     * 或非公开 API 拦截）。**失败属正常，忽略即可**，不能因此中断重连流程。
     *
     * 真正保证重连成功率的是「4 次固定间隔重试」，缓存清理只是加分项。
     */
    private fun refreshDeviceCache() {
        val dev = currentDevice ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothManager?.javaClass?.getMethod("refreshDeviceCache", BluetoothDevice::class.java)
                    ?.invoke(bluetoothManager, dev)
            } else {
                @Suppress("DEPRECATION")
                dev.javaClass.getMethod("refreshDeviceCache").invoke(dev)
            }
            Log.i(TAG, "refreshDeviceCache: GATT 缓存已清除")
        } catch (e: Throwable) {
            // 华为机型上常因隐藏 API 限制抛异常，属预期情况，静默处理
            Log.w(TAG, "refreshDeviceCache: 本机型不支持（忽略）")
        }
    }

    // ==================== GATT 回调 ====================

    private val gattCallback = object : BluetoothGattCallback() {

        /**
         * 注意：API 33 曾引入四参数重载 (gatt, status, newState, statusCode)，
         * 但在 API 36 SDK 中该重载已被移除，仅保留三参数版本。
         * 系统在低版本会调用此方法，故此处只实现三参数签名即可全版本兼容。
         */
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // status 是失败原因（8=超时 19=远端断开 133=GATT错误 等），必须打出来
            Log.i(TAG, "onConnectionStateChange: status=$status newState=$newState" +
                    " (0=OK 8=超时 19=对端断开 133=GATT错误)")
            handleConnectionChange(g, newState, status)
        }

        private fun handleConnectionChange(g: BluetoothGatt?, newState: Int, status: Int = 0) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT 已连接，发现服务中")
                    try {
                        g?.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "服务发现失败：权限不足", e)
                        _messages.tryEmit("服务发现失败：权限不足")
                        connectSignal?.complete(false)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT 断开 status=$status")
                    connectSignal?.complete(false)
                    closeGatt()
                    // 只有"曾连接成功过"的意外掉线才触发重连
                    if (_connectionState.value == ConnectionState.CONNECTED &&
                        !userCancelled && currentDevice != null
                    ) {
                        scheduleReconnect()
                    } else if (!userCancelled) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }

        /** 旧签名（Android 12 及以下回调） */
        @Deprecated("兼容 Android 12 及以下")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // status != 0 表示服务发现失败，必须记录原因
            Log.i(TAG, "onServicesDiscovered: status=$status (0=成功)")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _messages.tryEmit("服务发现失败 status=$status")
                connectSignal?.complete(false)
                return
            }
            handleServices(g)
        }

        /**
         * CCCD 写入回调。缺失这个回调时，
         * 通知订阅是否成功完全不可见——会出现"写帧正常但永远收不到数据"且毫无线索。
         */
        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            Log.i(
                TAG,
                "onDescriptorWrite: ${descriptor.uuid} status=$status" +
                        if (status == BluetoothGatt.GATT_SUCCESS) " 通知已订阅" else " 订阅失败"
            )
        }

        private fun handleServices(g: BluetoothGatt?) {
            if (g == null) {
                Log.w(TAG, "handleServices: gatt 为 null")
                _messages.tryEmit("未发现服务")
                connectSignal?.complete(false)
                return
            }

            // ⚠️ 打印完整 GATT 结构：现场适配 UUID 时必须看这份日志，
            // 不能靠猜——猜错会导致"连上了但收不到数据"。
            logGattTable(g)

            val service = g.getService(BleUuids.SERVICE_UUID)
                ?: BleUuids.ALT_SERVICE_UUIDS.firstNotNullOfOrNull { g.getService(it) }

            if (service == null) {
                Log.e(TAG, "handleServices: 未匹配到任何候选服务 UUID")
                _messages.tryEmit("未找到彦阳服务，UUID 可能不匹配")
                connectSignal?.complete(false)
                return
            }
            Log.i(TAG, "handleServices: 命中服务 ${service.uuid}")
            setupCharacteristics(g, service.uuid)
        }

        /** 打印设备全部服务/特征/描述符，供现场核对 UUID */
        private fun logGattTable(g: BluetoothGatt) {
            runCatching {
                val sb = StringBuilder("\n===== GATT TABLE =====\n")
                for (svc in g.services) {
                    sb.append("SERVICE ${svc.uuid}\n")
                    for (ch in svc.characteristics) {
                        sb.append("  CH ${ch.uuid}")
                        sb.append(" props=0x")
                        sb.append(Integer.toHexString(ch.properties))
                        sb.append(if (ch.properties and 0x10 != 0) " [NOTIFY]" else "")
                        sb.append(if (ch.properties and 0x08 != 0) " [WRITE]" else "")
                        sb.append(if (ch.properties and 0x02 != 0) " [READ]" else "")
                        sb.append("\n")
                        for (dsc in ch.descriptors) {
                            sb.append("    DSC ${dsc.uuid}\n")
                        }
                    }
                }
                sb.append("===== END GATT =====")
                Log.i(TAG, sb.toString())
            }.onFailure { Log.e(TAG, "logGattTable 失败: ${it.message}") }
        }

        /** 旧签名（Android 12 及以下回调） */
        @Deprecated("兼容 Android 12 及以下")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            if (value != null) onData(value)
        }

        /** 新签名（Android 13+ 回调） */
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) = onData(value)

        /** 旧签名（Android 12 及以下回调） */
        @Deprecated("兼容 Android 12 及以下")
        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            if (status == BluetoothGatt.GATT_SUCCESS && value != null) onData(value)
        }

        /** 新签名（Android 13+ 回调） */
        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) onData(value)
        }

        /** 通知/读取数据 → 粘包处理 → 只抛合法帧 */
        private fun onData(value: ByteArray) {
            // 即使没解出完整帧，也要看到原始数据，排查协议不匹配
            Log.i(TAG, "onData: 收到 ${value.size} 字节 ${YybmsProtocol.toHex(value)}")
            try {
                val frames = assembler.feed(value)
                if (frames.isEmpty()) {
                    Log.w(TAG, "onData: 未解出完整帧（CRC 或帧格式不符）")
                }
                frames.forEach { frame ->
                    Log.d(TAG, "收到帧: ${YybmsProtocol.toHex(frame)}")
                    _receivedFrames.tryEmit(frame)
                }
            } catch (e: Exception) {
                // 解析异常绝不允许冒泡到主线程导致崩溃
                Log.e(TAG, "数据处理异常", e)
            }
        }
    }

    private fun setupCharacteristics(g: BluetoothGatt, serviceUuid: UUID) {
        val service = g.getService(serviceUuid) ?: return
        Log.i(TAG, "setupCharacteristics: 服务 ${service.uuid} 共 ${service.characteristics.size} 个特征")

        //遍历全部特征并打日志
        service.characteristics.forEach { ch ->
            Log.i(TAG, "  特征 ${ch.uuid} props=0x${Integer.toHexString(ch.properties)}")
        }

        /**
         * 优先按 UUID 精确匹配——服务下通常有多个特征（如只读/只写分开），
         * 单纯按属性取会被后面的特征覆盖，最终选错通道导致"连上但收不到数据"。
         * UUID 匹配不到时才按属性兜底。
         */
        fun hasNotify(ch: BluetoothGattCharacteristic) =
            ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

        fun hasWrite(ch: BluetoothGattCharacteristic) =
            ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

        // 1) 优先精确匹配配置里的 UUID
        notifyChar = service.characteristics.firstOrNull { it.uuid == BleUuids.NOTIFY_UUID }
            ?: service.characteristics.firstOrNull {
                it.uuid != BleUuids.WRITE_UUID && hasNotify(it)
            }

        writeChar = service.characteristics.firstOrNull { it.uuid == BleUuids.WRITE_UUID }
            ?: service.characteristics.firstOrNull {
                it.uuid != BleUuids.NOTIFY_UUID && hasWrite(it)
            }

        Log.i(TAG, "setupCharacteristics: notify=${notifyChar?.uuid}, write=${writeChar?.uuid}")

        notifyChar?.let { enableNotification(g, it) }

        if (notifyChar != null && writeChar != null) {
            connectSignal?.complete(true)
        } else {
            Log.e(TAG, "setupCharacteristics: 特征不完整 notify=${notifyChar != null} write=${writeChar != null}")
            _messages.tryEmit(
                "特征值不匹配 write=${writeChar != null} notify=${notifyChar != null}"
            )
            connectSignal?.complete(false)
        }
    }

    private fun enableNotification(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        try {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } catch (e: SecurityException) {
            _messages.tryEmit("开启通知失败：权限不足")
        }
    }

    private fun closeGatt() {
        try {
            gatt?.close()
        } catch (e: SecurityException) {
            // ignore
        }
        gatt = null
        writeChar = null
        notifyChar = null
        assembler.reset()
    }

    /** 运行中断连自动重连：固定 1s 间隔，最多 5 次（与官方一致） */
    private fun scheduleReconnect() {
        _connectionState.value = ConnectionState.CONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val device = currentDevice
            if (device == null) return@launch
            val ok = connectWithRetry(
                device, RECONNECT_MAX_RETRY,
                AUTO_CONNECT_TIMEOUT_MS, RECONNECT_BASE_MS
            )
            if (ok) {
                _connectionState.value = ConnectionState.CONNECTED
                _messages.tryEmit("已自动重连")
            } else if (!userCancelled) {
                _connectionState.value = ConnectionState.FAILED
                _messages.tryEmit("重连失败，请手动重新连接")
            }
        }
    }

    // ==================== 发送（只读） ====================

    /**
     * 【唯一的发送入口】发送一个只读查询命令。
     * 不提供写参数/写配置/均衡控制的方法，从架构上杜绝误下发。
     * @return 是否成功写入 GATT 队列
     */
    fun sendReadCommand(cmd: com.yanyang.yybms.core.protocol.YybmsCommand): Boolean {
        val frame = cmd.buildFrame()
        Log.d(TAG, "发送帧 [${cmd.label}]: ${YybmsProtocol.toHex(frame)} (回包${cmd.expectedResponse}B)")
        return sendFrame(frame)
    }

    private fun sendFrame(frame: ByteArray): Boolean {
        val g = gatt ?: run {
            Log.w(TAG, "sendFrame: gatt 为空（未连接）")
            return false
        }
        val ch = writeChar ?: run {
            Log.w(TAG, "sendFrame: writeChar 为空")
            return false
        }
        return try {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = frame
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            // 首次失败时明确记录，后续高频失败不再刷屏
            if (!ok && writeFailureLogged.not()) {
                Log.e(TAG, "sendFrame: 写入被拒绝 status!=GATT_SUCCESS")
                writeFailureLogged = true
            } else if (ok) {
                writeFailureLogged = false
            }
            ok
        } catch (e: SecurityException) {
            Log.e(TAG, "sendFrame: 权限不足", e)
            _messages.tryEmit("发送失败：权限不足")
            false
        }
    }

    /** 累计收到的原始字节数（诊断用） */
    val totalReceivedBytes: Int get() = assembler.totalReceived
}
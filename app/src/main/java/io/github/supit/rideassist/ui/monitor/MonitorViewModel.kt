package io.github.supit.rideassist.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import io.github.supit.rideassist.YybmsAppHolder
import io.github.supit.rideassist.data.prefs.AppPrefs
import io.github.supit.rideassist.data.repo.AlarmEvent
import io.github.supit.rideassist.data.repo.MonitorRepository
import io.github.supit.rideassist.data.repo.TrendData
import io.github.supit.rideassist.core.ble.BleManager
import io.github.supit.rideassist.core.ble.BleScanner
import io.github.supit.rideassist.core.ble.ConnectionState
import io.github.supit.rideassist.core.ble.ScanDevice
import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 实时监控 ViewModel。
 *
 * UI 只读状态、只调方法，不持有任何 Bluetooth API。
 */
class MonitorViewModel(
    private val monitorRepository: MonitorRepository,
    private val bleManager: BleManager,
    private val bleScanner: BleScanner,
    private val prefs: AppPrefs
) : ViewModel() {

    // ==================== 对外状态 ====================

    val realtime: StateFlow<BmsRealtimeData> = monitorRepository.realtime
    val trend: StateFlow<TrendData> = monitorRepository.trend
    val connectionState: StateFlow<ConnectionState> = monitorRepository.connectionState
    val isAutoConnecting: StateFlow<Boolean> = bleManager.isAutoConnecting
    val alarmEvents: SharedFlow<AlarmEvent> = monitorRepository.alarmEvents
    val messages = monitorRepository.messages

    /** 扫描结果，随下拉刷新更新 */
    private val _scanDevices = MutableStateFlow<List<ScanDevice>>(emptyList())
    val scanDevices: StateFlow<List<ScanDevice>> = _scanDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    /** 当前连接设备名，仅在连接成功后写入 */
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _autoConnectJob = MutableStateFlow<Job?>(null)

    val vibrateEnabled: Boolean get() = prefs.vibrateEnabled
    val alertDialogEnabled: Boolean get() = prefs.alertDialogEnabled

    init {
        observeScanResults()
        observeConnection()
    }

    // ==================== 蓝牙状态 ====================

    fun refreshBluetoothState() {
        _isBluetoothEnabled.value = bleScanner.isBluetoothEnabled()
    }

    fun hasScanPermission(): Boolean = bleScanner.hasScanPermission()

    /** 是否已有可自动连接的设备记录 */
    fun hasSavedDevice(): Boolean = prefs.hasSavedDevice

    private val _hasScanPermission = MutableStateFlow(false)
    val hasScanPermissionState: StateFlow<Boolean> = _hasScanPermission.asStateFlow()

    /** 权限状态在 UI 生命周期内会变化（用户去设置页返回），需主动刷新 */
    fun refreshPermissionState() {
        _hasScanPermission.value = bleScanner.hasScanPermission()
    }

    // ==================== 扫描 ====================

    private fun observeScanResults() {
        viewModelScope.launch {
            bleScanner.devices.collect { list ->
                _scanDevices.value = list
            }
        }
        viewModelScope.launch {
            bleScanner.isScanning.collect { scanning ->
                _isScanning.value = scanning
            }
        }
    }

    /** 下拉刷新扫描，支持关键词过滤彦阳设备 */
    fun startScan(keyword: String? = null) {
        refreshBluetoothState()
        bleScanner.startScan(keyword)
    }

    fun stopScan() = bleScanner.stopScan()

    /**
     * 手动连接。**只有连接成功才覆盖保存的设备记录。**
     */
    fun connectToDevice(device: ScanDevice) {
        bleManager.connect(device.macAddress) { success ->
            if (success) {
                prefs.saveConnectedDevice(device.macAddress, device.name)
                _connectedDeviceName.value = device.displayName
            }
        }
    }

    // ==================== 自动连接 ====================

    private fun observeConnection() {
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED && prefs.hasSavedDevice) {
                    _connectedDeviceName.value = prefs.lastDeviceName
                }
            }
        }
    }

    /**
     * 启动 APP 时尝试自动连接上次设备。
     * 后台进行，不弹窗、不阻塞 UI。
     */
    fun startAutoConnectIfNeeded() {
        refreshBluetoothState()
        if (!prefs.autoConnectEnabled) return
        val mac = prefs.lastDeviceMac
        if (mac.isNullOrBlank()) return
        _autoConnectJob.value = bleManager.autoConnect(mac)
    }

    /** 用户点"取消" */
    fun cancelAutoConnect() {
        bleManager.cancelAutoConnect()
        _autoConnectJob.value = null
    }

    fun disconnect() {
        bleManager.disconnect()
        _connectedDeviceName.value = null
    }

    // ==================== 单体详情 ====================

    /** 是否已有单体数据，无数据时详情页展示空态 */
    fun hasCellData(): Boolean = realtime.value.cellVoltages.isNotEmpty()

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = YybmsAppHolder.from(context)
                    return MonitorViewModel(
                        monitorRepository = app.monitorRepository,
                        bleManager = app.bleManager,
                        bleScanner = app.bleScanner,
                        prefs = app.prefs
                    ) as T
                }
            }
    }
}
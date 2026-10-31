package io.github.supit.rideassist.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * BLE 扫描器。
 *
 * 职责边界：只负责"发现设备"，不负责连接。
 * 结果通过 [devices] 流暴露，UI 层订阅即可（下拉刷新触发 [startScan]）。
 */
class BleScanner(private val context: Context) {

    private val _devices = MutableSharedFlow<List<ScanDevice>>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val devices: SharedFlow<List<ScanDevice>> = _devices

    private val _isScanning = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
    val isScanning: SharedFlow<Boolean> = _isScanning

    private val found = LinkedHashMap<String, ScanDevice>()

    /** 名称关键词：null = 不过滤（显示全部）；非空 = 只保留名称含该关键词的设备（模糊匹配） */
    @Volatile
    private var nameFilter: String? = null

    private val bluetoothManager: BluetoothManager? =
        ContextCompat.getSystemService(context, BluetoothManager::class.java)

    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var scanner: BluetoothLeScanner? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i(TAG, "onScanResult: ${result.device.address} name=${result.device.name}")
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            Log.i(TAG, "onBatchScanResults: ${results.size} 个设备")
            results.forEach(::handleResult)
        }

        override fun onScanFailed(errorCode: Int) {
            // 常见：1=已注册过多扫描器，2=内部错误，4=厂商不支持，5=蓝牙关闭
            Log.e(TAG, "onScanFailed: errorCode=$errorCode")
            _isScanning.tryEmit(false)
        }
    }

    companion object {
        private const val TAG = "BleScanner"
    }

    private fun handleResult(result: ScanResult) {
        // ⚠️ 不要因为 scanRecord 为 null 就丢弃：
        // 很多 BLE 保护板模块（如部分彦阳模组）**不广播扫描记录**，
        // 只广播 MAC。早期版本写成 `?: return` 导致这类设备完全扫不到。
        val record = result.scanRecord
        val name = record?.deviceName ?: result.device.name ?: ""
        val mac = result.device.address ?: return

        /**
         * 名称过滤放在这里做**模糊匹配**，而不是用 ScanFilter.setDeviceName()。
         * 后者是精确匹配：设备名 `YY-NBCL-6666` ≠ 关键词 `YY`，
         * 结果是一个设备都扫不到。
         */
        val kw = nameFilter
        if (!kw.isNullOrEmpty() && !name.contains(kw, ignoreCase = true)) return

        // 同 MAC 多次上报保留信号最强的一次
        val device = ScanDevice(
            name = name,
            macAddress = mac,
            rssi = result.rssi,
            isYanYang = BleUuids.isYanYangDevice(name)
        )
        synchronized(found) {
            val old = found[mac]
            if (old == null || old.rssi < device.rssi) {
                found[mac] = device
                _devices.tryEmit(found.values.sortedByDescending { it.rssi })
            }
        }
    }

    /**
     * 蓝牙总开关是否开启。
     * Android 12+ 未授 BLUETOOTH_CONNECT 时调用 adapter.isEnabled() 会抛
     * SecurityException，这里兜住异常返回 false，避免上层拿到错误状态。
     */
    fun isBluetoothEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalStateException) {
        // 蓝牙适配器被系统关闭或正在切换状态
        false
    }

    /** 当前适配器是否可用 */
    fun isBleSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** 连接权限（Android 12+ 需要 BLUETOOTH_CONNECT 才能 getRemoteDevice） */
    fun hasConnectPermission(): Boolean {
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

    /**
     * 权限检查。Android 12+ 需要 BLUETOOTH_SCAN，低版本需要定位权限。
     */
    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 开始扫描。
     * @param keyword 关键词过滤，非空时只上报名称包含关键词的设备
     */
    @SuppressLint("MissingPermission")
    fun startScan(keyword: String? = null) {
        nameFilter = keyword?.trim()?.takeIf { it.isNotEmpty() }
        Log.i(TAG, "startScan: keyword=$keyword, nameFilter=$nameFilter")

        if (!hasScanPermission()) {
            Log.w(TAG, "startScan: 缺少扫描权限，直接返回")
            _isScanning.tryEmit(false)
            return
        }
        val btAdapter = adapter ?: run {
            Log.w(TAG, "startScan: adapter 为空")
            _isScanning.tryEmit(false)
            return
        }
        if (!btAdapter.isEnabled) {
            Log.w(TAG, "startScan: 蓝牙未开启")
            _isScanning.tryEmit(false)
            return
        }
        stopScan()
        synchronized(found) {
            found.clear()
            _devices.tryEmit(emptyList())
        }

        val bleScanner = btAdapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "startScan: bluetoothLeScanner 为空（蓝牙关闭？）")
            _isScanning.tryEmit(false)
            return
        }
        scanner = bleScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        /**
         * ⚠️ 关键：**始终全扫，不加任何 ScanFilter**。
         *
         * 两个踩过的坑：
         * 1. 按服务 UUID 过滤 —— UUID 是推测值，不符就一个都扫不到；
         * 2. 按 `setDeviceName(kw)` 过滤 —— **这是精确匹配**，
         *    设备名 `YY-NBCL-6666` 不等于关键词 `YY`，会被全部过滤掉。
         *
         * 名称过滤改在`handleResult` 里做模糊匹配（contains），
         * 底层多扫一些设备换来的是绝不会漏检。
         */
        val filters: MutableList<ScanFilter> = mutableListOf()

        try {
            bleScanner.startScan(filters, settings, scanCallback)
            _isScanning.tryEmit(true)
            Log.i(TAG, "startScan: 已启动, filters=${filters.size}, 全扫=${filters.isEmpty()}")
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan: SecurityException ${e.message}")
            _isScanning.tryEmit(false)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startScan: IllegalStateException ${e.message}")
            _isScanning.tryEmit(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.let {
            try {
                it.stopScan(scanCallback)
            } catch (e: SecurityException) {
                // 权限被回收时静默处理
            }
        }
        scanner = null
        _isScanning.tryEmit(false)
    }
}
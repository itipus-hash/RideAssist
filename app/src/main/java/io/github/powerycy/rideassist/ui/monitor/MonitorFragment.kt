package io.github.powerycy.rideassist.ui.monitor

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.powerycy.rideassist.R
import io.github.powerycy.rideassist.core.ble.ConnectionState
import io.github.powerycy.rideassist.core.ble.tpms.TpmsMonitor
import io.github.powerycy.rideassist.core.protocol.model.BmsRealtimeData
import io.github.powerycy.rideassist.data.prefs.AppPrefs
import io.github.powerycy.rideassist.databinding.FragmentMonitorBinding
import io.github.powerycy.rideassist.ui.diag.DiagActivity
import io.github.powerycy.rideassist.ui.main.MainActivity
import io.github.powerycy.rideassist.util.VibrateHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 实时监控页。只读展示，不含任何参数设置入口。
 */
class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MonitorViewModel by viewModels {
        MonitorViewModel.factory(requireContext())
    }

    /** 记录权限是否发起过申请，用于区分首次与永久拒绝 */
    private val prefs by lazy {
        requireContext().getSharedPreferences("perm_state", android.content.Context.MODE_PRIVATE)
    }

    // adapter 一律使用 val + 字段初始化，不使用 lateinit，
    //从根本上避免"声明了但忘了实例化"导致的运行期崩溃。
    private val deviceAdapter = DeviceListAdapter { device ->
        // 点击后：停扫描 → 关闭浮窗（用户立即看到反馈）→ 连接
        viewModel.stopScan()
        dismissDeviceDialog()
        viewModel.connectToDevice(device)
    }

    /** 刚结束扫描（用于决定是否显示空态提示） */
    private var scanningJustFinished = false

    // ==================== 当前时间（HH:MM） ====================

    /**
     * 时钟刷新句柄。
     *
     * 用 [android.os.Handler] 而非 Timer：`Timer` 会创建独立线程并持有 Runnable，
     * 而 Runnable 捕获了 Fragment → 视图销毁后仍在跑，直接泄漏。
     * Handler 跟随 Looper，在 `onDestroyView` 里 removeCallbacks 即可彻底停止。
     */
    private val clockHandler = Handler(Looper.getMainLooper())

    /**
     * 24 小时制格式化器。
     *
     * ⚠️ pattern 里的小写 `mm` 是**分钟**，`MM` 才是月份，这里写错会显示成日期。
     * "HH:mm" 里的 HH 是 24 小时制（hh 是 12 小时制），与用户要求一致。
     */
    private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ==================== 权限与蓝牙 ====================

    /**
     * 权限申请回调。
     * 注意：不能用 `any { it }` 判定成败——扫描与连接是两个独立权限，
     * 只要扫描通过就必须继续，否则用户表现为"扫不到设备"。
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissionState()
        when {
            hasScanPermission() -> {
                // ensureBluetoothThenConnect 内部已处理"有无历史设备"，
                // 这里**不能**再调showScanIfEmpty()——否则会与自动连接并发抢射频
                ensureBluetoothThenConnect()
            }
            isPermanentlyDenied() -> showGoToSettingsDialog()
            else -> showPermissionRationaleDialog()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.refreshBluetoothState()
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            ensureBluetoothThenConnect()
        } else if (!viewModel.isBluetoothEnabled.value) {
            showBluetoothDisabledDialog()
        }
    }

    /** 从系统设置返回时重新检测权限与蓝牙 */
    private val appSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissionState()
        viewModel.refreshBluetoothState()
        if (hasScanPermission()) ensureBluetoothThenConnect() else showPermissionRationaleDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupScanList()
        setupClickListeners()
        observeState()
        onPermissionChainStart()
        startClock()
        // ⭐ 单击电池参数卡进入诊断面板（隐藏入口）
        setupDiagGesture()
        setupTpms()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBluetoothState()
        // 回前台立即校时，避免显示的是暂停前的时间
        startClock()
    }

    // ==================== 当前时间（HH:MM，24 小时制） ====================

    /**
     * 每秒刷新一次时钟。
     *
     * 用 `postDelayed` 递归而非 `Timer`/`while` 循环：
     * - [android.os.Handler] 跟随 Fragment 生命周期，`onDestroyView` 里 removeCallbacks
     *   即可彻底停止，不会像 Timer 那样持有已销毁的 View 造成泄漏。
     * - 1 秒间隔而不是对齐整分钟：整点跳变会让人以为界面卡住。
     *
     * ⚠️ MainActivity 用 show/hide 切页，本页被 hide 时 onPause 不会走，
     * 所以这里在 [onHiddenChanged] 里也做一次开关管理，避免后台空转。
     */
    private fun startClock() {
        val b = _binding ?: return
        clockHandler.removeCallbacks(clockTick)
        renderClock()
        clockHandler.postDelayed(clockTick, CLOCK_INTERVAL_MS)
    }

    private fun stopClock() {
        clockHandler.removeCallbacks(clockTick)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            renderClock()
            clockHandler.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    /** 渲染当前时间。24 小时制由 pattern "HH:mm" 保证，不受系统 12/24 制设置影响。 */
    private fun renderClock() {
        val b = _binding ?: return
        b.tvTpmsClock.text = TIME_FORMAT.format(Date())
        // 搭时钟的每秒顺风车刷新胎压「N分钟前」相对时间
        refreshTpmsTimes()
    }

/**
     * ⭐⭐ **点击「电池参数」卡 → 进入诊断面板**。
     *
     * ## 为什么放在这里
     * 诊断面板是纯调试入口，不该出现在设置页里（那里全是用户关心的开关）。
     * 但设置入口依赖 Toolbar 菜单，而 `Theme.Material3.DayNight.NoActionBar`
     * 下 MainActivity 用的是布局里的 MaterialToolbar，实测该菜单图标不可达。
     *
     * 「电池参数」卡与诊断内容同源（都是保护板读数），语义上最贴切。
     *
     * ## 为什么是单击而不是长按
     * 长按方案（GestureDetector.onLongPress）实机时灵时不灵 ——
     * 卡片内的子 TextView 会拦截部分区域的触摸，长按命中不稳定。
     * 单击 + `setOnClickListener` 由框架分发到所有子区域，**没有这个坑**。
     * 该卡没有其它点击行为，不会冲突。
     *
     * ⚠️ 监听绑在**整张卡**上，不是某个 TextView ——
     * 卡内还有横向子布局，绑子控件会漏掉空白区域。
     */
    private fun setupDiagGesture() {
        val b = _binding ?: return
        b.cardBatteryParams.setOnClickListener {
            // 震动反馈：确认「点到了」，否则用户不知道有没有生效。
            // ⚠️ 绕过「震动提醒」开关 —— 那是给告警用的，
            // 交互确认反馈性质不同。
            VibrateHelper.vibrate(requireContext(), enabled = true)
            startActivity(Intent(requireContext(), DiagActivity::class.java))
        }
    }

    // ==================== 胎压监测（TPMS） ====================

    /**
     * 胎压卡：订阅 [TpmsMonitor] 状态 + 长按绑定。
     *
     * ## 绑定交互
     * - 未绑定轮位：长按 → 直接进入 30s 绑定窗口（卡片上显示倒计时）
     * - 已绑定轮位：长按 → 弹窗三选：解绑 / 重新绑定 / 取消
     *
     * ⚠️ 绑定窗口内出现的第一颗未绑定传感器会被绑上 —— 绑定前确认
     * 另一颗传感器已绑定或已远离（协议本身无法区分「哪颗是你要的」）。
     */
    private fun setupTpms() {
        val app = io.github.powerycy.rideassist.YybmsAppHolder.from(requireContext())
        val monitor = app.tpmsMonitor

        binding.layoutTpmsFront.setOnLongClickListener { onTpmsLongPress(TpmsMonitor.POS_FRONT); true }
        binding.layoutTpmsRear.setOnLongClickListener { onTpmsLongPress(TpmsMonitor.POS_REAR); true }

        // 阈值配置入口 → 设置页（Toolbar 菜单实机不可达的补偿路径）
        binding.btnTpmsSettings.setOnClickListener {
            startActivity(Intent(requireContext(), io.github.powerycy.rideassist.ui.settings.SettingsActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    monitor.state.collect { renderTpms(it) }
                }
                launch {
                    monitor.events.collect { msg ->
                        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun onTpmsLongPress(pos: Int) {
        val app = io.github.powerycy.rideassist.YybmsAppHolder.from(requireContext())
        val monitor = app.tpmsMonitor
        val prefs = app.prefs
        val posName = getString(if (pos == TpmsMonitor.POS_FRONT) R.string.tpms_front else R.string.tpms_rear)
        val boundSn = if (pos == TpmsMonitor.POS_FRONT) prefs.tpmsFrontSn else prefs.tpmsRearSn

        if (boundSn.isNullOrBlank()) {
            // 未绑定：直接进入绑定窗口（引导把手机靠近目标车轮，RSSI 识别）
            VibrateHelper.vibrate(requireContext(), enabled = true)
            monitor.startBinding(pos)
            Toast.makeText(
                requireContext(),
                getString(R.string.tpms_bind_guide, posName),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        // 已绑定：解绑 / 重新绑定 / 交换前后轮（两颗都绑时才有意义）
        val options = mutableListOf(
            getString(R.string.tpms_action_unbind),
            getString(R.string.tpms_action_rebind),
        )
        val bothBound = !prefs.tpmsFrontSn.isNullOrBlank() && !prefs.tpmsRearSn.isNullOrBlank()
        if (bothBound) options.add(getString(R.string.tpms_action_swap))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.tpms_bound_title, posName, boundSn.takeLast(4)))
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.tpms_action_unbind) -> {
                        monitor.unbind(pos)
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.tpms_unbound_done, posName),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    getString(R.string.tpms_action_rebind) -> {
                        monitor.startBinding(pos)
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.tpms_bind_guide, posName),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    getString(R.string.tpms_action_swap) -> {
                        monitor.swapPositions()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.tpms_swap_done),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 渲染胎压卡。阈值越界标红（阈值存 [AppPrefs]，设置页可改）。 */
    private fun renderTpms(state: TpmsMonitor.UiState) {
        val b = _binding ?: return
        val app = io.github.powerycy.rideassist.YybmsAppHolder.from(requireContext())
        val prefs = app.prefs
        val abnormalColor = ContextCompat.getColor(requireContext(), R.color.abnormal)
        val normalColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
        val secondaryColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        fun renderHalf(
            pos: Int,
            pressureView: android.widget.TextView,
            unitView: android.widget.TextView,
            updateView: android.widget.TextView,
        ) {
            val sn = if (pos == TpmsMonitor.POS_FRONT) prefs.tpmsFrontSn else prefs.tpmsRearSn
            val bindingNow = state.binding?.takeIf { it.pos == pos }
            val reading = sn?.let { state.readings[it] }

            when {
                bindingNow != null -> {
                    pressureView.text = getString(R.string.tpms_binding_fmt, bindingNow.remainSec)
                    pressureView.setTextColor(secondaryColor)
                    unitView.visibility = View.GONE
                    updateView.text = ""
                }
                // ⭐ 三态区分（用户反馈：已绑但收不到包时错误显示「未匹配」）：
                // 未绑 = 未匹配；已绑但无包 = 无数据；有包 = 数值
                sn.isNullOrBlank() -> {
                    pressureView.text = getString(R.string.tpms_unmatched)
                    pressureView.setTextColor(secondaryColor)
                    unitView.visibility = View.GONE
                    updateView.text = ""
                }
                reading == null -> {
                    pressureView.text = getString(R.string.tpms_no_data_short)
                    pressureView.setTextColor(secondaryColor)
                    unitView.visibility = View.GONE
                    updateView.text = ""
                }
                else -> {
                    pressureView.text = String.format(Locale.US, "%.2f", reading.bar)
                    unitView.visibility = View.VISIBLE
                    // ⭐ 阈值判定（DJTPMS 同款口径）：bar < 低压 或 > 高压；温度 > 高温
                    val pressureAlarm = reading.bar < prefs.tpmsLowBar || reading.bar > prefs.tpmsHighBar
                    pressureView.setTextColor(if (pressureAlarm) abnormalColor else normalColor)
                    updateView.text = relativeUpdateText(reading.timestampMs)
                }
            }
        }

        renderHalf(
            TpmsMonitor.POS_FRONT,
            b.tvTpmsFrontPressure, b.tvTpmsFrontUnit, b.tvTpmsFrontUpdate,
        )
        renderHalf(
            TpmsMonitor.POS_REAR,
            b.tvTpmsRearPressure, b.tvTpmsRearUnit, b.tvTpmsRearUpdate,
        )
        // 保留最新状态供每秒 ticker 刷新相对时间（「1分钟前」要走得动）
        lastTpmsState = state
    }

    /** 最近一次 [TpmsMonitor.UiState]，供时钟 ticker 每秒刷新更新时间行 */
    private var lastTpmsState: TpmsMonitor.UiState? = null

    /**
     * 相对时间文案（用户要求）：10 分钟内用「刚刚 / N分钟前」，
     * 超过 10 分钟显示绝对时刻 HH:mm:ss。
     */
    private fun relativeUpdateText(ts: Long): String {
        val diffMin = (System.currentTimeMillis() - ts) / 60_000L
        return when {
            diffMin < 1L -> getString(R.string.tpms_update_just)
            diffMin < 10L -> getString(R.string.tpms_update_min_ago, diffMin.toInt())
            else -> getString(
                R.string.tpms_update_fmt,
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts)),
            )
        }
    }

    /** 由每秒时钟 ticker 调用：仅刷新两行更新时间，不动其它内容 */
    private fun refreshTpmsTimes() {
        val b = _binding ?: return
        val state = lastTpmsState ?: return
        val front = prefsTpmsSn(TpmsMonitor.POS_FRONT)?.let { state.readings[it] }
        val rear = prefsTpmsSn(TpmsMonitor.POS_REAR)?.let { state.readings[it] }
        b.tvTpmsFrontUpdate.text = front?.let { relativeUpdateText(it.timestampMs) } ?: ""
        b.tvTpmsRearUpdate.text = rear?.let { relativeUpdateText(it.timestampMs) } ?: ""
    }

    private fun prefsTpmsSn(pos: Int): String? =
        io.github.powerycy.rideassist.YybmsAppHolder.from(requireContext()).prefs.let {
            if (pos == TpmsMonitor.POS_FRONT) it.tpmsFrontSn else it.tpmsRearSn
        }

    /**
     * show/hide 切页时被 hide → 停表；切回→ 重新对时。
     * 与 [ensureBluetoothThenConnect] 里同样的生命周期处理思路一致。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) stopClock() else startClock()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // ⚠️ 必须先关掉浮窗再置空 binding：Dialog 持有 Activity context，
        // 不主动 dismiss 会造成窗口泄漏（切页后浮窗还留在屏幕上）
        dismissDeviceDialog()
        viewModel.stopScan()
        stopClock()
        _binding = null
    }

    // ==================== 启动流程 ====================

    /**
     * 权限链路：**必须先拿权限，再检测蓝牙**。
     * Android 12+ 未授 BLUETOOTH_CONNECT 时调用 adapter.isEnabled() 会抛
     * SecurityException，若先检测蓝牙就会表现为"扫不到设备且无任何提示"。
     */
    private fun onPermissionChainStart() {
        viewModel.refreshPermissionState()
        viewModel.refreshBluetoothState()
        renderPermissionBanner()

        if (hasScanPermission()) {
            ensureBluetoothThenConnect()
        } else if (isPermanentlyDenied()) {
            showGoToSettingsDialog()
        } else {
            markPermissionAsked()
            permissionLauncher.launch(requiredPermissions())
        }
    }

    /**
     * 权限已就绪，检查蓝牙开关。
     *
     * ⭐⭐ **启动时绝不触发任何扫描**——与官方 `InfoActivity_YY` 完全一致：
     * ```
     * // onCreate里唯一的动作
     * if (!mBleConnectedState && first_into) { first_into = false; autoConnectBle(true); }
     *
     * // autoConnectBle：SP 无 MAC 就直接 return，什么都不做
     * String mac = SPStaticUtils.getString("bt_device_address");
     * if (TextUtils.isEmpty(mac) || !Get_Permission()) return;   // ← 空则静默放弃
     * connectBtDevice(mac, isAutoConn);
     *
     * // connectBtDevice 第一行就是停扫
     * MyApplication.mClient.stopSearch();
     * ```
     *
     * 官方的扫描只发生在**用户主动点开设备列表弹窗**时（`onShow` 里调`scanBtDevice()`），
     * 启动路径上零扫描。实测对比：官方一打开就连接成功，我们启动扫描会抢射频。
     *
     * @param allowScanFallback 自动连接失败后是否退回扫描。**仅首次连接允许**，
     *        之后每次进页面都不扫（保持与官方一致的静默失败语义）
     */
    private fun ensureBluetoothThenConnect(allowScanFallback: Boolean = true) {
        viewModel.refreshBluetoothState()
        if (!viewModel.isBluetoothEnabled.value) {
            showBluetoothDisabledDialog()
            return
        }

        if (!viewModel.hasSavedDevice()) {
            // ⭐ 无历史设备：**不扫描、不连接**（官方 `TextUtils.isEmpty` → return）
            // 静默停在空态，等用户主动点设备列表再扫。
            // 之前这里直接 doScan()，导致首次安装/清数据后一进 APP 就抢射频。
            return
        }

        // MainActivity 用 show/hide 切页，切回本页会再走一次这里。
        // 已在连接中/已连接就**不要重新发起**，否则每次切页都重启一轮
        // 连接，既打断当前连接，又让状态在页面上来回跳。
        val st = viewModel.connectionState.value
        if (st == ConnectionState.CONNECTING || st == ConnectionState.CONNECTED) return

        viewModel.startAutoConnectIfNeeded()

        if (!allowScanFallback) return

        // 自动连接失败后才放开扫描，避免用户对着空白页不知道下一步。
        // ⚠️ 用 `take(1)` 只取第一次 FAILED，**不能**持续 collect：
        // 否则每次状态变 FAILED 都扫（含切页回来时状态仍是 FAILED），
        // 等于变相自动扫描，抢射频且与「启动零扫描」相悖。
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.first { it == ConnectionState.FAILED }
            doScan()
        }
    }

    /**
     * 统一扫描入口。
     *
     * ⚠️⚠️ **扫描与连接绝不能并发**：BLE 扫描占用射频，会导致 GATT 连接必然失败
     * （实测日志中出现 onScanResult 与"连接尝试"交错输出，之后 status=8 超时）。
     * 因此任何扫描动作前必须先 stopScan()。
     *
     * @param fromUser true=用户主动触发（点扫描/下拉刷新）
     */
    private fun doScan(fromUser: Boolean = false) {
        val st = viewModel.connectionState.value
        // 连接中/已连接 → 不扫描（fromUser 也一样，除非连接已结束）
        if (st == ConnectionState.CONNECTING || st == ConnectionState.CONNECTED) return

        // 先停掉可能存在的扫描，确保射频空闲
        viewModel.stopScan()

        // 固定只扫名称含 YY 的设备（用户需求：默认仅显示 YY，不提供"不过滤"）
        viewModel.startScan(DEFAULT_KEYWORD)
    }

    // 已删除 showScanIfEmpty()：它会在设备列表为空时**自动**开始扫描，
    // 与「启动零扫描」的目标冲突。扫描现在只有一个入口——用户点「扫描」按钮。

    /** 扫描区显隐：连接中/已连接时隐藏「扫描」按钮，避免误触 */
    private fun renderEmptyState() {
        // 设备列表已改为对话框显示；「扫描」按钮并入连接状态标题行（用户要求省空间）
        val st = viewModel.connectionState.value
        binding.btnScan.visibility =
            if (st == ConnectionState.CONNECTED || st == ConnectionState.CONNECTING) {
                View.GONE
            } else {
                View.VISIBLE
            }
    }

    private fun showBluetoothDisabledDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bluetooth_off_title)
            .setMessage(R.string.bluetooth_off_msg)
            .setPositiveButton(R.string.to_enable) { _, _ ->
                runCatching {
                    bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }.onFailure {
                    Snackbar.make(binding.root, R.string.bluetooth_off_msg, Snackbar.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .show()
    }

    /**
     * 当前版本需要的运行时权限。
     * Android 12+：扫描 + 连接
     * Android 11 及以下：定位（BLE 扫描硬性要求）
     */
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasScanPermission(): Boolean = viewModel.hasScanPermission()

    /**
     * 是否已被"永久拒绝"（用户勾选了"不再询问"）。
     *
     * ⚠️ 不能只用 shouldShowRequestPermissionRationale() 判断：
     * 该方法在**从未申请过**的权限上同样返回 false，
     * 会把"首次安装"误判成"永久拒绝"，导致系统弹窗根本不弹、直接跳设置页——
     * 表现为"点了去授权但无法授权"。
     *
     * 正确判定 = 未授权 && 已申请过 && 系统不再要求说明。
     */
    private fun isPermanentlyDenied(): Boolean {
        val askedBefore = prefs.getBoolean(KEY_PERM_ASKED, false)
        if (!askedBefore) return false

        return requiredPermissions().any { perm ->
            val granted = ContextCompat.checkSelfPermission(
                requireContext(), perm
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            !granted && !shouldShowRequestPermissionRationale(perm)
        }
    }

    /** 标记"已向用户发起过权限申请"，供后续区分首次/永久拒绝 */
    private fun markPermissionAsked() {
        prefs.edit().putBoolean(KEY_PERM_ASKED, true).apply()
    }

    /** 权限被拒但可再次申请：解释用途后重新申请 */
    private fun showPermissionRationaleDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.perm_required)
            .setMessage(R.string.perm_bt_msg)
            .setPositiveButton(R.string.grant_now) { _, _ ->
                markPermissionAsked()
                permissionLauncher.launch(requiredPermissions())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 权限被永久拒绝：只能引导到系统设置页手动开启 */
    private fun showGoToSettingsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.perm_required)
            .setMessage(R.string.perm_settings_msg)
            .setPositiveButton(R.string.to_settings) { _, _ ->
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + requireContext().packageName)
                )
                appSettingsLauncher.launch(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .show()
    }

    /** 顶部横幅：权限缺失时给出常驻可见的提示（不是只弹一次窗） */
    private fun renderPermissionBanner() {
        val missing = !hasScanPermission()
        binding.permissionBanner.visibility = if (missing) View.VISIBLE else View.GONE
        if (missing) {
            binding.btnBannerAction.setOnClickListener {
                if (isPermanentlyDenied()) showGoToSettingsDialog() else showPermissionRationaleDialog()
            }
        }
    }

    // ==================== 扫描列表 ====================

    private fun setupScanList() {
        // 首页不再展示每串电压列表（用户要求），单体详情页才看
    }

    /** 弹出设备列表对话框（仅在用户主动扫描时调用） */
    private fun showDeviceDialog() {
        val ctx = requireContext()
        val rv = androidx.recyclerview.widget.RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = deviceAdapter
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -2)
        }
        val hint = android.widget.TextView(ctx).apply {
            setText(R.string.scanning)
            setPadding(16, 8, 16, 8)
        }
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(hint)
            addView(rv)
        }
        // ⭐ 「仅显示名称含YY的保护板」直接作为弹窗标题（用户要求），
        //    原标题「选择设备」与弹窗内提示行都已去掉
        scanHint = hint
        deviceDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.scan_yy_only_hint)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                scanHint = null
                viewModel.stopScan()
            }
            .create()
        deviceDialog?.show()
        doScan(fromUser = true)
    }

    private var deviceDialog: androidx.appcompat.app.AlertDialog? = null
    private var scanHint: android.widget.TextView? = null

    /**
     * 检查权限与蓝牙开关后，弹出设备列表对话框。
     *
     * 由两个入口复用：
     * - 点击「扫描」按钮
     * - **长按「已连接」卡片**（需求：连接成功后长按即可换连别的保护板）
     */
    private fun requestScanAndShowDialog() {
        if (!viewModel.hasScanPermission()) {
            if (isPermanentlyDenied()) showGoToSettingsDialog()
            else showPermissionRationaleDialog()
            return
        }
        if (!viewModel.isBluetoothEnabled.value) {
            showBluetoothDisabledDialog()
            return
        }
        showDeviceDialog()
    }

    private fun setupClickListeners() {
        // 下拉刷新扫描（用户主动）

        // 扫描按钮 → 弹出设备列表对话框
        binding.btnScan.setOnClickListener { requestScanAndShowDialog() }

        // ⭐ 长按「已连接」卡片 → 弹出蓝牙扫描，方便换连别的保护板
        //（需求："首页连接成功后，允许长按已连接这个格子，然后弹出蓝牙扫描"）
        binding.cardConnection.setOnLongClickListener {
            // ⚠️ 必须在**已连接**时才允许 —— 未连接时长按无意义
            //（用户没连接就长按，弹扫描框反而让人困惑）
            if (viewModel.connectionState.value != ConnectionState.CONNECTED) {
                Snackbar.make(binding.root, R.string.only_when_connected,
                    Snackbar.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            // 反馈一下（否则用户不知道长按被识别了）
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            requestScanAndShowDialog()
            true
        }

        // 取消自动连接
        binding.btnCancelAuto.setOnClickListener {
            viewModel.cancelAutoConnect()
            Snackbar.make(binding.root, R.string.auto_connect_cancelled, Snackbar.LENGTH_SHORT).show()
        }

        // 点击单体电芯卡片 → 进入压差详情页
        binding.cardCells.setOnClickListener {
            if (viewModel.hasCellData()) {
                (activity as? MainActivity)?.openCellDetail()
            } else {
                Snackbar.make(binding.root, R.string.no_cell_data, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 状态订阅 ====================

    private fun observeState() {
        // 连接状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        renderConnectionState(state)
                    }
                }
                // 实时数据
                launch {
                    viewModel.realtime.collect { data ->
                        renderRealtime(data)
                    }
                }
                // 设备名只在标题栏显示一次，状态栏不再重复
                launch {
                    viewModel.connectedDeviceName.collect { name ->
                        binding.tvDeviceName.text = name.orEmpty()
                    }
                }
                // 自动连接中：仅在自动连接过程中显示提示，结束后交回连接状态渲染
                launch {
                    viewModel.isAutoConnecting.collect { auto ->
                        binding.btnCancelAuto.visibility = if (auto) View.VISIBLE else View.GONE
                        if (auto) {
                            binding.tvStatus.text = getString(R.string.auto_connecting)
                        } else {
                            // ⚠️ 不能写成 `binding.tvStatus.text = binding.tvStatus.text`，
                            // 那是自赋值，等于没改，文字会永远停在"正在自动连接"
                            binding.tvStatus.text = getString(R.string.state_disconnected)
                            renderConnectionState(viewModel.connectionState.value)
                        }
                    }
                }
                // 扫描结果
                launch {
                    viewModel.scanDevices.collect { list ->
                        deviceAdapter.submitList(list)
                        renderEmptyState()
                        scanHint?.visibility =
                            if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
                launch {
                    viewModel.isScanning.collect { scanning ->
                        renderEmptyState()
                    }
                }
                // 提示消息
                launch {
                    viewModel.messages.collect {
                        Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show()
                    }
                }
                // 告警事件：弹窗 + 震动（历史日志已按需求移除）
                launch {
                    viewModel.alarmEvents.collect { event ->
                        VibrateHelper.vibrate(requireContext(), viewModel.vibrateEnabled)
                        if (viewModel.alertDialogEnabled) {
                            showAlarmDialog(event)
                        }
                    }
                }
            }
        }
    }

    private fun showAlarmDialog(event: io.github.powerycy.rideassist.data.repo.AlarmEvent) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.alarm_title)
            .setMessage("${event.type}\n\n${event.detail}")
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun renderConnectionState(state: ConnectionState) {
        binding.tvConnectionState.text = state.displayText
        binding.tvConnectionState.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when (state) {
                    ConnectionState.CONNECTED -> R.color.state_connected
                    ConnectionState.CONNECTING -> R.color.state_connecting
                    ConnectionState.FAILED -> R.color.state_failed
                    ConnectionState.DISCONNECTED -> R.color.state_disconnected
                }
            )
        )

        // ⭐ 电池状态卡只在**已连接**时显示。
        // 用户要求"在建立连接之前不显示电池状态这一个格子"——
        // 没有真实数据时那张卡只会显示「--」，纯属占位噪音。
        binding.cardBatteryState.visibility =
            if (state == ConnectionState.CONNECTED) View.VISIBLE else View.GONE

        // 连接中显示进度条 + 文字提示，让用户明确知道点击已生效
        val connecting = state == ConnectionState.CONNECTING
        binding.pbConnecting.visibility = if (connecting) View.VISIBLE else View.GONE

        // 状态栏文案必须与状态一致，避免出现"连接失败"却仍显示"正在连接"的矛盾
        // ⚠️ FAILED/DISCONNECTED 一律**空串**：用户要求不再显示
        //「连接失败，请靠近设备后重试」这句 —— 上方「连接状态」已经写了"连接失败"，
        // 再加一句引导语是冗余，且会让人误以为必须手动重试。
        val hint = when (state) {
            ConnectionState.CONNECTING -> getString(R.string.connecting_hint)
            ConnectionState.CONNECTED -> getString(R.string.connected_hint)
            // FAILED / DISCONNECTED：不显示任何提示文字
            ConnectionState.FAILED, ConnectionState.DISCONNECTED -> ""
        }
        binding.tvStatus.text = hint
        binding.tvStatus.visibility = if (hint.isBlank()) View.GONE else View.VISIBLE

        if (state == ConnectionState.CONNECTED) {
            // 已连接：停止自动扫描（避免持续扫描耗电与干扰通讯）
            viewModel.stopScan()
        }

        // ⚠️⚠️ 无论什么状态都要刷新扫描区显隐。
        // 之前只在 CONNECTED / 扫描列表变化时调用，导致**连接失败后
        // 「扫描」按钮永久消失**，用户既看不到设备也没法重试，只能杀进程重开。
        // 失败态恰恰最需要这个按钮。
        renderEmptyState()

        // ⭐ 连接失败时**不自动重扫**，只保证「扫描」按钮可见。
        // 之前这里调 doScan()，导致：
        // 1. 每次 renderState(FAILED) 都扫（含切页回来时状态仍是 FAILED），
        //    等于变相自动扫描，与官方「启动零扫描」相悖；
        // 2. 抢射频，直接降低重连成功率。
        // 现在统一由用户点「扫描」按钮触发（onScanClicked → doScan(fromUser = true)）。

        // ⚠️ 一旦开始连接（连接中或已连接），立刻关掉「选择设备」浮窗。
        // 否则用户点了某个设备后浮窗还挡在界面上，既挡住状态栏也容易误点其他条目。
        if (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) {
            dismissDeviceDialog()
        }
    }

    /**
     * 关闭设备选择浮窗（若已显示）。
     *
     * 用 [androidx.appcompat.app.AlertDialog.dismiss] 而非 `cancel()`：
     * dismiss 不触发 onCancel，只走 onDismiss → 那里已经处理了 stopScan()。
     * 同时把引用置空，避免重复 dismiss。
     */
    private fun dismissDeviceDialog() {
        val d = deviceDialog ?: return
        deviceDialog = null
        if (d.isShowing) {
            runCatching { d.dismiss() }
        }
    }

    /**
     * 渲染功率区。
     * 约定（用户需求）：
     * - 功率 > 0 → 标签「放电中」，显示数值
     * - 功率 = 0 → **标签也不显示**（用户反馈"电流是0的时候不显示空闲中这几个字"）
     * - 功率 < 0 → 标签「充电中」，显示数值
     */
    private fun renderPower(data: BmsRealtimeData) {
        val ctx = requireContext()
        val w = data.power
        when {
            w > 0.05f -> {
                binding.tvPowerLabel.setText(R.string.power_discharging)
                binding.tvPower.text = getString(R.string.format_power, w)
                binding.tvPower.setTextColor(ContextCompat.getColor(ctx, R.color.state_discharging))
            }
            w < -0.05f -> {
                binding.tvPowerLabel.setText(R.string.power_charging)
                binding.tvPower.text = getString(R.string.format_power, -w)
                binding.tvPower.setTextColor(ContextCompat.getColor(ctx, R.color.state_charging))
            }
            else -> {
                // 空闲：标签与数值都不显示，格子留空
                binding.tvPowerLabel.text = ""
                binding.tvPower.text = ""
            }
        }
        renderChargeEta(data)
    }

    /**
     * 充电倒计时：仅在充电中且设备提供该数据时显示「XX 分钟后充满」。
     * 数据源 [BmsRealtimeData.chargeMinutesLeft]（官方 bArr[86..87] 大端 u16）。
     */
    private fun renderChargeEta(data: BmsRealtimeData) {
        val charging = data.power < -0.05f
        val mins = data.chargeMinutesLeft
        // ⭐ 防「骑行动能回收」误报：**最近 60 秒内有 ≥1A 放电帧就不显示**。
        // 不能用 isRecording——它的停止条件是「断连 3 分钟」，骑行结束立刻
        // 插充电器时 BMS 还连着，锁存会保持 true 导致 ETA 永远出不来。
        // 骑行中放电帧持续存在（隐藏）；插充电器后最后一波放电 60s 过期（显示）。
        val tracker = io.github.powerycy.rideassist.YybmsAppHolder.from(requireContext()).sessionTracker
        val recentDischarge = tracker.lastDischargeWallMs > 0L &&
            System.currentTimeMillis() - tracker.lastDischargeWallMs < 60_000L
        val show = charging && mins > 0 && !recentDischarge
        binding.tvChargeEta.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.tvChargeEta.text = getString(R.string.charge_eta_fmt, mins)
        }
    }

    /** 渲染实时数据，异常标红 */
    private fun renderRealtime(data: BmsRealtimeData) {
        // ---- 电池总参数 ----
        binding.tvVoltage.text = getString(R.string.format_voltage, data.totalVoltage)
        //⭐ 电流显示取绝对值（官方 APP 也不带负号），充电/放电方向由颜色与功率区标签表达
        val raw = data.totalCurrent
        val amps = abs(raw)
        // 空闲（≈0）时不显示小数点，跟电压一样显示「0V/  0A」
        binding.tvCurrent.text = if (amps < 0.05f) {
            getString(R.string.current_idle_fmt)
        } else {
            getString(R.string.format_current, amps)
        }
        // 配色：充电=绿 / 放电=红 / 空闲=黑（与电压同色）
        binding.tvCurrent.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    amps < 0.05f -> R.color.text_primary   // 空闲：黑
                    raw < 0f -> R.color.state_charging     // 充电：绿
                    else -> R.color.state_discharging      // 放电：红
                }
            )
        )
        // 功率区：标签随功率方向变化；空闲时不显示数值
        renderPower(data)
        binding.tvSoc.text = getString(R.string.format_percent, data.soc)
        binding.tvCapacity.text = getString(R.string.format_capacity, data.remainingCapacity)
        binding.tvRatedCapacity.text = getString(R.string.format_capacity, data.ratedCapacity)

        // ---- 单体电芯 ----
        // ⭐ 显示「电池类型 / 串数」，与官方 APP 一致（例：三元 / 17串）。
        //只用设备上报的 batType 字段（0x01 实时帧 bArr[149]），
        // **不做电压推断** —— 单体电压随 SOC 浮动，铁锂充满可冲到 3.65V，
        // 靠电压猜化学体系不可靠。
        binding.tvCellCount.text = if (data.batteryType >= 0) {
            getString(R.string.format_cell_count_with_type, data.batteryTypeName, data.cellCount)
        } else {
            getString(R.string.format_cell_count, data.cellCount)
        }
        binding.tvCellMax.text = getString(R.string.format_voltage_mv, data.maxCellVoltage)
        binding.tvCellMin.text = getString(R.string.format_voltage_mv, data.minCellVoltage)

        // 压差超标标红（阈值 80mV）
        val diffAbnormal = data.cellDiff > CELL_DIFF_THRESHOLD_MV
        binding.tvCellDiff.text = getString(R.string.format_diff, data.cellDiff)
        binding.tvCellDiff.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (diffAbnormal) R.color.abnormal else R.color.text_primary
            )
        )

        // ---- 温度 ----
        binding.tvTemp1.text = data.temp1?.let { getString(R.string.format_temp, it) }
            ?: getString(R.string.temp_none)
        binding.tvTemp2.text = data.temp2?.let { getString(R.string.format_temp, it) }
            ?: getString(R.string.temp_none)
        binding.tvMosTemp.text = data.mosTemp?.let { getString(R.string.format_temp, it) }
            ?: getString(R.string.temp_none)
        // 超温(>60℃) / 低温(<-10℃) 标红
        binding.tvTemp1.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (data.temp1 != null && (data.temp1 > 60f || data.temp1 < -10f))
                    R.color.abnormal else R.color.text_primary
            )
        )
        binding.tvTemp2.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (data.temp2 != null && (data.temp2 > 60f || data.temp2 < -10f))
                    R.color.abnormal else R.color.text_primary
            )
        )

        // ---- 电池状态 ----
        // ⚠️⚠️ 三态严格区分，缺一不可：
        //   1) hasDeviceData=false → 「--」  不知道（断连/未连接）
        //   2) 有数据且有效告警空 → 「正常」  设备确实上报了 0 位域
        //   3) 有数据且有告警 → 列出具体告警项
        // 混淆 1 和 2 就是误导用户以为设备没事。
        if (!data.hasDeviceData) {
            binding.tvAlarmTags.text = getString(R.string.state_unknown_fmt)
            binding.tvAlarmTags.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_tertiary)
            )
            binding.tvBalanceState.text = getString(R.string.value_none)
        } else {
            // ⭐⭐ 与弹窗**同一套判据**：电压类优先用「实测值 vs 0xA0 读到的阈值」，
            // 不直接采信硬件告警位（它带迟滞/延时，会在未超阈值时报警）。
            // 阈值未读到时才退回告警位。
            val effective = data.effectiveAlarms()
            if (effective.isEmpty()) {
                binding.tvAlarmTags.setText(R.string.battery_state_normal)
                binding.tvAlarmTags.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.state_connected)
                )
            } else {
                binding.tvAlarmTags.text = getString(
                    R.string.battery_state_abnormal,
                    effective.joinToString("  ") { it.label }
                )
                binding.tvAlarmTags.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.abnormal)
                )
            }
            // 均衡：读 bArr[118..121] 逐串位，显示正在均衡的具体串号
            binding.tvBalanceState.text = if (data.balanceOn) {
                getString(
                    R.string.balance_on_cells,
                    data.balancingCells.joinToString(",")
                )
            } else {
                getString(R.string.balance_off)
            }
        }
        // 循环次数同理：没数据时不要显示 0（会误导为"从没循环过"）
        binding.tvCycleCount.text = if (data.hasDeviceData) {
            getString(R.string.cycle_count_label, data.cycleCount)
        } else {
            getString(R.string.cycle_count_unknown)
        }
    }

    companion object {
        /** 单体压差告警阈值 (mV) */
        const val CELL_DIFF_THRESHOLD_MV = 80f

        /** 权限申请记录，用于区分首次申请与永久拒绝 */
        private const val KEY_PERM_ASKED = "perm_asked"

        /** 默认过滤关键词：只显示设备名含 YY 的设备 */
        private const val DEFAULT_KEYWORD = "YY"

        /**
         * 时钟刷新间隔 1 秒。
         *
         * 取 1 秒而非对齐整分钟（`delay` 到下一个整分）：整点跳变会让用户
         * 以为界面卡住，逐秒刷新则始终有细微变化、看起来"活着"。
         */
        private const val CLOCK_INTERVAL_MS = 1_000L
    }
}
package io.github.supit.rideassist

import android.app.Application
import io.github.supit.rideassist.core.ble.BleManager
import io.github.supit.rideassist.core.ble.BleScanner
import io.github.supit.rideassist.data.prefs.AppPrefs
import io.github.supit.rideassist.data.repo.MonitorRepository
import io.github.supit.rideassist.core.gate.MoveGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 应用入口。手写依赖容器（不引入 Hilt，minSdk 21 场景下更轻）。
 *
 * 依赖方向：UI → Repository → BleManager，UI 层不直接持有蓝牙 API。
 */
class YybmsApp : Application() {

    /** 应用级协程作用域，SupervisorJob 保证单个任务异常不拖垮全局 */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    val prefs: AppPrefs by lazy { AppPrefs(this) }

    /**
     * ⚠️ 声明顺序有依赖：`bleScanner` 必须在 `bleManager` **之前**，
     * 因为 BleManager 要注入它做「扫描/连接互斥」——
     * BLE 扫描占用射频，会让 GATT 连接必然失败（实测 status=8 超时）。
     */
    val bleScanner: BleScanner by lazy { BleScanner(this) }

    val bleManager: BleManager by lazy { BleManager(this, appScope, bleScanner) }

    val monitorRepository: MonitorRepository by lazy {
        MonitorRepository(bleManager, appScope)
    }

    /**
     * ⭐⭐⭐ 会话记录器 —— **应用级单例**（原为 Activity 作用域 ViewModel）。
     *
     * ## 为什么升到 Application 作用域
     * 1. **诊断面板跨 Activity**：它是独立 Activity，若还用 `activityViewModels`
     *    就会拿到**另一份**实例，看到的累加器全是最新的 0 —— 诊断价值归零。
     * 2. **诊断面板在设置页里**：本来就不该依赖 MainActivity 的生命周期。
     *
     * 语义仍然等价于「本次会话」：退出 APP 进程即重置，
     * 且跨进程恢复由 [SessionSpeedTracker.restoreSession] 的落盘承担。
     */
    val sessionTracker: io.github.supit.rideassist.ui.realtime.SessionSpeedTracker by lazy {
        io.github.supit.rideassist.ui.realtime.SessionSpeedTracker()
    }

    /**
     * ⭐ 胎压监测器 —— 应用级单例（与 sessionTracker 同一作用域思路）。
     * 占空比扫描（扫4s/停8s），与 BMS 的 GATT 连接零冲突（传感器纯广播）。
     */
    val tpmsMonitor: io.github.supit.rideassist.core.ble.tpms.TpmsMonitor by lazy {
        io.github.supit.rideassist.core.ble.tpms.TpmsMonitor(this, appScope, prefs)
    }

    /** ⭐ 本应用是否处于前台（悬浮窗显隐跟随用，ActivityLifecycleCallbacks 计数） */
    var isSelfForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        YybmsAppHolder.register(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                started++
                isSelfForeground = started > 0
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                started--
                if (started <= 0) isSelfForeground = false
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })

        // ⭐⭐⭐ **会话峰值 / 记录状态机的应用级喂数点**。
        //
        // 旧实现只在 RealtimeFragment 的 repeatOnLifecycle(STARTED) 里逐帧比对：
        // 一旦切到导航软件，Activity onStop → Fragment 生命周期 STOPPED →
        // 采集器被取消，peakRecords 整段冻结。于是悬浮窗的「Max压差」
        // （以及 Max电流 / Max功率）看着永远不更新 —— 用户反馈的根因。
        // sessionTracker 本来就是 Application 级单例，喂数也必须在同一层级。
        //
        // ⚠️ 只搬「状态机 + 三项 BMS 峰值」；曲线采样与骑行积分仍留在实时页
        //    （它们依赖 GPS 显示的局部口径，搬走会改变统计语义）。
        appScope.launch {
            monitorRepository.realtime.collect { d ->
                val tracker = sessionTracker
                // ⭐⭐ **电流证据的唯一喂数点**，必须放在 hasDeviceData 判断之前：
                //    断连帧不刷新也不清零，交给宽限期自然过期 → 速度锁 0，
                //    正是用户要求的「电流一直是 0，速度保持为 0」。
                tracker.noteCurrentEvidence(abs(d.totalCurrent), d.hasDeviceData)
                if (!d.hasDeviceData) return@collect   // 断连帧不采样（沿用旧口径）
                // 判据只有 MoveGate 一处，速度证据取 tracker 里的最新 GPS 值
                val verdict = MoveGate.evaluate(d, tracker.liveSpeedKmh)
                tracker.updateRecordingState(verdict.moving, true)
                if (!tracker.isRecording) return@collect
                val T = io.github.supit.rideassist.ui.realtime.SessionSpeedTracker
                tracker.updatePeak(T.CARD_POWER, d.power, d)
                tracker.updatePeak(T.CARD_CURRENT, abs(d.totalCurrent), d)
                tracker.updatePeak(T.CARD_DIFF, d.cellDiff, d)

                // ⭐⭐ **速度卡峰值快照也必须在这一层比对**（用户要求：悬浮/退后台期间
                //    APP 本体也要持续记录极速与最大压差，回来能查到）。
                // 以前它留在 RealtimeFragment 的 STARTED 作用域里，切到导航后
                // Fragment STOPPED → 「最大速度 XX @ 时间」记录卡整段冻结。
                // 速度证据取 tracker.liveSpeedKmh：APP 前台由实时页写、退后台由
                // 悬浮前台服务写，两处都是 SpeedDisplay 同一口径。
                // ⚠️ 只对「有速度信号」的帧比对；取整数与页面水印/曲线口径一致。
                tracker.liveSpeedKmh?.let { v ->
                    tracker.updatePeak(T.CARD_SPEED, v.toInt().toFloat(), d)
                }
            }
        }
    }
}
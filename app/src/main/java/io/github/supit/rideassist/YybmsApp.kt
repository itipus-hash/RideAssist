package io.github.supit.rideassist

import android.app.Application
import io.github.supit.rideassist.core.ble.BleManager
import io.github.supit.rideassist.core.ble.BleScanner
import io.github.supit.rideassist.data.prefs.AppPrefs
import io.github.supit.rideassist.data.repo.MonitorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    }
}
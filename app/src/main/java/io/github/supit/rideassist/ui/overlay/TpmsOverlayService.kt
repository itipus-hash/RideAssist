package io.github.supit.rideassist.ui.overlay

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.supit.rideassist.R
import io.github.supit.rideassist.YybmsApp
import io.github.supit.rideassist.core.ble.tpms.TpmsMonitor
import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ⭐ 悬浮信息框 —— 叠在任何界面之上，按「项目注册表 + 用户排序」渲染各项目行。
 *
 * ## 交互
 * - **拖动** → 移动位置，松手记忆（左上角基准坐标）
 * - **单击**（<500ms 未拖动）→ 返回本应用；**按住 ≥500ms 松开** → 关闭
 *
 * ## 行显隐（用户要求）
 * - **每个信息单独一行**，开关打开的项目恒定占一行；
 *   没数据也**不隐藏**（写「标签  --」），行数不跳动
 * - 「空格占位」项目 = 一条纯空白行
 * - 所有项目开关都关 → 整框隐藏
 */
class TpmsOverlayService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 每个项目一行，key → TextView（创建一次，排序时重排） */
    private val rowViews = mutableMapOf<String, TextView>()

    /** 空态占位：开关开着但所有行都无有效数据时显示 */
    private var tvWaiting: TextView? = null

    /** ⭐ 临时隐藏（其他界面长按悬浮窗）：服务保留，回本 APP 自动恢复显示 */
    private var tempHidden = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 最近一次数据缓存（两条数据流各一份，任一更新都全量重渲） */
    private var latestTpms: TpmsMonitor.UiState? = null
    private var latestRealtime: BmsRealtimeData? = null

    /** 字号档位 → sp（小/中/大；19:47 用户反馈各再大一点：18/22/26 → 22/26/30） */
    private val sizeSp = intArrayOf(22, 26, 30)

    /**
     * 是否夜间主题（用户要求按**实际时间**：18:00~06:00 夜间白字，
     * 其余白天黑字——系统日夜模式与真实时间可能不一致）
     */
    private val themeIsNight: Boolean
        get() {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return h >= 18 || h < 6
        }

    /** 主题文字颜色：夜间白 / 白天黑（低电量/越界的红由渲染单独覆盖） */
    private val themeTextColor: Int
        get() = if (themeIsNight) Color.WHITE else Color.BLACK

    /** 主题背景色（叠加 prefs 背景透明度） */
    private fun bgColor(): Int {
        val a = ((application as? YybmsApp)?.prefs?.overlayBgAlpha ?: 70).coerceIn(0, 100) * 255 / 100
        return if (themeIsNight) Color.argb(a, 0, 0, 0) else Color.argb(a, 255, 255, 255)
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 滑条实时调背景透明度（服务运行时非空） */
        @Volatile
        var bgAlphaListener: ((Int) -> Unit)? = null

        /** 设置页拖动排序后通知悬浮窗重建行（服务运行时非空） */
        @Volatile
        var orderListener: (() -> Unit)? = null

        /** ⭐ 空白占位项目 key（渲染时跳过数据填充） */
        const val KEY_SPACE = "space"

        /** ⭐ 本 APP 切到导航软件后的预期状态（此期间长按悬浮窗不隐藏） */
        @Volatile
        var expectNav: Boolean = false

    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * ⭐ 前台服务保活：切到导航后本 APP 退后台，系统（华为省电）会冻结/杀掉
     * 普通后台服务 → 悬浮窗消失、临时隐藏状态丢失。挂一个静默通知
     * （IMPORTANCE_NONE 不响不动不显示横幅）常驻，服务就不再被杀。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "overlay_keepalive"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (nm?.getNotificationChannel(channelId) == null) {
                nm?.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "悬浮信息",
                        android.app.NotificationManager.IMPORTANCE_NONE
                    )
                )
            }
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("骑行助手")
            .setContentText("悬浮信息运行中")
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(1001, notification)
        return START_STICKY
    }

    /** 返回本应用主界面（悬浮框保留） */
    private fun goApp() {
        expectNav = false   // ⭐ 回本 APP → 清「导航中」标志
        val i = Intent(this, io.github.supit.rideassist.ui.main.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(i)
    }

    /** 本应用是否处于前台（决定单击悬浮窗的双态行为） */
    private fun isAppForeground(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.runningAppProcesses?.any {
            it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                it.processName == packageName
        } ?: false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // ⭐ 一次性回归默认成对顺序（拆分胎压/新增实时项后旧顺序失配）
        val app = application as YybmsApp
        if (app.prefs.overlayOrder.isNotEmpty() && !app.prefs.pairResetDone) {
            app.prefs.overlayOrder = ""
            app.prefs.pairResetDone = true
        }
        val prefs = app.prefs
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 14, 28, 14)
            background = GradientDrawable().apply {
                cornerRadius = 28f
                // 背景颜色跟随日夜模式，不透明度可调（prefs.overlayBgAlpha，%）
                setColor(bgColor())
            }
        }
        // 每个项目一行（创建一次，排序时重排）
        OverlayItems.ALL.forEach { def ->
            rowViews[def.key] = TextView(this).apply {
                if (def.key == KEY_SPACE) text = " "
                textSize = 15f
                setTextColor(themeTextColor)
            }
        }
        // 空态占位行（放在最后）
        tvWaiting = TextView(this).apply {
            text = "等待数据…"
            textSize = 13f
            setTextColor(if (themeIsNight) 0x99FFFFFF.toInt() else 0x99000000.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(tvWaiting)
        rootView = root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        val density = resources.displayMetrics.density
        if (prefs.overlayX >= 0 && prefs.overlayY >= 0) {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = prefs.overlayX
            params.y = prefs.overlayY
        } else {
            // 默认右上：先按 END 放置，布局完成后一次性换算为 START 基准
            // （运行期 gravity 恒为 START，触摸时不再有任何换算——
            //   旧版在 ACTION_DOWN 里换算，v.width 不可靠导致点击后飞到屏幕边缘）
            params.gravity = Gravity.TOP or Gravity.END
            params.x = (12 * density).toInt()
            params.y = (56 * density).toInt()
            root.post {
                val p = layoutParams ?: return@post
                p.gravity = Gravity.TOP or Gravity.START
                p.x = resources.displayMetrics.widthPixels - root.width - p.x
                windowManager?.updateViewLayout(root, p)
            }
        }
        layoutParams = params
        wm.addView(root, params)
        android.util.Log.i("OverlayDiag",
            "addView done shown=${OverlayItems.ordered(prefs).filter { it.getShow(prefs) }.map { it.key }} " +
                "vis=${root.visibility} anySwitchOn=${prefs.overlayShowTpmsFront || prefs.overlayShowTpmsRear || prefs.overlayShowBattery || prefs.overlayShowVolts} " +
                "alpha=${prefs.overlayBgAlpha} tempHidden=$tempHidden")

        attachGestures(root, params)
        applyOrderAndVisibility()

        // 滑条拖动时实时更新背景
        bgAlphaListener = { a ->
            (root.background as? GradientDrawable)?.setColor(bgColor())
        }
        // 设置页排序/开关变化时实时重建
        orderListener = { applyOrderAndVisibility() }
        // ⭐ 每分钟检查主题（跨 18:00/06:00 时段自动换色，服务长驻也能跟随）
        val tick = object : Runnable {
            override fun run() {
                applyTheme()
                // ⭐ 回本 APP 前台 → 取消临时隐藏（用户要求：切回再出现）
                if (tempHidden && (application as YybmsApp).isSelfForeground) {
                    tempHidden = false
                    updateRootVisibility()
                }
                handler.postDelayed(this, 5000L)
            }
        }
        handler.post(tick)


        scope.launch {
            app.tpmsMonitor.state.collect {
                latestTpms = it
                renderAll()
            }
        }
        scope.launch {
            app.monitorRepository.realtime.collect {
                latestRealtime = it
                renderAll()
            }
        }
        // ⭐⭐ 会话峰值一刷新就重渲：以前只靠上面两条数据流，峰值更新与
        //    悬浮渲染的先后顺序不保证，Max压差/Max电流等会慢一帧甚至冻结。
        scope.launch {
            app.sessionTracker.peakRevisionFlow.collect { renderAll() }
        }
    }

    /**
     * 布局重建（开关/排序/字号变化时）：
     * ⭐ **每个项目独占一行**（用户要求），行宽撑满、文字左对齐。
     * 数据无效也保持这一行（写「标签  --」占位），行数恒定不跳动。
     */
    private fun applyOrderAndVisibility() {
        val root = rootView ?: return
        val prefs = (application as YybmsApp).prefs
        root.removeAllViews()
        val shown = OverlayItems.ordered(prefs).filter { it.getShow(prefs) }

        shown.forEach { def ->
            val tv = rowViews[def.key] ?: return@forEach
            (tv.parent as? ViewGroup)?.removeView(tv)
            // ⭐ WRAP_CONTENT：悬浮框宽度随内容走，绝不意外撑满整屏
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tv.textSize = sizeSp[def.getSize(prefs).coerceIn(0, 2)].toFloat()
            tv.visibility = View.VISIBLE
            if (def.key == KEY_SPACE) {
                // 空白占位：不含任何文字/单位；字号档位复用为留白高度
                tv.text = " "
                tv.textSize = sizeSp[def.getSize(prefs).coerceIn(0, 2)] * 0.6f
                tv.setTextColor(themeTextColor)
            }
            root.addView(tv)
        }

        // 空态占位行（放最后，现已恒隐：行都常驻，不再需要「等待数据…」）
        tvWaiting?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.visibility = View.GONE
            root.addView(w)
        }
        updateRootVisibility()
        renderAll()
    }

    /**
     * 整框显隐 + 空态占位：
     * - 所有项目开关都关 → 整框隐藏
     * - 有开关开着但行全部无有效数据 → 显示「等待数据…」
     */
    /** ⭐ 悬浮窗显示名（Def.displayLabelRes，与设置页开关名分离） */
    private fun displayLabel(key: String): String =
        OverlayItems.ALL.find { it.key == key }?.let { getString(it.displayLabelRes) } ?: key

    /** 当前前台包名（getRunningTasks 在部分 ROM 可用；拿不到返回 null） */
    private fun runningTaskPkg(): String? = runCatching {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
    }.getOrNull()

    /**
     * 整框显隐 + 空态占位 + 前台跟随（用户要求）：
     * - 所有项目开关都关 → 整框隐藏
     * - 有开关开着但行全部无有效数据 → 显示「等待数据…」
     * - 前台不是 导航软件/本应用 → 整框隐藏（切回再出现）
     */
    private fun updateRootVisibility() {
        val root = rootView ?: return
        val prefs = (application as YybmsApp).prefs
        // ⭐ 直接用注册表算：旧版手抄 12 个开关，每加一个项目都要记得补，
        //    漏一个就会出现「开关开了但整框不显示」。
        val anySwitchOn = OverlayItems.ordered(prefs).any { it.getShow(prefs) }
        if (tempHidden) {
            root.visibility = View.INVISIBLE
            tvWaiting?.visibility = View.GONE
            return
        }
        root.visibility = if (anySwitchOn) View.VISIBLE else View.INVISIBLE
        // 行全部常驻（无数据也写 --），不再需要「等待数据…」占位
        tvWaiting?.visibility = View.GONE
    }

    // ==================== 渲染 ====================

    private fun renderAll() {
        // ⭐ 空白占位行恒为一条空白，任何数据渲染都不碰它
        rowViews[KEY_SPACE]?.let { tv ->
            tv.text = " "
            tv.visibility = View.VISIBLE
        }
        renderTpmsFront(latestTpms)
        renderTpmsRear(latestTpms)
        renderSoc(latestRealtime)
        renderRealtime(latestRealtime)
        updateRootVisibility()
    }

    /**
     * ⭐ **行恒显**：有值写「标签  值」，无值写「标签  --」，
     * 绝不 GONE。以前无数据/为 0 就隐藏，悬浮框高度忽大忽小、
     * 开着的项目还会整行消失（用户要求「不要隐藏」）。
     */
    private fun showRow(tv: TextView, label: String, value: String?, alarm: Boolean = false) {
        tv.text = if (value == null) "$label  --" else "$label  $value"
        tv.setTextColor(
            if (value != null && alarm) getColor(R.color.abnormal) else themeTextColor
        )
        tv.visibility = View.VISIBLE
    }

    /** 按 key 写一行（该 key 的行不在布局里时自然跳过） */
    private fun row(key: String, label: String, value: String?, alarm: Boolean = false) {
        rowViews[key]?.let { showRow(it, label, value, alarm) }
    }

    /** 胎压行：标签「前轮/后轮」，**不带 Bar 单位**，标签与数值之间留双空格 */
    private fun renderTpmsHalf(st: TpmsMonitor.UiState?, pos: Int, tv: TextView) {
        val prefs = (application as YybmsApp).prefs
        val label = getString(
            if (pos == TpmsMonitor.POS_FRONT) R.string.tpms_front else R.string.tpms_rear
        )
        val sn = if (pos == TpmsMonitor.POS_FRONT) prefs.tpmsFrontSn else prefs.tpmsRearSn
        val r = sn?.let { st?.readings?.get(it) }
        if (r == null) {
            showRow(tv, label, null)
            return
        }
        val v = String.format(java.util.Locale.US, "%.2f", r.bar)
        val alarm = r.bar < prefs.tpmsLowBar || r.bar > prefs.tpmsHighBar
        showRow(tv, label, v, alarm)
    }

    private fun renderTpmsFront(st: TpmsMonitor.UiState?) {
        val tv = rowViews["tpmsf"] ?: return
        if ((application as YybmsApp).prefs.overlayShowTpmsFront)
            renderTpmsHalf(st, TpmsMonitor.POS_FRONT, tv)
    }

    private fun renderTpmsRear(st: TpmsMonitor.UiState?) {
        val tv = rowViews["tpmsr"] ?: return
        if ((application as YybmsApp).prefs.overlayShowTpmsRear)
            renderTpmsHalf(st, TpmsMonitor.POS_REAR, tv)
    }

    /** 电量行：锂电池 SOC（不是胎压传感器电池）；<20% 标红 */
    private fun renderSoc(d: BmsRealtimeData?) {
        val prefs = (application as YybmsApp).prefs
        if (!prefs.overlayShowBattery) return
        val label = displayLabel("battery")
        if (d == null || d.totalVoltage <= 0f) {
            row("battery", label, null)
            return
        }
        val soc = d.soc.toInt()
        row("battery", label, String.format(java.util.Locale.US, "%d %%", soc), soc < 20)
    }

    /** 电压/电流/功率/实时压差/会话峰值各行（BMS 实时帧 + 会话峰值） */
    private fun renderRealtime(d: BmsRealtimeData?) {
        val app = application as YybmsApp
        val prefs = app.prefs
        val tracker = app.sessionTracker
        fun f(v: Float, pat: String) = String.format(java.util.Locale.US, pat, v)

        if (prefs.overlayShowVolts)
            row("volts", displayLabel("volts"),
                d?.totalVoltage?.takeIf { it > 0f }?.let { f(it, "%.1f V") })
        // ⭐ 实时电流 / 实时功率 = 当前帧瞬时值
        if (prefs.overlayShowAmpsLive)
            row("ampslive", displayLabel("ampslive"),
                d?.totalCurrent?.takeIf { kotlin.math.abs(it) > 0.05f }?.let { f(it, "%.1f A") })
        if (prefs.overlayShowPowerLive)
            row("powerlive", displayLabel("powerlive"),
                d?.power?.takeIf { kotlin.math.abs(it) > 0.5f }?.let { f(it, "%.0f W") })
        // ⭐ 会话峰值（由 YybmsApp 应用级采集器逐帧喂入，退后台也不停）
        if (prefs.overlayShowAmps)
            row("amps", displayLabel("amps"),
                tracker.peakRecords[io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.CARD_CURRENT]
                    ?.value?.takeIf { it > 0.05f }?.let { f(it, "%.1f A") })
        if (prefs.overlayShowPower)
            row("power", displayLabel("power"),
                tracker.peakRecords[io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.CARD_POWER]
                    ?.value?.takeIf { kotlin.math.abs(it) > 0.5f }?.let { f(it, "%.0f W") })
        if (prefs.overlayShowMaxSpeed)
            row("maxspeed", displayLabel("maxspeed"),
                tracker.maxSpeed.takeIf { it > 0 }
                    ?.let { String.format(java.util.Locale.US, "%d km/h", it) })
        if (prefs.overlayShowMosTemp)
            row("mostemp", getString(R.string.overlay_item_mos_temp),
                d?.mosTemp?.let { f(it, "%.1f ℃") })
        if (prefs.overlayShowDiffLive)
            row("difflive", displayLabel("difflive"),
                d?.cellDiff?.takeIf { it > 0f }?.let { f(it, "%.0f mV") })
        if (prefs.overlayShowDiffMax)
            row("diffmax", displayLabel("diffmax"),
                tracker.peakRecords[io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.CARD_DIFF]
                    ?.value?.takeIf { it > 0f }?.let { f(it, "%.0f mV") })
    }

    private fun tvVoltsView(): TextView? = rowViews["volts"]
    private fun tvAmpsView(): TextView? = rowViews["amps"]
    private fun tvPowerView(): TextView? = rowViews["power"]
    private fun tvMosTempView(): TextView? = rowViews["mostemp"]
    private fun tvDiffLiveView(): TextView? = rowViews["difflive"]
    private fun tvDiffMaxView(): TextView? = rowViews["diffmax"]
    private fun tvMaxSpeedView(): TextView? = rowViews["maxspeed"]
    private fun tvAmpsLiveView(): TextView? = rowViews["ampslive"]
    private fun tvPowerLiveView(): TextView? = rowViews["powerlive"]

    /**
     * 手势（自管理）：拖动 / 单击(<500ms)返回应用 / 按住 ≥500ms 关闭。
     * 拖动开始时 END 基准一次性换算成 START 基准，保存与恢复口径统一。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(view: View, params: WindowManager.LayoutParams) {
        val slopPx = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startParamX = 0
        var startParamY = 0
        var downAt = 0L
        var dragging = false

        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startParamX = params.x
                    startParamY = params.y
                    downAt = System.currentTimeMillis()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > slopPx || kotlin.math.abs(dy) > slopPx)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startParamX + dx.toInt()
                        params.y = startParamY + dy.toInt()
                        windowManager?.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val app = application as YybmsApp
                    if (dragging) {
                        // 拖动结束：记忆位置
                        app.prefs.overlayX = params.x
                        app.prefs.overlayY = params.y
                    } else if (System.currentTimeMillis() - downAt >= 500L) {
                        // ⭐ 长按：临时隐藏 —— 仅在「非本 APP 且非导航中」时生效
                        //    （用户要求：本 APP 与高德界面长按不隐藏）
                        val inApp = (application as YybmsApp).isSelfForeground
                        if (!inApp && !expectNav) {
                            tempHidden = true
                            updateRootVisibility()
                        }
                    } else {
                        // 单击：双态切换（用户要求）——
                        // 本应用在前台 → 打开导航软件；否则 → 回本应用
                        if (isAppForeground()) {
                            val pkg = (application as YybmsApp).prefs.navAppPkg
                            val navIntent = if (pkg.isNotBlank())
                                packageManager.getLaunchIntentForPackage(pkg)
                            else null
                            if (navIntent != null) {
                                navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(navIntent)
                            } else {
                                goApp()
                            }
                        } else {
                            goApp()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 应用当前主题：背景色 + 全部文字颜色（跨时段自动换色的核心） */
    private fun applyTheme() {
        val root = rootView ?: return
        (root.background as? GradientDrawable)?.setColor(bgColor())
        rowViews.values.forEach { it.setTextColor(themeTextColor) }
        tvWaiting?.setTextColor(if (themeIsNight) 0x99FFFFFF.toInt() else 0x99000000.toInt())
        renderAll()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    override fun onDestroy() {
        runCatching { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) }
        rootView?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        rootView = null
        rowViews.clear()
        tempHidden = false
        bgAlphaListener = null
        orderListener = null
        handler.removeCallbacksAndMessages(null)
        tvWaiting = null
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }
}

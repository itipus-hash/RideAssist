package com.yanyang.yybms.ui.overlay

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
import com.yanyang.yybms.R
import com.yanyang.yybms.YybmsApp
import com.yanyang.yybms.core.ble.tpms.TpmsMonitor
import com.yanyang.yybms.core.protocol.model.BmsRealtimeData
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
 * - 开关关闭 / 无数据 / 数值为 0 的行不显示
 * - 有开关开着但行全部无有效数据 → 显示「等待数据…」占位
 *   （否则启动后看不到任何反馈，像「没反应」）
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
        val i = Intent(this, com.yanyang.yybms.ui.main.MainActivity::class.java).apply {
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
    }

    /**
     * 布局重建（开关/排序/字号变化时）：
     * ⭐ 电量行独占一行（重要信息突出），**其余项目两两一行**（用户要求：
     * 从胎压开始两个项目放一行），每行两个 TextView 各 weight=1 左右分布。
     * 数据无效的 TextView 用 INVISIBLE 占位（保住同行另一项的位置），
     * 整行两项都无效时该行高度塌陷为 0。
     */
    /**
     * 布局重建（开关/排序/字号变化时）：
     * ⭐ 悬浮框每**两个项目**放一行（用户要求），行内两个 TextView 各 weight=1
     * 左右分布（中间自然隔开）。数据无效 → INVISIBLE 占位（保住同行另一项位置），
     * 整行两项都无效时行高塌陷为 0。
     */
    private fun applyOrderAndVisibility() {
        val root = rootView ?: return
        val prefs = (application as YybmsApp).prefs
        root.removeAllViews()
        val shown = OverlayItems.ordered(prefs).filter { it.getShow(prefs) }

        shown.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { def ->
                val tv = rowViews[def.key] ?: return@forEach
                (tv.parent as? ViewGroup)?.removeView(tv)
                tv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                tv.textSize = sizeSp[def.getSize(prefs).coerceIn(0, 2)].toFloat()
                tv.visibility = View.VISIBLE
                row.addView(tv)
            }
            root.addView(row)
        }

        // 空态占位行（放最后）
        tvWaiting?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
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
        val anySwitchOn = prefs.overlayShowTpms || prefs.overlayShowVolts ||
            prefs.overlayShowAmps || prefs.overlayShowPower ||
            prefs.overlayShowDiffLive || prefs.overlayShowDiffMax ||
            prefs.overlayShowBattery || prefs.overlayShowMosTemp ||
            prefs.overlayShowMaxSpeed || prefs.overlayShowTpmsFront ||
            prefs.overlayShowTpmsRear || prefs.overlayShowAmpsLive ||
            prefs.overlayShowPowerLive
        val anyRowVisible = rowViews.values.any { it.visibility == View.VISIBLE }
        // ⭐ 临时隐藏优先（其他界面长按触发，回本 APP 自动恢复）
        if (tempHidden) {
            root.visibility = View.INVISIBLE
            tvWaiting?.visibility = View.GONE
            return
        }
        root.visibility = if (anySwitchOn) View.VISIBLE else View.INVISIBLE
        tvWaiting?.visibility = if (anySwitchOn && !anyRowVisible) View.VISIBLE else View.GONE
    }

    // ==================== 渲染 ====================

    private fun renderAll() {
        renderTpmsFront(latestTpms)
        renderTpmsRear(latestTpms)
        renderSoc(latestRealtime)
        renderRealtime(latestRealtime)
        updateRootVisibility()
    }

    /** 胎压行渲染公共：pos=前/后；label 固定「前轮/后轮」，格式「前轮 1.24」 */
    private fun renderTpmsHalf(st: TpmsMonitor.UiState?, pos: Int, tv: TextView) {
        val prefs = (application as YybmsApp).prefs
        val label = getString(
            if (pos == TpmsMonitor.POS_FRONT) R.string.tpms_front else R.string.tpms_rear
        )
        val sn = if (pos == TpmsMonitor.POS_FRONT) prefs.tpmsFrontSn else prefs.tpmsRearSn
        val r = sn?.let { st?.readings?.get(it) }
        if (r == null) {
            tv.visibility = View.GONE
            return
        }
        val alarm = r.bar < prefs.tpmsLowBar || r.bar > prefs.tpmsHighBar
        val v = String.format(java.util.Locale.US, "%.2f", r.bar)
        tv.text = "$label ${v}Bar"
        tv.setTextColor(if (alarm) getColor(R.color.abnormal) else themeTextColor)
        tv.visibility = View.VISIBLE
    }

    private fun renderTpmsFront(st: TpmsMonitor.UiState?) {
        val tv = rowViews["tpmsf"] ?: return
        if ((application as YybmsApp).prefs.overlayShowTpmsFront) renderTpmsHalf(st, TpmsMonitor.POS_FRONT, tv)
    }

    private fun renderTpmsRear(st: TpmsMonitor.UiState?) {
        val tv = rowViews["tpmsr"] ?: return
        if ((application as YybmsApp).prefs.overlayShowTpmsRear) renderTpmsHalf(st, TpmsMonitor.POS_REAR, tv)
    }

    /**
     * 电量行：**锂电池 SOC**（用户澄清：不是胎压传感器电池）。
     * 数据源 BMS 实时帧 soc（%）；<20% 标红；BMS 无数据帧时整行隐藏。
     */
    private fun renderSoc(d: BmsRealtimeData?) {
        val tv = rowViews["battery"] ?: return
        val prefs = (application as YybmsApp).prefs
        if (!prefs.overlayShowBattery) return
        // BMS 没连（总压 0）时 soc 无意义，整行隐藏
        if (d == null || d.totalVoltage <= 0f) {
            tv.visibility = View.GONE
            return
        }
        val soc = d.soc.toInt()
        val low = soc < 20
        tv.text = displayLabel("battery") + " " +
            String.format(java.util.Locale.US, "%d %%", soc)
        tv.setTextColor(if (low) getColor(R.color.abnormal) else themeTextColor)
        tv.visibility = View.VISIBLE
    }

    /** 电压/电流/功率/实时压差/最大压差各行（BMS 实时帧 + 会话峰值） */
    private fun renderRealtime(d: BmsRealtimeData?) {
        tvVoltsView()?.let { t ->
            if (!(application as YybmsApp).prefs.overlayShowVolts) return@let
            val v = d?.totalVoltage ?: 0f
            if (v > 0f) {
                t.text = displayLabel("volts") + " " +
                    String.format(java.util.Locale.US, "%.1f V", v)
                t.visibility = View.VISIBLE
            } else {
                t.visibility = View.GONE
            }
        }
        // ⭐ 实时电流/实时功率 = 当前帧瞬时值（区别于会话峰值的最大电流/峰值功率）
        tvAmpsLiveView()?.let { t ->
            if (!(application as YybmsApp).prefs.overlayShowAmpsLive) return@let
            val cur = d?.totalCurrent ?: 0f
            if (kotlin.math.abs(cur) > 0.05f) {
                t.text = displayLabel("ampslive") + " " +
                    String.format(java.util.Locale.US, "%.1f A", cur)
                t.visibility = View.VISIBLE
            } else {
                t.visibility = View.GONE
            }
        }
        tvPowerLiveView()?.let { t ->
            if (!(application as YybmsApp).prefs.overlayShowPowerLive) return@let
            val p = d?.power ?: 0f
            if (kotlin.math.abs(p) > 0.5f) {
                t.text = displayLabel("powerlive") + " " +
                    String.format(java.util.Locale.US, "%.0f W", p)
                t.visibility = View.VISIBLE
            } else {
                t.visibility = View.GONE
            }
        }
        // ⭐ 最大电流/峰值功率 = 会话峰值（峰值记录同源，与实时页口径一致）
        val peakAmps = (application as YybmsApp).sessionTracker
            .peakRecords[com.yanyang.yybms.ui.realtime.SessionSpeedTracker.CARD_CURRENT]?.value ?: 0f
        tvAmpsView()?.let { t ->
            if (!(application as YybmsApp).prefs.overlayShowAmps) return@let
            if (peakAmps > 0.05f) {
                t.text = displayLabel("amps") + " " +
                    String.format(java.util.Locale.US, "%.1f A", peakAmps)
                t.visibility = View.VISIBLE
            } else {
                t.visibility = View.GONE
            }
        }
        val peakPower = (application as YybmsApp).sessionTracker
            .peakRecords[com.yanyang.yybms.ui.realtime.SessionSpeedTracker.CARD_POWER]?.value ?: 0f
        tvPowerView()?.let { t ->
            if (!(application as YybmsApp).prefs.overlayShowPower) return@let
            if (kotlin.math.abs(peakPower) > 0.5f) {
                t.text = displayLabel("power") + " " +
                    String.format(java.util.Locale.US, "%.0f W", peakPower)
                t.visibility = View.VISIBLE
            } else {
                t.visibility = View.GONE
            }
        }
        // ⭐ 最大速度 = 会话峰值（跨会话持久恢复）
        tvMaxSpeedView()?.let { t ->
            if (!(application as YybmsApp).prefs.overlayShowMaxSpeed) return@let
            val ms = (application as YybmsApp).sessionTracker.maxSpeed
            if (ms > 0) {
                t.text = displayLabel("maxspeed") + " " +
                    String.format(java.util.Locale.US, "%d km/h", ms)
                t.visibility = View.VISIBLE
            } else {
                t.visibility = View.GONE
            }
        }
        tvMosTempView()?.let { t ->
            if (!(application as YybmsApp).prefs.overlayShowMosTemp) return@let
            val mt = d?.mosTemp
            if (mt != null) {
                t.text = getString(R.string.overlay_item_mos_temp) + " " +
                    String.format(java.util.Locale.US, "%.1f ℃", mt)
                t.visibility = View.VISIBLE
            } else {
                t.visibility = View.GONE
            }
        }
        tvDiffLiveView()?.let { t ->
            if (!(application as YybmsApp).prefs.overlayShowDiffLive) return@let
            val diff = d?.cellDiff ?: 0f
            if (diff > 0f) {
                t.text = displayLabel("difflive") + " " +
                    String.format(java.util.Locale.US, "%.0f mV", diff)
                t.visibility = View.VISIBLE
            } else {
                t.visibility = View.GONE
            }
        }
        tvDiffMaxView()?.let { t ->
            if (!(application as YybmsApp).prefs.overlayShowDiffMax) return@let
            val maxDiff = (application as YybmsApp).sessionTracker
                .peakRecords[com.yanyang.yybms.ui.realtime.SessionSpeedTracker.CARD_DIFF]?.value ?: 0f
            if (maxDiff > 0f) {
                t.text = displayLabel("diffmax") + " " +
                    String.format(java.util.Locale.US, "%.0f mV", maxDiff)
                t.visibility = View.VISIBLE
            } else {
                t.visibility = View.GONE
            }
        }
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

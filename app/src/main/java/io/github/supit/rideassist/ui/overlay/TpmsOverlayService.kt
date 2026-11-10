package io.github.supit.rideassist.ui.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
 * - **每个信息单独一行**，开关打开的项目恒定占一行；没数据也**不隐藏**
 *   （保留项目名、数值留空，不再出现「--」），行数不跳动
 * - ⚠️ 唯一例外：前轮 / 后轮胎压**合并成一行**（两个开关仍独立、仍可排序）
 * - 「空格占位」项目 = 一条纯空白行
 * - 所有项目开关都关 → 整框隐藏
 *
 * ## 后台 GPS（2026-11 修复极速冻结）
 * 实时页的 GPS 订阅挂在 Fragment 生命周期上，切到导航软件后 onPause 会
 * `removeUpdates` → 会话极速从此冻结，悬浮窗「极速」永远停在切走那一刻。
 * 与「Max压差」是同一类根因（喂数点层级低于数据持有者）。
 * 前台服务在这里**独立订阅一路 GPS**，只喂 [SessionSpeedTracker.noteSpeed]，
 * 不碰 UI 曲线 / GPS 丢失推算；低通口径复用 SpeedDisplay.Filter 保证两处一致。
 */
class TpmsOverlayService : android.app.Service(), LocationListener {

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
     * ⭐ 前/后轮胎压的合并行容器。
     * 两个项目在注册表里仍是各自独立的开关与排序项，只在**渲染**时合并到这一行；
     * 谁先出现由用户排序决定，两个都关时容器不进布局树。
     */
    private var tpmsPairRow: LinearLayout? = null

    // ===== 后台 GPS：只负责维持会话极速，不做任何 UI 采样 =====

    private var overlayLocationManager: LocationManager? = null

    /** ⭐ 独立 Filter 实例：低通状态不能与实时页共享，但口径常量只有 SpeedDisplay 一处 */
    private val overlaySpeedFilter = io.github.supit.rideassist.util.SpeedDisplay.Filter()

    /**
     * 是否夜间主题（用户要求按**实际时间**：18:00~06:00 夜间白字，
     * 其余白天黑字——系统日夜模式与真实时间可能不一致）
     */
    private val themeIsNight: Boolean
        get() {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return h >= 18 || h < 6
        }

    /**
     * ⭐ 主题文字颜色：**用户选色优先，0 = 自动**。
     *
     * 自动沿用按实际时间（18:00~06:00 夜间白 / 其余黑）的旧口径；
     * 非 0 直接作为普通文字色。告警红仍由 [showRow] 单独覆盖，
     * 不能让用户选色把异常态的红色吃掉。
     */
    private val themeTextColor: Int
        get() {
            val custom = (application as? YybmsApp)?.prefs?.overlayTextColor ?: 0
            return if (custom != 0) custom else if (themeIsNight) Color.WHITE else Color.BLACK
        }

    /** 「等待数据」用主文字色的 60% 透明度；自动模式下与原来的黑白半透明完全一致 */
    private val waitingTextColor: Int
        get() = Color.argb(
            0x99, Color.red(themeTextColor), Color.green(themeTextColor), Color.blue(themeTextColor)
        )

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

        /** ⭐ 设置页选完字体颜色后通知已运行的悬浮窗立刻换色（服务运行时非空） */
        @Volatile
        var textColorListener: ((Int) -> Unit)? = null

        /**
         * ⭐ 空白占位项目 key：**从注册表派生**，不在服务里手抄一份清单。
         * 新增占位只需在 OverlayItems.ALL 里加一条 isSpace=true 的 Def。
         */
        val SPACE_KEYS: Set<String> =
            OverlayItems.ALL.filter { it.isSpace }.map { it.key }.toSet()

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
        // ⭐ 前台服务要在后台持续拿 GPS（维持会话极速），必须声明 location 类型，
        //    否则 Android 14+ 会掐掉后台定位更新。
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                startForeground(
                    1001, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            }.onFailure {
                // ⚠️ 用户没给定位权限时系统可能拒绝带 location 类型的 FGS。
                //    降级成普通前台通知：悬浮窗还能显示，绝不因权限闪退。
                runCatching { startForeground(1001, notification) }
            }
        } else {
            startForeground(1001, notification)
        }
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
        val app = application as YybmsApp
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
                if (def.isSpace) text = " "
                textSize = 15f
                setTextColor(themeTextColor)
            }
        }
        // 空态占位行（放在最后）
        tvWaiting = TextView(this).apply {
            text = "等待数据…"
            textSize = 13f
            setTextColor(waitingTextColor)
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
                // ⭐⭐ 换算结果一并持久化：否则 overlayX/Y 一直是 -1，
                //    以后任何一次服务重建都会重走「默认右上 + 按当前宽度换算」，
                //    内容宽度一变（排序 / 字号 / 开关）窗口就会漂移。
                //    写死后运行期坐标恒为 START 基准，与用户手动拖动保存的口径一致。
                prefs.overlayX = p.x
                prefs.overlayY = p.y
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
        // ⭐ 设置页选色后立刻重刷全部行文字色 + 空态色（不重启服务）
        textColorListener = { _ -> applyTheme() }
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
        // ⭐⭐ 极速同理：破纪录的瞬间必须重渲，不能等下一帧 BMS 数据。
        scope.launch {
            app.sessionTracker.speedRevisionFlow.collect { renderAll() }
        }
        // ⭐⭐ 退后台后实时页会停 GPS，这里续上一路，会话极速不再冻结。
        startOverlayGps()
    }

    /**
     * 布局重建（开关/排序/字号变化时）。
     *
     * - ⭐ **每个项目独占一行**，文字左对齐；数据无效也保留这一行
     *   （保留项目名、数值留空），行数恒定不跳动。
     * - ⭐ **唯一例外**：前 / 后轮胎压合并到同一水平行。两个开关与排序位置仍
     *   各自独立，只是渲染时落到同一个 [tpmsPairRow] 里，谁在左由排序决定。
     *   间隔用真实 margin 而不是补空格字符 —— 字体宽度不可靠（本项目踩过）。
     * - ⭐⭐ **只重建 root 的子 View，绝不碰 [layoutParams] / 服务生命周期**。
     *   设置页拖动排序会连续回调本方法；一旦顺手重启服务，就会重新
     *   `addView` 把悬浮框位置打回默认点（用户实测的「排序重置窗口位置」）。
     */
    private fun applyOrderAndVisibility() {
        val root = rootView ?: return
        val prefs = (application as YybmsApp).prefs
        root.removeAllViews()
        tpmsPairRow?.removeAllViews()
        val shown = OverlayItems.ordered(prefs).filter { it.getShow(prefs) }
        val gapPx = (14 * resources.displayMetrics.density).toInt()
        var pairAdded = false

        shown.forEach { def ->
            val tv = rowViews[def.key] ?: return@forEach
            (tv.parent as? ViewGroup)?.removeView(tv)
            tv.textSize = sizeSp[def.getSize(prefs).coerceIn(0, 2)].toFloat()
            tv.visibility = View.VISIBLE
            if (def.isSpace) {
                // 空白占位：不含任何文字/单位；字号档位复用为留白高度
                tv.text = " "
                tv.textSize = sizeSp[def.getSize(prefs).coerceIn(0, 2)] * 0.6f
                tv.setTextColor(themeTextColor)
            }

            if (def.key == "tpmsf" || def.key == "tpmsr") {
                // ⭐ 合并行：容器只在第一个胎压项出现时挂进 root 一次，
                //    第二个胎压项直接追加为同行的第二个 child。
                val row = tpmsPairRow ?: LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }.also { tpmsPairRow = it }
                if (!pairAdded) {
                    root.addView(
                        row,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    pairAdded = true
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // 只给「同一行里的第二个」加间隔；单开一个开关时行首不留白
                if (row.childCount > 0) lp.marginStart = gapPx
                row.addView(tv, lp)
            } else {
                // ⭐ WRAP_CONTENT：悬浮框宽度随内容走，绝不意外撑满整屏
                tv.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                root.addView(tv)
            }
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
        // 行全部常驻（无数据只留项目名），不再需要「等待数据…」占位
        tvWaiting?.visibility = View.GONE
    }

    // ==================== 渲染 ====================

    private fun renderAll() {
        // ⭐ 所有空白占位行恒为纯空白，任何数据渲染都不碰它们
        //    （由注册表派生，新增占位自动纳入，不用改这里）
        SPACE_KEYS.forEach { key ->
            rowViews[key]?.let { tv ->
                tv.text = " "
                tv.visibility = View.VISIBLE
            }
        }
        renderTpmsFront(latestTpms)
        renderTpmsRear(latestTpms)
        renderSoc(latestRealtime)
        renderRealtime(latestRealtime)
        updateRootVisibility()
    }

    /**
     * ⭐ **行恒显**：有值写「标签  值」，无值**只写项目名、数值留空**。
     *
     * ⚠️ 用户口径（09:30 纠正）：「没数据直接空白」指的是**数值**留白，
     * 不是整行抹掉 —— 项目名必须一直在，否则悬浮框里几行空白根本看不出
     * 开着的是哪几项。当然也不能再出现「--」。
     * 绝不 GONE —— 保留行高与行序，悬浮框高度不跳动。
     * ⚠️ 特例：「空格占位」项目不走这里，渲染层恒为纯空白行。
     */
    private fun showRow(tv: TextView, label: String, value: String?, alarm: Boolean = false) {
        tv.text = if (value == null) label else "$label  $value"
        tv.setTextColor(
            if (value != null && alarm) getColor(R.color.abnormal) else themeTextColor
        )
        tv.visibility = View.VISIBLE
    }

    /** 按 key 写一行（该 key 的行不在布局里时自然跳过） */
    private fun row(key: String, label: String, value: String?, alarm: Boolean = false) {
        rowViews[key]?.let { showRow(it, label, value, alarm) }
    }

    /**
     * 胎压片段：标签「前轮/后轮」，**不带 Bar 单位**，标签与数值之间留双空格。
     * ⭐ 前 / 后轮两个片段在布局里被放进同一个水平行（见 [applyOrderAndVisibility]），
     *    所以这里只负责各自的文本，间隔由容器的 margin 承担。
     */
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
        tvWaiting?.setTextColor(waitingTextColor)
        renderAll()
    }

    // ==================== 后台 GPS：只维持会话极速 ====================

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * ⭐⭐ 服务自带的 GPS 订阅，**唯一职责**是把显示速度喂给会话极速。
     *
     * ⚠️ **不做**曲线采样 / GPS 丢失推算 / 水印渲染 —— 那些仍归实时页管。
     * 两条订阅共用 [io.github.supit.rideassist.util.SpeedDisplay] 的口径常量，
     * 但各自持有独立 Filter（低通序列不能被两个回调交错打乱）。
     */
    @SuppressLint("MissingPermission")
    private fun startOverlayGps() {
        if (!hasLocationPermission()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        overlayLocationManager = lm
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            // 未授权 / 该 provider 不存在都只跳过这一路，绝不让服务起不来
            runCatching {
                lm.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper())
            }
        }
    }

    private fun stopOverlayGps() {
        runCatching { overlayLocationManager?.removeUpdates(this) }
        overlayLocationManager = null
        overlaySpeedFilter.reset()
        // ⭐ 服务停了且 APP 不在前台 → 清掉速度证据，
        //    否则应用级采集器会拿一个陈旧速度继续判「在动」。
        val app = application as? YybmsApp
        if (app != null && !app.isSelfForeground) app.sessionTracker.liveSpeedKmh = null
    }

    override fun onLocationChanged(location: Location) {
        val app = application as? YybmsApp ?: return
        val tracker = app.sessionTracker
        // ⚠️ 无速度解算（隧道 / 树荫）不能当 0 喂进去，会污染低通序列。
        //    但仍要用上一帧有效速度喂运动锁存，否则退后台进隧道时
        //    15 秒没有速度帧就会误判「停稳」、把速度锁成 0。
        if (!location.hasSpeed()) {
            tracker.noteGpsSpeed(overlaySpeedFilter.realSpeed ?: 0f)
            return
        }
        // ⭐⭐ 与实时页同一运动锁存（Application 级单例，一份状态）：
        //    起步靠电流/持续高速 GPS 上锁，锁存后滑行电流为 0 仍照常记速度，
        //    真正停稳才解锁并把静止 GPS 漂移压成 0。
        val rawKmh = location.speed * 3.6f
        val shown = overlaySpeedFilter.push(rawKmh, tracker.noteGpsSpeed(rawKmh))
        // 退后台后实时页已停 GPS，MoveGate 的第三路证据由这里续上；
        // APP 在前台时让实时页独占写入，避免两路互相覆盖。
        if (!app.isSelfForeground) tracker.liveSpeedKmh = overlaySpeedFilter.realSpeed
        // ⭐ 会话极速唯一写入口；破纪录时 emit speedRevisionFlow → 悬浮窗立刻重渲
        tracker.noteSpeed(shown)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) {
        overlaySpeedFilter.reset()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    override fun onDestroy() {
        stopOverlayGps()
        runCatching { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) }
        rootView?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        rootView = null
        rowViews.clear()
        tempHidden = false
        bgAlphaListener = null
        orderListener = null
        textColorListener = null
        handler.removeCallbacksAndMessages(null)
        tvWaiting = null
        tpmsPairRow = null
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }
}

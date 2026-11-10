package io.github.supit.rideassist.ui.realtime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.supit.rideassist.util.RecordThreshold
import io.github.supit.rideassist.util.SpeedDisplay
import android.os.SystemClock
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.supit.rideassist.R
import io.github.supit.rideassist.YybmsAppHolder
import io.github.supit.rideassist.core.gate.MoveGate
import io.github.supit.rideassist.databinding.FragmentRealtimeBinding
import io.github.supit.rideassist.ui.monitor.MonitorViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 实时页：GPS 速度曲线 + 功率/电流/压差曲线（四条曲线同构）。
 *
 * - **GPS 速度**：用 [LocationManager.requestLocationUpdates] 持续订阅定位更新，
 *   取 `Location.speed`（m/s）× 3.6 转 km/h。
 *   这是系统当前能给出的最可靠速度值（不需要自己按位移算）。
 * - **速度卡片结构**：左上角「速度」标题 + 右上角本次会话最高速度 + 曲线。
 *   曲线上**居中悬浮水印**显示当前速度（用户要求）：
 *   曾同时存在 96sp 大数字与水印，两者重复且互相挤占空间，已去掉大数字，
 *   水印是速度卡片里唯一的速度显示。
 * - **采样口径**：四条曲线统一存**显示值**（速度已做 5km/h 阈值归零 + 取整），
 *   不是原始值——否则曲线会在 0~4km/h 出现锯齿而水印恒显 0，两者对不上。
 * - **会话级保留**：采样队列与最高速度都在 Activity 作用域的 [SessionSpeedTracker]，
 *   切页 / Fragment 重建 / 断连都不清空（除非退出 APP）。
 */
class RealtimeFragment : Fragment(), LocationListener {

    private var _binding: FragmentRealtimeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MonitorViewModel by viewModels {
        MonitorViewModel.factory(requireContext())
    }

    private var locationManager: LocationManager? = null

    /** GPS 速度低通滤波后的值（km/h） */
    /** ⭐ 速度口径统一走 SpeedDisplay.Filter（与悬浮服务同源） */
    private val speedFilter = io.github.supit.rideassist.util.SpeedDisplay.Filter()

    /**
     * 「位置到底动没动」的粗判据 —— 用来否决静止时的 GPS 速度噪声。
     * 见 [io.github.supit.rideassist.util.GpsMotionProbe]。
     */
    private val motionProbe = io.github.supit.rideassist.util.GpsMotionProbe()

    /** 本次 APP 会话的最高速度（km/h，整数显示）。
     *  存在 Activity 上，切页/重建/断连都不丢。 */
    /**
     * ⭐⭐⭐ 会话记录器 —— **Application 级单例**。
     *
     * 原来是 `by activityViewModels()`，但诊断面板（[io.github.supit.rideassist.ui.diag.DiagActivity]）
     * 是独立 Activity，会拿到另一份实例、看到全 0 的累加器，诊断价值归零。
     * 改为单例后实时页 / 骑行页 / 诊断页读写的**永远是同一份数据**。
     */
    private val tracker: SessionSpeedTracker
        get() = YybmsAppHolder.from(requireContext()).sessionTracker

    /**
     * 判定为「滑动」的最小水平位移（像素）。
     *
     * ⚠️ 取 **48dp** 而非 `ViewConfiguration.scaledTouchSlop`（约 8dp）：
     * 8dp 阈值太灵敏，页面滚动时的手抖会被误判成切卡。
     * 48dp 是「明确是有意滑动」的量级，误触发概率低。
     *
     * ⚠️ 用实例字段而非 companion 里的 `val`：需要 `resources.displayMetrics`，
     * 而 companion object 初始化时拿不到 Context。
     */
    private val swipeThresholdPx: Float by lazy {
        48f * resources.displayMetrics.density
    }

    // ==================== GPS 丢失推算（需求 4） ====================

    /**
     * 惯性推算器（加速度计 + 车头方位角）。
     *
     * ⚠️ 首次进入聚焦速度卡时才创建 —— 传感器注册有开销，
     * 不需要时不注册。离开时 [releaseLossEstimator] 释放。
     */
    private var lossEstimator: GpsLossEstimator? = null

    /**
     * GPS 丢失的起始时刻（[SystemClock.elapsedRealtime]），0 = 当前正常。
     *
     * 用于计算「已丢失 XX」，>60 秒后不再更新（推算无意义）。
     */
    private var gpsLostSinceMs = 0L

    /**
     * GPS 恢复的**连续成功计数**（达到 [GPS_RECOVER_CONFIRM_COUNT] 才退出丢失态）。
     *
     * ⭐⭐ 用户需求："连续 3 次刷新，都获取到 GPS 速度，才退出 GPS 丢失状态，
     * 不然会错误退出。"
     *
     * ⚠️ 为什么必须数「**连续有速度**」的次数，而不能只看「有没有定位帧」：
     * 隧道/树荫下系统会持续推送 `hasSpeed()==false` 的定位帧。
     * 若把它们也算作"恢复"，就会不断重置/推进确认，导致状态反复横跳。
     * 所以：**只有拿到速度解算才算一次有效恢复**，
     * 收到「有定位但无速度」的帧直接把计数清零。
     */
    private var gpsRecoverSpeedCount = 0

    /** GPS 丢失期间的刷新句柄（每秒推进一次推算 + 刷新时长文字） */
    private val gpsLostHandler = Handler(Looper.getMainLooper())
    private val gpsLostTick = object : Runnable {
        override fun run() {
            val est = lossEstimator
            if (gpsLostSinceMs == 0L) return
            // ⭐⭐ 与 renderSpeed 同一门控：运动锁存解除（车真停稳）时，
            //    推算/保持速度一律归零并结束丢失态，不许继续往曲线里写旧速度。
            //    ⚠️ 不能用「电流证据」——滑行时电流为 0 但车还在动。
            if (!tracker.speedAllowed) {
                gpsLostHandler.removeCallbacks(this)
                clearGpsLostState()
                speedFilter.reset()
                tracker.liveSpeedKmh = null
                hasSpeedSignal = false
                pushSpeedSample(0)
                val b0 = _binding ?: return
                b0.tvSpeedNow.text = "0"
                renderSpeedColors(0)
                applySpeedMax()
                return
            }
            // 推进推算（超过 60 秒后内部会自动改为「保持」）
            est?.update(SystemClock.elapsedRealtime())
            val shown = if (est != null && est.isEstimating) est.estimatedSpeed.toInt()
            else lastValidSpeed()
            // ⭐ GPS 丢失期间没有真实速度帧，用推算/保持速度继续喂运动锁存：
            //    只要推算速度仍 ≥5km/h，滑行中的锁存就不会因「没有 GPS 帧」而过期。
            tracker.noteGpsSpeed(shown.toFloat())
            val b = _binding ?: return
            hasSpeedSignal = true
            renderSpeedColors(shown)
            b.tvSpeedNow.text = String.format("%.0f", shown.toFloat())
            applySpeedMax()
            // ⭐ 推算值照常写回曲线（用户要求），曲线在丢失期间继续延伸
            pushSpeedSample(shown)
            renderGpsLostCard()
            // 60 秒后停止刷新（推算已无意义，保持显示即可）
            if (SystemClock.elapsedRealtime() - gpsLostSinceMs < GpsLossEstimator.MAX_ESTIMATE_MS) {
                gpsLostHandler.postDelayed(this, 1000L)
            }
        }
    }

    /** 取用/创建推算器 */
    private fun ensureLossEstimator(): GpsLossEstimator {
        lossEstimator?.let { return it }
        val ctx = _binding?.root?.context ?: requireContext()
        return GpsLossEstimator(ctx).also { lossEstimator = it }
    }

    /** 释放传感器（离开聚焦速度卡 / 页面销毁时） */
    private fun releaseLossEstimator() {
        gpsLostHandler.removeCallbacks(gpsLostTick)
        lossEstimator?.stop()
        lossEstimator = null
        gpsLostSinceMs = 0L
        _binding?.cardGpsLost?.visibility = View.GONE
    }

    // ==================== 卡片交互（单击聚焦 / 双击切曲线） ====================

    /** 曲线显示模式（用户 2026-11-08 定案） */
    private enum class CurveMode {
        /** 不显示曲线（只留数值大字） */
        OFF,

        /** 实时曲线：固定步长滚动，最新点贴右边缘 */
        LIVE,

        /** 历史曲线：进入时的全量快照铺满一屏，彩色渐变，不随新数据变化 */
        HISTORY,
    }

    /**
     * 曲线显示模式。
     *
     * ⭐ **全局单一状态**，不是每卡一份。
     * 之前用 `BooleanArray(CARD_COUNT)`，聚焦态切卡时新旧卡片各自记一份状态，
     * 会出现「上一张显示曲线、切过去的那张不显示」的视觉撕裂。
     * 曲线本来就是「双击任意处全局切换」的操作，语义上就该是全局的。
     *
     * ⭐ **默认为 [CurveMode.OFF]**（用户要求"打开 APP 的时候，默认不显示曲线"）：
     * 四条曲线叠在一起很乱，而且刚打开时队列里还没数据。
     * 双击循环顺序：不显示 → 实时 → 历史 → 不显示。
     */
    private var curveMode: CurveMode = CurveMode.OFF

    /** ⭐ Application 级偏好（连接设置页里的「实时曲线默认」等） */
    private val appPrefs get() = YybmsAppHolder.from(requireContext()).prefs

    /**
     * 上次按「实时曲线默认」复位时用过的偏好值；`null` = 本 Fragment 尚未复位过。
     *
     * 用来区分「用户刚改了设置」与「用户只是切出去又回来」：偏好没变时，
     * 不打断用户在本页双击选中的曲线模式；只有真正**切入实时页**
     * （onViewCreated / onHiddenChanged(false)）才强制按偏好复位。
     */
    private var lastCurvePrefApplied: Boolean? = null

    /**
     * 历史模式的数据快照（索引与 [cardViews] 对齐）。
     *
     * ⭐ 用户选的是「**进入时快照**」：切到历史那一刻把四条队列各拷一份、
     * 压缩一次，之后无论实时数据怎么长，画面都不再变化。
     * 这样既不会每帧重算压缩（零抖动），也不会出现「看着看着 X 轴被压扁」。
     * 退出历史模式后释放，切回来重新拍。
     */
    private val historySnapshots = arrayOfNulls<FloatArray>(CARD_COUNT)

    /**
     * 聚焦模式：只显示某一张卡（其余隐藏）。
     * `null` = 未聚焦，四张卡全显示。
     */
    private var focusedCardIndex: Int? = null

    /**
     * 实时页时钟句柄。
     *
     * ⚠️ 用 [Handler] 而非 `Timer`：`Timer` 会创建独立线程持有 Runnable，
     * 而 Runnable 捕获了 Fragment → 视图销毁后仍在跑，直接泄漏。
     * 与首页 MonitorFragment 里的时钟是**两套独立实现**（各自页面生命周期独立）。
     */
    private val realtimeClockHandler = Handler(Looper.getMainLooper())

    /** 24 小时制 `HH:mm`（⚠️ 小写 mm 是分钟，MM 是月份） */
    private val REALTIME_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * 双击检测。
     *
     * ⭐ 必须用时间间隔判定，不能用 `GestureDetector` 的 `onDoubleTap`——
     * 后者在 ScrollView 里会与滚动冲突导致误判。系统参数
     * `ViewConfiguration.getDoubleTapTimeout()`（默认 300ms）是官方推荐值。
     */

    private val locationPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { startGpsUpdates() }

    // ==================== 卡片交互 ====================

    /**
     * 给四张卡挂单击/双击手势。
     *
     * 用户需求（最终版）：
     * - **单击任意卡片** → **所有**卡片的曲线一起显示/隐藏
     *   （不是只切被点的那张——点功率卡时速度卡也要跟着变）
     * - **双击任意卡片** → 聚焦该卡（其余隐藏 + 顶部时间卡 + 卡片居中），
     *   默认不显示曲线；聚焦态下单击仍用于切换曲线；
     *   再次双击恢复默认布局
     */
    /**
     * 给四张卡挂手势（**语义已按用户需求重构**）。
     *
     * ## 默认布局（未聚焦）
     * - **单击**任意卡片 → 进入聚焦模式（聚焦该卡）
     * - **双击**任意卡片 → 显示/隐藏**所有**卡片的曲线
     *
     * ## 聚焦模式
     * - **单击**卡片 → 循环切换到下一张卡片
     * - **单击**卡片以外区域（顶部时间卡）→ 返回默认布局
     * - **双击**任意地方 → 显示/隐藏**当前聚焦卡**的曲线
     *
     * ## 为什么能用 GestureDetector（这次可以）
     * 之前不敢用是因为「单击和双击做同一件事」（切曲线），
     * 必须靠延迟 300ms 判定，代价是单击感觉迟钝。
     * ⭐ 现在单击与双击**语义完全不同**（单击=聚焦/切卡，双击=切曲线），
     * 可以直接交给 GestureDetector 的 `onSingleTapConfirmed` / `onDoubleTap`，
     * 两者互不干扰，**不再需要任何延迟判定**。
     */
    // ==================== ⭐ 记录卡长按清除（用户 2026-11-10） ====================

    /**
     * 四张「记录」卡长按 → 弹确认框 → 清除该卡的全部历史。
     *
     * 用户原话：「所有最大值卡片长按后提醒是否清除记录」。
     *
     * 只清**被长按的那一张**（连同它的落盘文件），不影响其它三张、
     * 也不影响本次会话峰值记录卡 —— 峰值是「本次骑行」的实时结果，
     * 记录卡才是需要手动清理的历史。
     *
     * ⚠️ 卡片必须 `clickable/focusable`（布局已设），否则长按事件收不到。
     */
    private fun setupHistoryClearGestures() {
        val b = _binding ?: return
        bindHistoryClear(b.cardDiffHistory, R.string.diff_history_title) {
            tracker.clearDiffHistory()
        }
        bindHistoryClear(b.cardTopSpeedHistory, R.string.top_speed_history_title) {
            tracker.clearTopSpeedHistory()
        }
        bindHistoryClear(b.cardCurrentHistory, R.string.current_history_title) {
            tracker.clearMetricHistory(SessionSpeedTracker.CARD_CURRENT)
        }
        bindHistoryClear(b.cardPowerHistory, R.string.power_history_title) {
            tracker.clearMetricHistory(SessionSpeedTracker.CARD_POWER)
        }
    }

    private fun bindHistoryClear(card: View, titleRes: Int, clear: () -> Unit) {
        card.setOnLongClickListener {
            val ctx = context ?: return@setOnLongClickListener false
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.history_clear_title)
                .setMessage(
                    getString(R.string.history_clear_message, getString(titleRes))
                )
                .setNegativeButton(R.string.history_clear_cancel, null)
                .setPositiveButton(R.string.history_clear_confirm) { _, _ ->
                    clear()
                    // 立刻重绘：卡片还在聚焦态，清空后应马上变成空态文案
                    renderPeakSection()
                }
                .show()
            true
        }
    }

    private fun setupCardGestures() {
        val b = _binding ?: return
        cardViews().forEachIndexed { index, card ->            val detector = GestureDetector(
                requireContext(),
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        // ⭐ 单击：默认布局→聚焦该卡；聚焦态→循环切下一张
                        if (focusedCardIndex == null) onCardFocused(index)
                        else switchFocus(1)
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        // ⭐ 双击：循环切换 不显示 → 实时 → 历史
                        cycleCurveMode()
                        return true
                    }

                    override fun onFling(
                        e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
                    ): Boolean {
                        // 聚焦态下左右滑动也能切卡（保留这个额外的便利手势）
                        if (focusedCardIndex == null) return false
                        val dx = e2.x - (e1?.x ?: e2.x)
                        val dy = e2.y - (e1?.y ?: e2.y)
                        if (kotlin.math.abs(dx) < kotlin.math.abs(dy)) return false
                        if (kotlin.math.abs(dx) < swipeThresholdPx) return false
                        switchFocus(if (dx > 0) -1 else 1)
                        return true
                    }
                }
            )
            // ⚠️⚠️ **必须显式设为 clickable**：
            // 只有 setOnClickListener 会自动置 clickable，
            // 而现在改用 GestureDetector + setOnTouchListener，
            // View 默认**不可点击** → 不派发 DOWN 事件 → onTouchListener 收不到，
            // 结果「单击没反应」（实测踩到：dumpsys 显示卡片标志 `V.E` 而非 `V.E.C`）。
            card.isClickable = true
            card.isFocusable = false
            card.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                // ⚠️ 必须返回 false，让 NestedScrollView 能正常上下滚动
                false
            }
        }

        // ⭐⭐ **内容容器也要绑手势**（用户反馈："聚焦模式点击所有卡片下面的空白区域
        //   没法返回默认布局"、"双击非卡片区域没法切曲线"）。
        //
        // 原因：聚焦布局只有「时间卡 + 一张卡 + **大片空白**」，
        // 空白区属于 ScrollView 的子容器，**没有任何手势绑定** → 点了没反应。
        //
        // 语义（聚焦态）：
        // - 单击空白 → 返回默认布局
        // - 双击空白 → 切换当前聚焦卡的曲线
        val content = b.scrollView.getChildAt(0) as? View ?: return
        // ⚠️ detector 只创建一次（若放在 onTouch 里每次触摸都 new，会拖慢并丢状态）
        val blankDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (focusedCardIndex != null) exitFocus()
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // 与卡片层同一入口：三态是全局状态，两处必须一致
                    cycleCurveMode()
                    return true
                }
            }
        )
        content.isClickable = true
        content.isFocusable = false
        content.setOnTouchListener { _, event ->
            blankDetector.onTouchEvent(event)
            // ⚠️ 仍返回 false，不影响 NestedScrollView 滚动
            false
        }
        // ⭐ 聚焦态下**单击顶部时间卡** → 返回默认布局
        //（卡片以外的地方 = 卡片；时间卡是卡片区之外唯一的可点区域）
        b.cardFocusClock.setOnClickListener { exitFocus() }
    }

    /**
     * ⭐ 供 MainActivity 调用：外部触发聚焦速度卡
     * （点导航按钮自动切实时页时聚焦，等价于单击速度卡；已聚焦则不动）
     */
    fun focusSpeedCard() {
        if (focusedCardIndex == null) onCardFocused(CARD_SPEED)
    }

    /**
     * 进入聚焦模式（默认布局下单击卡片）。
     *
     * @param index 被聚焦的卡片索引
     */
    private fun onCardFocused(index: Int) {
        focusedCardIndex = index
        // ⭐⭐ 进入聚焦时**强制隐藏四条曲线**（用户要求：
        //「而且聚焦后，也没有默认不显示曲线（需要的话自己再切换）」）。
        // 聚焦是为了把一张卡看清楚，曲线此时是干扰项；
        // 想看曲线在聚焦态下双击即可打开。
        setCurveMode(CurveMode.OFF, refresh = false)
        applyCardVisibility()
        // ⭐ 胎压卡数据订阅（聚焦速度卡时显示，用户要求）
        setupFocusTpms()
        cardViews().getOrNull(index)?.post { applyFocusTopAligned(index) }
    }

    /**
     * 退出聚焦模式，返回默认布局（全部卡片可见）。
     *
     * ⚠️ 不动[curveMode]：默认就是「不显示曲线」，
     * 若这里强制设 LIVE，退出聚焦会突然冒出四条线，与初始体验不一致。
     * 保持用户当前的双击选择即可。
     */
    private fun exitFocus() {
        if (focusedCardIndex == null) return
        focusedCardIndex = null
        applyCardVisibility(wasFocused = true)
    }

    /**
     * **双击的唯一入口**：不显示 → 实时 → 历史 → 不显示。
     *
     * 用户 2026-11-08 定案的三态循环。语义：
     * - [CurveMode.OFF]：只留数值大字，曲线区域保留 110dp 占位（不塌陷）
     * - [CurveMode.LIVE]：固定步长滚动，最新点贴右边缘
     * - [CurveMode.HISTORY]：进入瞬间拍快照，全量铺满一屏、彩色渐变，之后不再变化
     */
    private fun cycleCurveMode() {
        val next = when (curveMode) {
            CurveMode.OFF -> CurveMode.LIVE
            CurveMode.LIVE -> CurveMode.HISTORY
            CurveMode.HISTORY -> CurveMode.OFF
        }
        setCurveMode(next)
    }

    /**
     * 设置曲线模式并刷新四张卡。
     *
     * @param refresh false 表示调用方自己会再调 [applyCardVisibility]（避免重复刷新）
     */
    private fun setCurveMode(mode: CurveMode, refresh: Boolean = true) {
        val previous = curveMode
        curveMode = mode
        when (mode) {
            CurveMode.HISTORY -> captureHistorySnapshot()
            CurveMode.LIVE -> {
                // 从历史/关闭切回实时：清掉快照立即回填当前曲线，
                // 否则要等下一帧 GPS/BMS 数据才恢复，会有一瞬间空白
                historySnapshots.fill(null)
                if (refresh && previous != CurveMode.LIVE) redrawFromTracker()
            }
            CurveMode.OFF -> historySnapshots.fill(null)
        }
        if (refresh) applyCardVisibility()
    }

    /**
     * ⭐ **按连接设置页的「实时曲线默认」复位进入实时页时的曲线状态**
     * （用户 2026-11-09）。
     *
     * 偏好 `true` → [CurveMode.LIVE]（显示曲线）；`false`（默认）→ [CurveMode.OFF]。
     * 只决定**进入时的初始状态**，双击三态循环 [cycleCurveMode] 完全不变，
     * 用户在本页手动切到实时/历史后，只要不离开本页就保持用户的选择。
     *
     * @param force `true` = 真正「切入实时页」（首次创建 / 从别的标签页切回来），
     *              无条件按偏好复位；`false` = 仅从其它 Activity 返回（onResume），
     *              只有**偏好值本身变了**才复位，避免把用户双击选中的历史曲线打回。
     *
     * ⚠️ **聚焦态一律跳过**：[onCardFocused] 定下的规矩是「聚焦后强制隐藏曲线」，
     *    偏好只作用于四张卡全显示的主布局，不能让聚焦卡突然冒出四条线。
     *    聚焦态下改了设置，等退出聚焦 / 下次进入主布局才会体现。
     */
    private fun syncCurveModeOnEnter(force: Boolean) {
        if (_binding == null || focusedCardIndex != null) return
        val pref = appPrefs.realtimeCurveShowByDefault
        if (!force && lastCurvePrefApplied == pref) return
        lastCurvePrefApplied = pref

        val target = if (pref) CurveMode.LIVE else CurveMode.OFF
        if (curveMode == target) return
        // refresh=false：下面统一重绘 + 刷显隐，避免重复刷新
        setCurveMode(target, refresh = false)
        redrawFromTracker()
        applyCardVisibility()
    }

    /**
     * 拍历史快照：四条队列各拷一份，按画布宽度做 min/max 包络压缩。
     *
     * ⭐ 用「按像素列取 min/max」而不是 LTTB / 道格拉斯-普克：
     * 全局最大值所在桶的最大点就是它本人，**数学上不可能被抹掉**，
     * 谷值和急刹突变同理；没有阈值可调，也不会断层。
     *
     * ⭐ 目标列数取画布可用宽度（px）：包络后最多 2×列数 个点，
     * 与绘制像素量级一致，既不会过密发糊，也不会丢形状。
     */
    private fun captureHistorySnapshot() {
        val b = _binding ?: return
        val cols = b.chartSpeed.width.coerceAtLeast(b.chartPower.width)
            .takeIf { it > 0 } ?: 720
        historySnapshots[CARD_SPEED] = envelope(tracker.speedPoints, cols)
        historySnapshots[CARD_POWER] = envelope(tracker.powerPoints, cols)
        historySnapshots[CARD_CURRENT] = envelope(tracker.currentPoints, cols)
        historySnapshots[CARD_DIFF] = envelope(tracker.diffPoints, cols)
    }

    private fun envelope(q: ArrayDeque<Float>, cols: Int): FloatArray {
        val all = io.github.supit.rideassist.util.SeriesMath.all(q)
        // ⭐ 全零（静止 / 断连）不画：那条贴底横线零信息量，
        //    与实时模式 setSeriesIfActive 的 IDLE_EPSILON_MV 同口径
        if (all.none { kotlin.math.abs(it) >= IDLE_EPSILON_MV }) return FloatArray(0)
        return io.github.supit.rideassist.util.EnvelopeDownsampler.downsample(all, cols)
    }

    /**
     * 聚焦态下切换到上/下一张卡片（**循环**：第一张的上一张是最后一张）。
     *
     * @param delta -1 = 上一张，+1 = 下一张
     */
    private fun switchFocus(delta: Int) {
        val current = focusedCardIndex ?: return
        val next = ((current + delta) % CARD_COUNT + CARD_COUNT) % CARD_COUNT
        focusedCardIndex = next
        // ⭐ **不改动曲线状态** —— 曲线是全局的，切卡只换卡片，
        // 新卡片自动沿用切换前的显隐状态，不会突然冒出/消失一条线
        applyCardVisibility()
        // 让新卡滚到顶部（时间卡下方）
        cardViews().getOrNull(next)?.post { applyFocusTopAligned(next) }
    }

    /** 四张卡的有序视图列表（索引与 [CARD_COUNT] 对齐） */
    private fun cardViews(): List<android.view.View> {
        val b = _binding ?: return emptyList()
        return listOf(b.cardSpeed, b.cardPower, b.cardCurrent, b.cardDiff)
    }

    /** 四张卡对应的曲线视图 */
    private fun chartViews(): List<io.github.supit.rideassist.ui.widget.TrendChartView> {
        val b = _binding ?: return emptyList()
        return listOf(b.chartSpeed, b.chartPower, b.chartCurrent, b.chartDiff)
    }



    private fun applyFocusTopAligned(index: Int) {
        val b = _binding ?: return
        val content = b.scrollView.getChildAt(0) as? View ?: return
        val pad = resources.getDimensionPixelSize(R.dimen.page_padding)
        // ⚠️ 四边都显式写入原始值，不依赖当前 paddingXxx
        // （那些值可能已被上一轮聚焦偏移改过，依赖它们会累积误差）
        content.setPadding(pad, pad, pad, pad)
        // 聚焦内容总高小于视口，滚动位置归零确保时间卡真的在顶部
        b.scrollView.scrollTo(0, 0)
    }

    /**
     * 恢复默认布局：把内容容器 padding **还原成布局原始值**，并把滚动位置归零。
     *
     * ⚠️⚠️ **不能归零**（早前踩过的坑，用户实测发现）：
     * 内容容器在布局里是 `android:padding="@dimen/page_padding"`（10dp），
     * **这 10dp 就是「速度卡片到屏幕上沿的留白」**。
     * 聚焦时我们给它加 paddingTop 把卡片顶到屏幕中间；
     * 恢复时若写成 `setPadding(l, 0, r, b)`，会连原本的 10dp 一起抹掉 →
     * 恢复后速度卡直接贴到屏幕边缘，上方留白消失，且再也回不来。
     *
     * 所以必须还原成 `R.dimen.page_padding`（与布局引用同一份值，天然一致）。
     *
     * `scrollTo(0, 0)` 同样必要：聚焦时 scrollY 停在偏移上，
     * 只复位 padding 不复位滚动位置，页面仍会停在中间偏上。
     */
    private fun resetContentPadding() {
        val b = _binding ?: return
        val content = b.scrollView.getChildAt(0) as? View ?: return
        val pad = resources.getDimensionPixelSize(R.dimen.page_padding)
        content.setPadding(pad, pad, pad, pad)
        b.scrollView.scrollTo(0, 0)
    }


    /** 胎压聚焦卡：订阅 TpmsMonitor（数据含落盘恢复，重启立即有值） */
    private fun setupFocusTpms() {
        val app = io.github.supit.rideassist.YybmsAppHolder.from(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.tpmsMonitor.state.collect { renderFocusTpms(it) }
            }
        }
    }

    /** 渲染聚焦胎压卡：越界标红（口径与首页一致：<低压 或 >高压） */
    private fun renderFocusTpms(state: io.github.supit.rideassist.core.ble.tpms.TpmsMonitor.UiState) {
        val b = _binding ?: return
        val prefs = io.github.supit.rideassist.YybmsAppHolder.from(requireContext()).prefs
        val abnormal = ContextCompat.getColor(requireContext(), R.color.abnormal)
        val normal = ContextCompat.getColor(requireContext(), R.color.text_primary)
        val secondary = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        fun apply(pos: Int, view: android.widget.TextView) {
            val label = if (pos == io.github.supit.rideassist.core.ble.tpms.TpmsMonitor.POS_FRONT)
                getString(R.string.tpms_front) else getString(R.string.tpms_rear)
            val sn = if (pos == io.github.supit.rideassist.core.ble.tpms.TpmsMonitor.POS_FRONT)
                prefs.tpmsFrontSn else prefs.tpmsRearSn
            val r = sn?.let { state.readings[it] }
            when {
                r == null -> {
                    view.text = "$label --"
                    view.setTextColor(secondary)
                }
                else -> {
                    // ⭐ 格式「前轮 1.34Bar」（用户要求，悬浮窗同款）
                    view.text = "$label " +
                        String.format(java.util.Locale.US, "%.2f", r.bar) + "Bar"
                    view.setTextColor(
                        if (r.bar < prefs.tpmsLowBar || r.bar > prefs.tpmsHighBar) abnormal else normal
                    )
                }
            }
        }
        apply(io.github.supit.rideassist.core.ble.tpms.TpmsMonitor.POS_FRONT, b.tvClockTpmsFront)
        apply(io.github.supit.rideassist.core.ble.tpms.TpmsMonitor.POS_REAR, b.tvClockTpmsRear)
        // ⭐ 功率卡顶部胎压（用户要求）：非聚焦时显示；聚焦时隐藏（胎压在时间卡两侧已有）
        if (focusedCardIndex != null) {
            b.rowPowerTpms.visibility = View.GONE
        } else {
            b.rowPowerTpms.visibility = View.VISIBLE
            apply(io.github.supit.rideassist.core.ble.tpms.TpmsMonitor.POS_FRONT, b.tvPowerTpmsFront)
            apply(io.github.supit.rideassist.core.ble.tpms.TpmsMonitor.POS_REAR, b.tvPowerTpmsRear)
        }
    }

    /** 应用显隐状态到实际视图。
     *
     * 聚焦时：聚焦卡 VISIBLE + 时间卡 VISIBLE，其余卡 GONE（完全移出）。
     * 非聚焦时：全部 VISIBLE + 时间卡 GONE，曲线按 [curveMode] 用
     * INVISIBLE 切换（保留 110dp 占位，页面不会跳动）。
     *
     * @param wasFocused 是否**从聚焦态退出**（只有这种场景才复位 padding + 滚动位置）。
     *        ⚠️ 不能简单写 `if (focus == null)` —— 那会让**普通状态下单击切曲线**
     *        也把页面强行滚回顶部，用户会莫名"跳一下"。
     */
    private fun applyCardVisibility(wasFocused: Boolean = false) {
        val b = _binding ?: return
        val focus = focusedCardIndex
        // 聚焦时才显示顶部时间卡
        b.cardFocusClock.visibility = if (focus == null) View.GONE else View.VISIBLE
        // ⭐ 只有真正退出聚焦时才复位：padding + 滚动位置一起归零，
        // 否则恢复后页面停在中间偏移处，卡片会顶到屏幕上沿
        if (focus == null && wasFocused) resetContentPadding()
        cardViews().forEachIndexed { index, card ->
            card.visibility = if (focus == null || focus == index) View.VISIBLE else View.GONE
        }
        // ⭐ 速度卡聚焦时隐藏卡内时间：顶部已有大时间卡，避免重复显示。
        // 用 INVISIBLE 而非 GONE —— 标题行高度由最高的子控件决定，
        // 速度卡内的时间是 20sp，比标题 15sp 高，GONE 会让标题行塌陷。
        // ⭐ 聚焦速度卡时隐藏两个**覆盖层**（时间 / 里程）。
        // 用 INVISIBLE 而非 GONE：覆盖层不参与排版，两种都不会引起塌陷，
        // 但 INVISIBLE 更保险（万一将来结构变了，GONE 会真的占位）。
        // ⚠️⚠️ **聚焦时必须用 GONE，不能用 INVISIBLE**（实测踩到）：
        // INVISIBLE 的 TextView **仍参与测量**，20sp 加粗时间的行高(≈111px)
        // 会一直把标题行撑高 → 聚焦时整张卡内容下移
        // （用户反馈"速度两字 + 数值 + km/h 都下移了"）。
        // GONE 才真正不占空间 —— 这里时间本就该消失，用 GONE 是对的。
        val overlayHidden = if (focus == CARD_SPEED) View.GONE else View.VISIBLE
        b.tvRealtimeClock.visibility = overlayHidden
        // ⭐ 聚焦时归零聚焦卡的 marginTop，间距统一由时间卡 marginBottom 承担
        applyFocusCardMargin(focus)
        // ⭐ 两张附属卡都随聚焦目标变化 → 切换聚焦时必须跟着刷新显隐
        renderPeakSection()
        // ⭐ 需求4：GPS 丢失提示卡只在「聚焦速度卡」时可见。
        // 非聚焦或聚焦其他卡时强制 GONE —— 普通布局下多一张卡会让速度卡高度跳动。
        b.cardGpsLost.visibility =
            if (focus == CARD_SPEED && gpsLostSinceMs != 0L) View.VISIBLE else View.GONE
        // ⭐ 独立胎压卡已取消（用户要求）：胎压统一显示在聚焦时间卡两侧

        // ⭐ 聚焦速度卡才注册传感器（加速度计有开销，不需要时不注册）
        if (focus == CARD_SPEED) {
            ensureLossEstimator()
            gpsLostHandler.removeCallbacks(gpsLostTick)
            if (gpsLostSinceMs != 0L) {
                renderGpsLostCard()
                gpsLostHandler.postDelayed(gpsLostTick, 1000L)
            }
        } else {
            releaseLossEstimator()
        }
        chartViews().forEachIndexed { index, chart ->
            applyChartMode(index, chart)
        }
    }

    /**
     * 把当前 [curveMode] 应用到单张曲线控件。
     *
     * - OFF → INVISIBLE（保留 110dp 占位，卡片高度不塌陷）
     * - LIVE → VISIBLE，数据由 `renderCurve` / `pushSpeedSample` 持续喂
     * - HISTORY → VISIBLE + 灌入快照，之后不再随新数据变化
     */
    private fun applyChartMode(index: Int, chart: io.github.supit.rideassist.ui.widget.TrendChartView) {
        when (curveMode) {
            CurveMode.OFF -> chart.setCurveVisible(false)
            CurveMode.LIVE -> chart.setCurveVisible(true)
            CurveMode.HISTORY -> {
                chart.setCurveVisible(true)
                val snapshot = historySnapshots.getOrNull(index)
                if (snapshot != null) chart.setHistorySeries(snapshot)
            }
        }
    }


    /**
     * 聚焦时把**聚焦卡的 marginTop 归零**，间距统一由顶部时间卡的
     * `marginBottom=12dp` 承担。
     *
     * ⭐ 为什么必须归零（用户要求"涉及所有卡片"）：
     * 布局里 `card_power/current/diff/gps_lost` 都有 `marginTop=6dp`，
     * 而 `card_speed` 没有。若不归零：
     *   聚焦速度卡 → 时间卡 12dp（速度卡无margin）
     *   聚焦功率卡 → 时间卡 12dp + 功率卡 6dp = **18dp**（间距不均）
     * 四种聚焦场景表现不一致。
     *
     * 归零后统一是 12dp，恢复时再把 margin 还原回布局原值。
     */
    private fun applyFocusCardMargin(focus: Int?) {
        val b = _binding ?: return
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        cardViews().forEachIndexed { index, card ->
            setTopMargin(card, if (focus == index) 0 else if (index == CARD_SPEED) 0 else gap)
        }
        // ⚠️ GPS 丢失卡是速度卡的**附属卡**（不在 cardViews() 里），
        // 它自带 marginTop=6dp。聚焦速度卡且它可见时同样要归零，
        // 否则间距变成 12 + 6 = 18dp。
        setTopMargin(b.cardGpsLost, if (focus == CARD_SPEED) 0 else gap)

        // ⭐ 「XX 记录卡」同样是**附属卡**（跟在聚焦卡下面），
        //   它的 marginTop 必须跟聚焦卡保持一致，
        //   否则速度卡聚焦时是 0dp、其他三张卡聚焦时仍是 6dp → 间距不一致
        //   （用户反馈："压差/电流/功率聚焦时记录卡位置不对"）。
        setTopMargin(b.cardPeakRecord, if (focus == null) 0 else gap)
        // 压差历史卡跟在记录卡下面，同属聚焦附属卡，按聚焦态统一间距
        setTopMargin(b.cardDiffHistory, if (focus == null) 0 else gap)
        // 极速 / 电流 / 功率记录卡同理（各自只在对应卡片聚焦时可见）
        setTopMargin(b.cardTopSpeedHistory, if (focus == null) 0 else gap)
        setTopMargin(b.cardCurrentHistory, if (focus == null) 0 else gap)
        setTopMargin(b.cardPowerHistory, if (focus == null) 0 else gap)
    }

    /** 设置 topMargin（已是目标像素值时跳过，避免重复触发 layout） */
    private fun setTopMargin(view: android.view.View, px: Int) {
        val lp = view.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin != px) {
            lp.topMargin = px
            view.layoutParams = lp
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRealtimeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // ⭐⭐ 2026-10-20 改版：**退出 APP = 全新会话**（用户要求）。
        // 旧逻辑「3 分钟内重开恢复上次会话」已移除 ——
        // 骑行数据 / 峰值记录 / 曲线只存在于进程内存，退出即清零。
        // ⚠️ 2026-11-09 唯一例外：聚焦压差卡下面的「最大压差单体历史」按用户要求
        //    单独落盘，跨 APP 重启保留（由 YybmsApp 恢复，本页照常读 tracker 渲染）。
        super.onViewCreated(view, savedInstanceState)
        // ⭐ 首次进入实时页：按连接设置页的「实时曲线默认」定初始显隐
        //    （默认 false = 不显示曲线，保持旧体验）
        syncCurveModeOnEnter(force = true)
        // 回填会话记录（Fragment重建 / 断连 / 切页回来后仍保留）
        // ⚠️ 只重绘，**不push 新采样点**，否则回填动作会污染曲线
        redrawFromTracker()
        observeBatteryData()
        setupCardGestures()
        setupHistoryClearGestures()
        startRealtimeClock()
        // ⭐⭐ **首次进入必须显式同步一次显隐状态**（用户反馈：
        //「首次进去实时界面，除了电流卡片，其他 3 个卡片都默认显示了曲线」）。
        // 根因：applyCardVisibility() 之前只在手势/聚焦时才调用，
        // 首次进入没人调 → 四张卡沿用布局里的默认可见性。
        // 布局已统一设成 invisible，这里再统一刷一次，双保险。
        applyCardVisibility()
        // ⭐ 里程初始显示「约0m」（协议无里程字段，由 App 积分得出）
    }

    // ==================== 实时页时钟（速度卡顶部正中） ====================

    /**
     * 速度卡标题行中间的当前时间。
     *
     * ⚠️ 与首页的时钟是**两套独立实现**：首页那个在 MonitorFragment，
     * 这里在 RealtimeFragment。两个页面生命周期独立（show/hide 而非 replace），
     * 各自维护自己的 Handler 反而更简单可靠。
     */
    private fun startRealtimeClock() {
        val b = _binding ?: return
        realtimeClockHandler.removeCallbacks(realtimeClockTick)
        renderRealtimeClock()
        realtimeClockHandler.postDelayed(realtimeClockTick, CLOCK_INTERVAL_MS)
    }

    private fun stopRealtimeClock() {
        realtimeClockHandler.removeCallbacks(realtimeClockTick)
    }

    private val realtimeClockTick = object : Runnable {
        override fun run() {
            renderRealtimeClock()
            realtimeClockHandler.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    private fun renderRealtimeClock() {
        val b = _binding ?: return
        val now = REALTIME_TIME_FORMAT.format(Date())
        b.tvRealtimeClock.text = now
        // 聚焦模式顶部时间卡同步刷新（需求：单独显示时上方也有时间）
        b.tvFocusClock.text = now
    }

    /**
     * 只重绘不采样（用于页面重建时回填历史曲线）。
     *
     * @param statView 「最大/最小」小字；速度卡片不需要（右上角已有会话 Max），传 null
     * @param unit 仅用于 Max/Min 文案；水印的单位是**独立 TextView**（布局里静态设好），
     *             所以这里不参与赋值，否则数值会又大又带单位。
     */
    private fun redrawCurve(
        chart: io.github.supit.rideassist.ui.widget.TrendChartView,
        q: ArrayDeque<Float>,
        nowView: android.widget.TextView,
        statView: android.widget.TextView?,
        now: Float,
        unit: String,
        maxOverride: Float? = null
    ) {
        // ⭐ 历史模式下曲线已定格在快照上，回填只更新大字与统计，不许覆盖曲线
        val historyFixed = curveMode == CurveMode.HISTORY
        // ⚠️ 与 renderCurve / redrawWithoutSampling 保持一致：全零不画
        if (!historyFixed) setSeriesIfActive(chart, q)
        nowView.text = String.format("%.0f", now)
        statView?.let { applyStat(it, statOf(q, unit, maxOverride, statWindow(historyFixed))) }
    }

    /**
     * 统计窗口：实时模式只看最近 [SessionSpeedTracker.RECENT_WINDOW] 点（反映近期状态），
     * 历史模式看全会话（用户要求 Min 取全会话最低值）。
     */
    private fun statWindow(historyFixed: Boolean): Int =
        if (historyFixed) Int.MAX_VALUE else SessionSpeedTracker.RECENT_WINDOW

    override fun onResume() {
        super.onResume()
        // ⭐ 从「连接设置」页等其它 Activity 返回时，Fragment 不会走
        //    onHiddenChanged（没被 hide），但偏好可能刚被改过：
        //    这里只在**偏好值真的变了**且本页可见时复位，
        //    不打断用户自己双击选中的历史曲线。
        //    隐藏中的预创建 Fragment 不处理，等真正切进来由 onHiddenChanged 负责。
        if (!isHidden) syncCurveModeOnEnter(force = false)
        if (hasLocationPermission()) {
            startGpsUpdates()
        } else if (!isHidden) {
            // ⭐ 预创建的隐藏 Fragment **不在这里申请权限** ——
            // 冷启动时首页的权限链先弹，这里再弹会连环弹窗。
            // 无权限时等用户真正切到本页（onHiddenChanged）再申请。
            requestLocationPermission()
        }
        startRealtimeClock()
    }

    override fun onPause() {
        super.onPause()
        stopGpsUpdates()
        // ⭐ 退后台后 GPS 停供，速度证据必须标为「无」，
        //    否则应用级采集器会拿一个陈旧速度继续判定在动。
        tracker.liveSpeedKmh = null
        stopRealtimeClock()
    }

    /**
     * MainActivity 用 show/hide 切页（不是 remove/add），被 hide 的 Fragment
     * 生命周期会走到 onHiddenChanged(true)，但 **onPause 未必被调用**。
     *
     * ⭐ GPS 订阅若在此停下，切走再切回来就会出现"速度曲线断一段"，
     * 看起来像数据被清空。所以只有**真正离开**（onPause，App 退到后台）
     * 才停订阅；被 hide 只是切页，订阅保持不断。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            // ⭐ 真正「切入实时页」（MainActivity 是 show/hide，不重建 Fragment）：
            //    无条件按「实时曲线默认」复位初始显隐
            syncCurveModeOnEnter(force = true)
            // 切回本页：恢复 GPS 订阅与时钟，但**不清空**任何历史数据
            if (hasLocationPermission()) startGpsUpdates()
            startRealtimeClock()
            redrawFromTracker()
        }
    }

    /** 从 tracker 回填全部四条曲线与大水印，不产生新采样点 */
    private fun redrawFromTracker() {
        val b = _binding ?: return
        applySpeedMax()
        redrawCurve(b.chartSpeed, tracker.speedPoints, b.tvSpeedNow, null, tracker.speedNow, SPEED_UNIT)
        redrawCurve(b.chartPower, tracker.powerPoints, b.tvPowerNow, b.tvPowerStat, tracker.powerNow, UNIT_WATT,
            maxOverride = tracker.peakRecords[io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.CARD_POWER]?.value)
        redrawCurve(b.chartCurrent, tracker.currentPoints, b.tvCurrentNow, b.tvCurrentStat, tracker.currentNow, UNIT_AMPERE,
            maxOverride = tracker.peakRecords[io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.CARD_CURRENT]?.value)
        redrawCurve(b.chartDiff, tracker.diffPoints, b.tvDiffNow, b.tvDiffStat, tracker.diffNow, UNIT_MV,
            maxOverride = tracker.peakRecords[io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.CARD_DIFF]?.value)
    }

    // ==================== GPS 速度 ====================

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        showNoGpsSource()
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    /**
     * 没有定位数据源（无权限/定位关闭/系统服务不可用）时的统一处理。
     *
     * ⚠️ 水印是速度卡片里**唯一**的速度显示（已按用户要求去掉 96sp 大数字），
     * 所以这里必须显式写 `--`，否则会残留上一次的旧速度值，误导用户。
     * 同时补一个 0 采样点，避免曲线区域整块空白（看起来像功能坏了）。
     */
    private fun showNoGpsSource() {
        val b = _binding ?: return
        // ⚠️ 数值和单位**都要**置空，否则会剩一个孤零零的「km/h」
        b.tvSpeedNow.text = getString(R.string.value_none)
        b.tvSpeedNowUnit.text = ""
        // ⭐ 无GPS 数据源 = 没有速度信号 → 曲线不画（塞 0 会出现贴底横线）
        hasSpeedSignal = false
        speedFilter.reset()
        motionProbe.reset()
        tracker.liveSpeedKmh = null
        // 无条件调用，队列非空时它会清空并重绘
        pushSpeedSample(0)
        applySpeedMax()
        // ⚠️ 这是「无数据源」（无权限/定位关闭），**不是** GPS 临时失锁，
        // 所以清掉丢失状态与推算器，不要显示「已丢失 XX」——
        // 那种情况下用户根本没在用定位，显示丢失时长是误导。
        gpsLostHandler.removeCallbacks(gpsLostTick)
        lossEstimator?.onGpsRecovered()
        gpsLostSinceMs = 0L
        b.cardGpsLost.visibility = View.GONE
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        val b = _binding ?: return
        if (!hasLocationPermission()) {
            showNoGpsSource()
            return
        }
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        locationManager = lm
        if (lm == null) {
            showNoGpsSource()
            return
        }
        // ⭐ 重新订阅时清空位移窗口：暂停前的旧点和刚恢复的帧跨了几分钟，
        //    算出来的「位移平均速度」毫无意义，会误判成在移动/静止
        motionProbe.reset()
        try {
            // 先立刻取一次，避免等待回调
            lm.getProviders(true)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { renderSpeed(it) }

            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
            )
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            showNoGpsSource()
        }
    }

    private fun stopGpsUpdates() {
        try {
            locationManager?.removeUpdates(this)
        } catch (e: SecurityException) {
            // 忽略：未授权时本就没有注册
        }
    }

    override fun onLocationChanged(location: Location) = renderSpeed(location)

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        showNoGpsSource()
    }

    /**
     * 速度计算与采样：m/s × 3.6 = km/h（与官方口径一致）。
     *
     * 处理：
     * - **平滑**：低通滤波，避免定位抖动导致数字乱跳
     * - **阈值 5km/h**：低于该值直接按0 处理（GPS 静态漂移 + 步行速度都压成 0）
     * - **只取整数**：符合阅读习惯，也避免精度幻觉
     *
     * ⚠️ 采样进队列的是**显示值**（已阈值归零 + 取整），不是 GPS 原始值——
     * 否则曲线会在 0~4km/h 出现锯齿，而水印恒显0，两者对不上。
     */
    private fun renderSpeed(loc: Location) {
        val b = _binding ?: return

        // ⭐⭐ GPS 恢复判据：**连续 N 次刷新都拿到速度**才退出丢失状态。
        //
        // ⚠️⚠️ 关键修正：原来只看「有没有定位帧」就算恢复，这是错的 ——
        // 隧道/树荫下会持续收到 `hasSpeed()==false` 的定位帧，
        // 每来一帧就重置恢复确认计时，导致**永远退不出丢失状态**，
        // 而用户看到的就是「明明有信号却一直显示 GPS 已丢失」。
        //
        // → 现在先分流：有速度 = 真恢复；只有定位没速度 = 仍是丢失态，
        //   立刻重置连续计数（相当于「中断」）。
        if (!loc.hasSpeed()) {
            // ⚠️ 有定位但**无速度解算**（隧道/树荫下常见）→ 走 GPS 丢失逻辑。
            // 先把连续计数清零：有速度才算恢复，这一帧不算。
            // ⚠️ 不要在这里调lossEstimator.onGpsLost() ——
            // handleGpsLost 内部会在「首次丢失」时调用并传入推算参数，
            // 在这里调一次等于传了空参数的错误调用。
            gpsRecoverSpeedCount = 0
            return handleGpsLost(headingOf(loc))
        }

        // ⭐⭐⭐ **速度来源可信度过滤**（2026-11-08 用户第二次反馈漂移）。
        //
        // 为什么必须有这道闸：
        // 1. 同时订阅了 `NETWORK_PROVIDER` —— 网络定位的 `speed` 是基站/热点
        //    推算出来的，**静止时会报出几到十几 km/h**，完全不能当速度用。
        // 2. `startGpsUpdates()` 启动时会把 `getLastKnownLocation()` 喂进来，
        //    那是**缓存的旧位置**（可能是几十分钟前骑行途中的），speed 是
        //    当时的值 —— 打开 APP 瞬间就会凭空出现一个速度点。
        // 3. 精度极差的定位帧（accuracy 几十上百米）速度同样不可信。
        //
        // 未通过就不更新速度、不喂运动锁存、不推进「GPS 恢复」计数。
        if (!isSpeedTrusted(loc)) {
            Log.d(
                TAG_GPS,
                "丢弃不可信速度 p=${loc.provider} acc=${loc.accuracy} " +
                    "age=${System.currentTimeMillis() - loc.time}ms"
            )
            return
        }

        // ============ 到这里说明本帧**有速度解算** ============
        // ⭐ 只有连续 GPS_RECOVER_CONFIRM_COUNT 次都拿到速度，才正式退出丢失态。
        if (gpsLostSinceMs != 0L) {
            gpsRecoverSpeedCount++
            if (gpsRecoverSpeedCount >= GPS_RECOVER_CONFIRM_COUNT) {
                // 连续够多次都有速度 → 确认恢复，正式清零
                clearGpsLostState()
            }
            // ⚠️ 恢复未确认期间**继续走丢失推算逻辑**（速度照常显示），
            //   只是「已丢失时长」继续累加，不清零。
        }
        lossEstimator?.onGpsRecovered()

        val kmh = loc.speed * 3.6f
        hasSpeedSignal = true
        // ⭐⭐⭐ 第三道闸：**位移否决**。
        // 静止时 Location.getSpeed() 可能持续报 5~9km/h（贴窗/高楼下/刚定位），
        // 光靠速度阈值挡不干净；但同一段时间里位置几乎没动。
        //
        // ⚠️ 判据收在 SpeedDisplay.shouldVetoByDisplacement 里（有单测），
        // 关键是带了「速度已达 15km/h 就不再用位移否决」的上限 ——
        // 位移是窗口平均值、天然滞后，等红灯后起步时若严格按位移否决，
        // 绿灯亮了速度表还会趴好几秒。
        val placeKmh = motionProbe.accept(loc.latitude, loc.longitude, loc.time)
        val still = SpeedDisplay.shouldVetoByDisplacement(kmh, placeKmh)
        if (still) tracker.resetMotionLatch()
        // ⭐⭐ 低通 / 5km/h 归零 / 取整统一由 SpeedDisplay 决定（唯一口径）。
        //    门控结论来自 Application 级的运动锁存：电流只在起步上锁，
        //    锁存后滑行（电流 0）由 GPS 速度维持，不会再把 40km/h 压成 0。
        val shown = speedFilter.push(kmh, if (still) false else tracker.noteGpsSpeed(kmh))
        // ⭐ 把低通后的真实速度共享给应用级采集器（MoveGate 第三路证据）；
        //   门控生效时 realSpeed=null，第三路证据同步失效。
        tracker.liveSpeedKmh = speedFilter.realSpeed
        // ⭐ 会话极速的唯一写入口；悬浮窗靠 speedRevisionFlow 同步
        tracker.noteSpeed(shown)
        // ⭐ 漂移排查用（debug 包直接 `adb logcat -s RideAssistGps` 看）：
        //    真出现速度曲线时，一眼就能看出是 GPS 报的速度、还是门控没拦住
        Log.d(
            TAG_GPS,
            "gps v=${"%.1f".format(kmh)} acc=${loc.accuracy.toInt()}m " +
                "place=${if (placeKmh < 0f) "样本不足" else "%.1f".format(placeKmh)} " +
                "still=$still shown=$shown latched=${tracker.motionLatched}"
        )
        applySpeedMax()
        renderSpeedColors(shown)
        pushSpeedSample(shown)
    }

    /**
     * 本帧的速度值是否可信（见 [renderSpeed] 里的三道闸）。
     *
     * ⚠️ 只要有一项不满足就整帧丢弃，绝不"打折使用"：
     * 半可信的速度一旦进了运动锁存，就会自己维持自己（锁存保持只看速度），
     * 变成一条再也停不下来的假曲线。
     */
    private fun isSpeedTrusted(loc: Location): Boolean {
        // ① 只认卫星定位。NETWORK_PROVIDER 的 speed 在静止时会凭空报出几到十几 km/h
        if (loc.provider != LocationManager.GPS_PROVIDER) return false
        // ② 不要缓存位置：getLastKnownLocation() 可能来自几十分钟前的骑行
        if (System.currentTimeMillis() - loc.time > GPS_FRESH_MS) return false
        // ③ 精度太差的帧速度噪声极大（accuracy <= 0 表示未知，放行）
        if (loc.accuracy > 0f && loc.accuracy > GPS_MAX_ACCURACY_M) return false
        return true
    }

    /**
     * **GPS 信号丢失**处理（需求 4）。
     *
     * 优先级按用户要求：
     * 1. 能用陀螺仪推算 → 显示推算速度（最多推算 60 秒）
     * 2. 超过 60 秒 / 无陀螺仪 / 没有车头方向 → **显示丢失前的速度**（保持）
     *
     * ⚠️ 推算值**只用于显示**，不写回 [tracker.speedPoints] 曲线，
     * 避免推算误差污染真实采样数据。
     */
    private fun handleGpsLost(headingDegrees: Float) {
        val b = _binding ?: return
        val now = SystemClock.elapsedRealtime()

        // ⭐ 本帧没有速度解算，但定位帧还是到了 —— 用「丢失前/推算速度」继续
        //    喂运动锁存，避免隧道/树荫下因 15 秒没有速度帧而把滑行锁存误解除。
        //    车真停稳时该值为 0，锁存会在 5 秒后自然解开。
        tracker.noteGpsSpeed(lastValidSpeed().toFloat())

        // ⭐⭐ **还没开始运动 → 不跟踪丢失**（用户要求："现在还没有开始动，
        // 根本不应该出现这个和任何曲线"）。
        // 静止时 GPS 本来就没有速度解算，报「丢失」是误报。
        // 用电流 >1A 判「开始动」（用户给的定义）。
        if (!shouldTrackGpsLoss()) {
            if (gpsLostSinceMs != 0L) clearGpsLostState()
            //⚠️ 显示 **0** 而不是 `--`（用户反馈"-- 很丑，原来是 0"）。
            // 静止是**正常状态**，不是"无数据"——车停着本来就是 0km/h，
            // 用 `--` 会让人以为设备断了。
            b.tvSpeedNow.text = "0"
            renderSpeedColors(0)
            applySpeedMax()
            return
        }

        // 首次丢失：记录起始时刻并启动推算器
        if (gpsLostSinceMs == 0L) {
            gpsLostSinceMs = now
            // ⭐ 又丢失了 → 重置「连续恢复计数」。
            // 之前若已恢复了几次（抖动），此刻全部作废，从 0 重新数。
            gpsRecoverSpeedCount = 0
            // 丢失前的速度：优先用低通后的值，没有则用 0
            val lastValid = speedFilter.realSpeed?.toInt() ?: 0
            ensureLossEstimator().onGpsLost(lastValid.toFloat(), headingDegrees, now)
            // 启动每秒推进推算 + 刷新「已丢失 XX」
            gpsLostHandler.removeCallbacks(gpsLostTick)
            gpsLostHandler.postDelayed(gpsLostTick, 1000L)
        }

        val est = lossEstimator
        val shown: Int
        if (est != null) {
            est.update(now)
            shown = if (est.isEstimating) est.estimatedSpeed.toInt() else lastValidSpeed()
        } else {
            // 无陀螺仪 → 保持丢失前的速度
            shown = lastValidSpeed()
        }
        hasSpeedSignal = true          // 推算中仍视为「有速度数据」
        renderSpeedColors(shown)
        b.tvSpeedNow.text = String.format("%.0f", shown.toFloat())
        applySpeedMax()
        // ⭐ 推算值**照常写回曲线**（用户要求）：曲线在 GPS 丢失期间继续延伸，
        // 而不是停在丢失那一刻断掉。超过 60 秒推算上限后，
        // 显示与曲线都转为「保持丢失前速度」。
        pushSpeedSample(shown)
        renderGpsLostCard()
    }

    /**
     * 清除 GPS 丢失状态（停止推算、隐藏提示卡）。
     *
     * 用于「静止时不该跟踪丢失」的场景 —— 见 [handleGpsLost]。
     */
    private fun clearGpsLostState() {
        gpsLostHandler.removeCallbacks(gpsLostTick)
        lossEstimator?.onGpsRecovered()
        gpsLostSinceMs = 0L
        // ⚠️ 一起重置连续计数，否则下次丢失时会带着旧的计数，
        // 可能刚丢 1 帧就因「已连有 2 次速度」而立即误判恢复
        gpsRecoverSpeedCount = 0
        _binding?.cardGpsLost?.visibility = View.GONE
    }

    /** 丢失前的速度（km/h，整数），至少为 0 */
    private fun lastValidSpeed(): Int =
        (speedFilter.realSpeed?.toInt() ?: 0).coerceAtLeast(0)

    /** 取定位的方位角（度），无则 -1 */
    private fun headingOf(loc: Location): Float =
        if (loc.hasBearing()) loc.bearing else -1f

    /**
     * 渲染「GPS 信号丢失」卡片（需求 4）。
     *
     * 时长格式：`3秒` / `3分钟2秒`（超过 1 分钟才显示分钟部分）。
     */
    private fun renderGpsLostCard() {
        val b = _binding ?: return
        val start = gpsLostSinceMs
        // ⚠️⚠️ **必须同时满足两个条件**：
        // 1. 确实处于 GPS 丢失状态（start != 0）
        // 2. **正在聚焦速度卡** —— 用户要求"只出现在被聚焦的时候"。
        //    少了条件 2 会导致普通布局下凭空多出一张卡（实测踩到）。
        // 缺的第3 个条件（已开始运动）由 [shouldTrackGpsLoss] 控制：
        // 静止时 GPS 本来就没有速度解算，不该报"丢失"。
        if (start == 0L || focusedCardIndex != CARD_SPEED) {
            b.cardGpsLost.visibility = View.GONE
            return
        }
        val durMs = SystemClock.elapsedRealtime() - start
        // ⭐⭐ **不足 2 秒不显示**（用户要求："断开没超过 2 秒的时候不要显示这个格子，
        // 哪有丢失 0 秒的说法"）。
        // GPS 短暂抖动非常常见，2 秒内的丢失不该惊动用户。
        // 真正的隐藏/清零由 [clearGpsLostState] 处理，
        // 它还要求「恢复持续超过 2 秒」才执行 → 抖动不会误清空计时。
        if (durMs < GPS_LOST_SHOW_THRESHOLD_MS) {
            b.cardGpsLost.visibility = View.GONE
            return
        }
        b.cardGpsLost.visibility = View.VISIBLE
        b.tvGpsLostDuration.text = getString(
            R.string.gps_lost_fmt, formatDuration(durMs)
        )
    }

    /**
     * 是否应该跟踪 GPS 丢失状态。
     *
     * ⭐ 用户给的「开始动」定义：**电流超过 1A**。
     *
     * 为什么用电流而不是 GPS 速度：静止时 GPS 也可能解算出
     * 0.5km/h 之类的抖动值，会被误判成"在动"；
     * 而电池电流是物理量，静止时严格为 0，噪声极小。
     *
     * 静止时不跟踪的好处：不会因为「停在红绿灯前 GPS 抖动」而弹出
     * 「GPS 信号丢失」提示卡——那是误报。
     */
    // ==================== XX 最大值记录（四张卡通用） ====================

    // ⭐⭐ 四项峰值快照（含速度）统一由 YybmsApp 的应用级采集器喂入，
    // 本页不再逐帧比对 —— 喂数点挂在 Fragment 上会随 STARTED 作用域一起停，
    // 切到导航后记录就冻结了。这里只负责把 tracker 里的快照渲染成记录卡。

    /** 当前时刻 HH:mm（峰值记录用） */
    private fun nowClockText(): String =
        CLOCK_FORMAT.format(java.util.Date())

    /**
     * 峰值记录卡 + 压差历史卡统一刷新。
     *
     * 峰值记录卡只在聚焦且该卡有记录时出现；压差历史卡只在**聚焦压差卡**
     * 时出现（无记录时显示占位文案），两者都由这里刷新。
     *
     * 两个刷新入口：切换聚焦目标（[applyCardVisibility]）与每帧 BMS 数据
     * （[observeBatteryData]）。后者必要 —— 峰值由**应用级采集器**写入，
     * 实时页没有 StateFlow 订阅它，只能靠每帧重绘跟进。
     * 退到导航时 Fragment 停止刷新，但采集器仍在记；切回来时
     * `onViewCreated` 会再调一次 [applyCardVisibility] 补齐。
     */
    private fun renderPeakSection() {
        renderPeakRecordCard()
        renderDiffHistoryCard()
        renderTopSpeedHistoryCard()
        renderCurrentHistoryCard()
        renderPowerHistoryCard()
    }

    /**
     * 渲染「最近 10 次行程的最大压差单体」历史卡。
     *
     * ⭐ 2026-11-09 最终口径：**只在聚焦压差卡时出现**，实时页主界面
     *    （普通布局）与聚焦其它卡时整卡 GONE —— 它是聚焦模式的附属卡，
     *    主界面多一张卡会打乱四张数值卡的排版。
     *    但进入聚焦压差卡后**始终显示**（不再要求已有记录）：
     *    列表为空时显示「暂无压差＞X mV记录」占位（X = 设置页的当前阈值），
     *    避免用户以为功能不存在。
     *
     * 列表最新在最上，顺序由 [SessionSpeedTracker.diffHistory] 保证；
     * **一条 = 一次行程**（压差没超过记录阈值的行程不记），
     * 同行程内压差再创新高只会替换那一条，不会追加。
     * ⭐ 这份历史单独落盘，重开 APP 仍在（用户 2026-11-09 要求「一直能显示」）；
     * 只有用户显式点「清空本次会话」才会连同其它会话数据一起清空。
     *
     * ⚠️ 行是复用的：数据每秒刷新，但历史只在压差创本行程新高 / 换新行程时才变化，
     * 每帧重建 10 个 TextView 会白白触发 measure/layout。
     * Fragment 重建时容器是全新的（childCount=0），自然重新创建，
     * 所以**不要**把 View 缓存到 Fragment 字段里（会持有已销毁的布局）。
     * ⚠️ `llDiffHistory` 里只有数据行；标题与空态是它的兄弟节点，
     *    否则行循环会把它们当数据行改写掉。
     */
    private fun renderDiffHistoryCard() {
        val b = _binding ?: return
        // ⭐ 主界面（focus == null）/ 聚焦其它卡 → 整卡 GONE。
        val visible = focusedCardIndex == CARD_DIFF
        b.cardDiffHistory.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        val rows = tracker.diffHistory
        val emptyVisibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (b.tvDiffHistoryEmpty.visibility != emptyVisibility) {
            b.tvDiffHistoryEmpty.visibility = emptyVisibility
        }
        if (rows.isEmpty()) {
            // ⭐ 阈值可在连接设置页改，空态文案必须跟着变，不能写死 50mV
            val empty = getString(
                R.string.diff_history_empty,
                io.github.supit.rideassist.util.RecordThreshold.label(
                    tracker.currentThresholds.diffMv
                ),
            )
            if (b.tvDiffHistoryEmpty.text.toString() != empty) {
                b.tvDiffHistoryEmpty.text = empty
            }
        }

        val container = b.llDiffHistory
        val rowGapPx = (4 * resources.displayMetrics.density).toInt()
        while (container.childCount < rows.size) {
            container.addView(TextView(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                includeFontPadding = false
            })
        }
        for (i in 0 until container.childCount) {
            val tv = container.getChildAt(i) as TextView
            if (i >= rows.size) {
                if (tv.visibility != View.GONE) tv.visibility = View.GONE
                continue
            }
            if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE

            val e = rows[i]
            val line = getString(
                R.string.diff_history_row,
                e.date, e.cellIndex, e.cellVolt, e.currentA.toInt(), e.deviationMv,
            )
            if (tv.text.toString() != line) tv.text = line

            // 第一行（最新）用主色加粗，其余中性灰 —— 不加标题行也能看出方向。
            val newest = i == 0
            if (tv.tag != newest) {
                tv.tag = newest
                tv.setTextColor(
                    ContextCompat.getColor(
                        container.context,
                        if (newest) R.color.primary else R.color.text_secondary,
                    )
                )
                tv.setTypeface(
                    android.graphics.Typeface.MONOSPACE,
                    if (newest) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL,
                )
            }
            val lp = tv.layoutParams as LinearLayout.LayoutParams
            val wantGap = if (newest) 0 else rowGapPx
            if (lp.topMargin != wantGap) {
                lp.topMargin = wantGap
                tv.layoutParams = lp
            }
        }
    }

    /**
     * 渲染「最近 10 次行程的极速 / 电流 / 功率」历史卡（用户 2026-11-10）。
     *
     * 三者结构与口径完全一致，只是数值、单位、阈值和归属卡片不同，
     * 所以共用一个渲染器（避免三份 70 行重复代码）：
     * - **只在聚焦对应卡片时出现**；主界面 / 聚焦其它卡整卡 GONE；
     * - 聚焦后**始终显示**，无记录时显示「暂无XX＞阈值 单位记录」；
     * - 一条 = 一次行程（没超过记录阈值的不记），同行程只保留更极端的值；
     * - 最新在最上，顺序由 tracker 的列表保证；
     * - 单独落盘，重开 APP 仍在；用户显式清空会话 / 长按卡片清除才清空。
     *
     * ⚠️ 行是复用的 TextView，不要缓存 View 到 Fragment 字段（重建后会持有旧布局）。
     * ⚠️ 容器里只有数据行；标题与空态是它的兄弟节点。
     */
    private fun renderMetricHistoryCard(
        card: View,
        container: LinearLayout,
        emptyView: TextView,
        rows: List<SessionSpeedTracker.MetricHistoryEntry>,
        emptyFmtRes: Int,
        rowFmtRes: Int,
        thresholdLabel: String,
    ) {
        val emptyVisibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (emptyView.visibility != emptyVisibility) emptyView.visibility = emptyVisibility
        if (rows.isEmpty()) {
            // ⭐ 阈值来自连接设置页，空态文案跟着变
            val empty = getString(emptyFmtRes, thresholdLabel)
            if (emptyView.text.toString() != empty) emptyView.text = empty
        }

        val rowGapPx = (4 * resources.displayMetrics.density).toInt()
        while (container.childCount < rows.size) {
            container.addView(TextView(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                includeFontPadding = false
            })
        }
        for (i in 0 until container.childCount) {
            val tv = container.getChildAt(i) as TextView
            if (i >= rows.size) {
                if (tv.visibility != View.GONE) tv.visibility = View.GONE
                continue
            }
            if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE

            val e = rows[i]
            val line = getString(rowFmtRes, e.date, e.value, e.clock)
            if (tv.text.toString() != line) tv.text = line

            // 第一行（最新）用主色加粗，其余中性灰（与压差卡一致）
            val newest = i == 0
            if (tv.tag != newest) {
                tv.tag = newest
                tv.setTextColor(
                    ContextCompat.getColor(
                        container.context,
                        if (newest) R.color.primary else R.color.text_secondary,
                    )
                )
                tv.setTypeface(
                    android.graphics.Typeface.MONOSPACE,
                    if (newest) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL,
                )
            }
            val lp = tv.layoutParams as LinearLayout.LayoutParams
            val wantGap = if (newest) 0 else rowGapPx
            if (lp.topMargin != wantGap) {
                lp.topMargin = wantGap
                tv.layoutParams = lp
            }
        }
        card.visibility = View.VISIBLE
    }

    /** 极速卡：聚焦速度卡时显示 */
    private fun renderTopSpeedHistoryCard() {
        val b = _binding ?: return
        if (focusedCardIndex != CARD_SPEED) {
            b.cardTopSpeedHistory.visibility = View.GONE
            return
        }
        renderMetricHistoryCard(
            card = b.cardTopSpeedHistory,
            container = b.llTopSpeedHistory,
            emptyView = b.tvTopSpeedHistoryEmpty,
            rows = tracker.topSpeedHistory.map {
                SessionSpeedTracker.MetricHistoryEntry(it.tripId, it.speedKmh, it.date, it.clock)
            },
            emptyFmtRes = R.string.top_speed_history_empty,
            rowFmtRes = R.string.top_speed_history_row,
            thresholdLabel = RecordThreshold.label(tracker.currentThresholds.topSpeedKmh),
        )
    }

    /** 电流卡：聚焦电流卡时显示（用户 2026-11-10） */
    private fun renderCurrentHistoryCard() {
        val b = _binding ?: return
        if (focusedCardIndex != CARD_CURRENT) {
            b.cardCurrentHistory.visibility = View.GONE
            return
        }
        renderMetricHistoryCard(
            card = b.cardCurrentHistory,
            container = b.llCurrentHistory,
            emptyView = b.tvCurrentHistoryEmpty,
            rows = tracker.currentHistory,
            emptyFmtRes = R.string.current_history_empty,
            rowFmtRes = R.string.current_history_row,
            thresholdLabel = RecordThreshold.label(tracker.currentThresholds.currentA),
        )
    }

    /** 功率卡：聚焦功率卡时显示（用户 2026-11-10） */
    private fun renderPowerHistoryCard() {
        val b = _binding ?: return
        if (focusedCardIndex != CARD_POWER) {
            b.cardPowerHistory.visibility = View.GONE
            return
        }
        renderMetricHistoryCard(
            card = b.cardPowerHistory,
            container = b.llPowerHistory,
            emptyView = b.tvPowerHistoryEmpty,
            rows = tracker.powerHistory,
            emptyFmtRes = R.string.power_history_empty,
            rowFmtRes = R.string.power_history_row,
            thresholdLabel = RecordThreshold.label(
                tracker.currentThresholds.powerW,
                RecordThreshold.DEFAULT_POWER,
            ),
        )
    }

    /**
     * 渲染「XX 最大值记录」卡。
     *
     * 只在**聚焦某张卡**且该卡已有记录时可见（需求）；
     * 其他状态强制 GONE，避免普通布局下多一张卡导致高度跳动。
     */
    private fun renderPeakRecordCard() {
        val b = _binding ?: return
        val focus = focusedCardIndex
        val rec = if (focus == null) null else tracker.peakRecords[focus]
        if (focus == null || rec == null) {
            b.cardPeakRecord.visibility = View.GONE
            return
        }
        b.cardPeakRecord.visibility = View.VISIBLE

        // 标题已按用户要求去掉，只保留「最大XX ... @ 时间」这一行
        val headRes = when (focus) {
            CARD_SPEED -> R.string.peak_record_speed
            CARD_POWER -> R.string.peak_record_power
            CARD_CURRENT -> R.string.peak_record_current
            else -> R.string.peak_record_diff
        }
        // 四种卡都是「最大XX <数值> @ 时间」，只是单位/资源不同
        b.tvPeakRecordHeadline.text = getString(headRes, rec.value.toInt(), rec.clock)

        // 以下 4 行**仅压差卡**使用
        if (focus == CARD_DIFF) {
            b.tvPeakRecordDetail1.visibility = View.VISIBLE
            b.tvPeakRecordDetail2.visibility = View.VISIBLE
            b.tvPeakRecordDetail3.visibility = View.VISIBLE
            b.tvPeakRecordDetail1.text =
                getString(R.string.peak_detail_current, rec.current.toInt())
            b.tvPeakRecordDetail2.text = getString(
                R.string.peak_detail_max_cell, rec.maxCellIndex, rec.maxCellVolt
            )
            b.tvPeakRecordDetail3.text = getString(
                R.string.peak_detail_min_cell, rec.minCellIndex, rec.minCellVolt
            )
            // ⭐ 第四行：偏离其余电芯均值最多的那颗（一致性诊断）。
            // 用醒目红色，与上面三行的中性色区分开。
            // 少于 2 颗电芯时无「均值」可言，保持 GONE。
            if (rec.outlierCellIndex > 0) {
                b.tvPeakRecordDetail4.visibility = View.VISIBLE
                b.tvPeakRecordDetail4.text = getString(
                    R.string.peak_detail_outlier_cell,
                    rec.outlierCellIndex,
                    getString(
                        if (rec.outlierDeviation >= 0f) R.string.peak_outlier_high
                        else R.string.peak_outlier_low
                    ),
                    Math.abs(rec.outlierDeviation * 1000f),
                )
            } else {
                b.tvPeakRecordDetail4.visibility = View.GONE
            }
        } else {
            b.tvPeakRecordDetail1.visibility = View.GONE
            b.tvPeakRecordDetail2.visibility = View.GONE
            b.tvPeakRecordDetail3.visibility = View.GONE
            b.tvPeakRecordDetail4.visibility = View.GONE
        }
    }

    /**
     * ⭐⭐ 车没真正在动（运动锁存未建立 / 已解除）时不跟踪 GPS 丢失，
     * 也不启动推算器。判据直接复用 Application 级单例
     * [SessionSpeedTracker.speedAllowed]，与 [renderSpeed] / 悬浮前台服务
     * 完全同源 —— 不再另写一份电流阈值。
     */
    private fun shouldTrackGpsLoss(): Boolean =
        isRecording() && tracker.speedAllowed

    /**
     * ⭐⭐⭐ **是否处于「记录中」—— 只读**，不再驱动状态机。
     *
     * ## 这里修过一个很隐蔽的 bug（电流曲线整卡空白的真凶）
     * 旧版有个 `isRecording(currentAmp)`，直接返回
     * `tracker.updateRecordingState(...)` 的结果。而该函数的返回值是
     * **「状态本次是否发生迁移」**（几乎恒为 false），不是「当前是否在记录」。
     * 电流卡是四张卡里唯一传了 `currentAmp` 的，于是它每帧都拿到 false，
     * 走「未开始记录」分支把 `chartCurrent` 的序列清空 ——
     * 表现为「其他 3 张卡都有曲线，电流卡没有」。
     *
     * 更根本的问题是**状态机有两处驱动**：`YybmsApp` 应用级采集器已经在
     * 每帧调 `updateRecordingState()`，实时页再调一次等于两份判据互相打架。
     * 现在驱动点只保留 `YybmsApp` 一处，本页与四张曲线一律读锁存状态。
     *
     * 用户需求："电流大于 1A 是启动记录的条件，启动记录后**持续记录**，
     * 直到出现停止记录的条件（断开保护板连接多久），
     * 而不是电流小于 1A 就不记录，而且把曲线隐藏了。"
     */
    private fun isRecording(): Boolean = tracker.isRecording

    /**
     * 把时长格式化成中文可读形式。
     *
     * - < 1 分钟 → `3秒`
     * - >= 1 分钟 → `3分钟2秒`（秒为 0 时省略，如 `3分钟`）
     * - >= 1 小时 → `1小时3分钟`
     */
    /**
     * 格式化丢失时长。
     *
     * ⭐ **秒数补零 + 空格分隔**（用户要求"数字要被隔开，方便阅读"）：
     * 补零：3秒 → "03 秒"（与 12秒 宽度一致，数字跳动时不抖动）
     * 分隔：「3 分 02 秒」比「3分钟2秒」更易读
     */
    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        if (totalSec < 60) return String.format("%02d 秒", totalSec)
        val min = totalSec / 60
        val sec = totalSec % 60
        if (min < 60) {
            return if (sec == 0L) "$min 分钟" else String.format("%d 分 %02d 秒", min, sec)
        }
        val hour = min / 60
        val remainMin = min % 60
        return "$hour 小时 $remainMin 分"
    }

    
    /**
     * 追加一个速度采样点，重绘曲线并更新水印。
     *
     * ⚠️ 存的是显示值（已阈值归零 + 取整）而非 GPS 原始值——
     * 否则曲线会在 0~4km/h 出现锯齿，而水印恒显 0，两者对不上。
     *
     * ⚠️⚠️ **没有速度信号时不塞 0，而是清空整个队列**：
     * `TrendChartView.drawSeries()` 只在 `data.size < 2` 时跳过绘制，
     * 一旦队列里有 ≥2 个 0，就会画出**一条贴底的横线**（用户反馈"没数值却画一根线"）。
     * 功率/电流/压差不出现这问题，是因为断连时走 `redrawWithoutSampling()` 不塞点。
     */
    private fun pushSpeedSample(shown: Int) {
        val b = _binding ?: return
        val q = tracker.speedPoints
        val historyFixed = curveMode == CurveMode.HISTORY

        // ⭐ 没有速度信号 → 清空曲线，不画
        if (!hasSpeedSignal) {
            if (q.isNotEmpty()) {
                q.clear()
                tracker.speedNow = 0f
                if (!historyFixed) b.chartSpeed.setSingleSeries(FloatArray(0))
                b.tvSpeedNow.text = getString(R.string.value_none)
            }
            return
        }

        // ⭐⭐ **0 值不再无脑入队**（用户二次反馈「速度卡片为什么会绘制一条 0 的线」）。
        //
        // 上一次的修法只挡住了「打开 APP 就一直静止」（队列全 0 → 不画），
        // 但「跑过之后停车」是另一条路：队列 [30,20,10] + shown=0 时
        // `allZero == false` → 0 被追加 → 之后每帧继续追加 → 贴底横线越长越长。
        //
        // 新规则（判据在 SpeedDisplay.shouldAppendSample，有单测锁住）：
        // - shown > 0 → 正常入队
        // - shown == 0 且尾部已经是 0 → 不入队（不延长横线）
        // - shown == 0 且尾部不是 0（含空队列）→ 入队**一个** 0，让曲线落到基线
        if (!io.github.supit.rideassist.util.SpeedDisplay
                .shouldAppendSample(shown, q.peekLast())
        ) {
            tracker.speedNow = 0f
            return
        }

        tracker.speedNow = shown.toFloat()
        if (q.size >= MAX_POINTS) q.removeFirst()
        q.addLast(shown.toFloat())
        // 历史模式曲线已定格，不再喂新数据（数据本身照常入队，供下次拍快照）
        if (!historyFixed) {
            b.chartSpeed.setSingleSeries(
                io.github.supit.rideassist.util.SeriesMath.tail(q, RECENT_WINDOW)
            )
        }
        b.tvSpeedNow.text = String.format("%.0f", tracker.speedNow)
    }

    /**
     * 当前是否拿到了**有效的速度信号**。
     *
     * ⭐ 这是「画曲线」的前提：没有真实速度数据就不画，
     * 避免出现一条无意义的贴底横线。
     */
    private var hasSpeedSignal = false

    /**
     * 速度配色：**超过 [SPEED_DANGER] 转为红色告警**，否则主题蓝。
     * 右上角 Max 跟随水印同色（用户要求"颜色和速度一样"），两处始终一致。
     */
    private fun renderSpeedColors(shown: Int) {
        val b = _binding ?: return
        val ctx = requireContext()
        val c = if (shown > SPEED_DANGER) {
            ContextCompat.getColor(ctx, R.color.state_discharging)
        } else {
            ContextCompat.getColor(ctx, R.color.primary)
        }
        b.tvSpeedNow.setTextColor(c)
        // ⚠️ 单位是独立 TextView，必须一并变色，
        // 否则超速时数字变红而单位还是蓝的，看起来像两个不同指标
        b.tvSpeedNowUnit.setTextColor(c)
        b.tvGpsMax.setTextColor(c)
    }

    // ==================== 电池数据与曲线 ====================

    /**
     * ⭐⭐⭐ **移动判定的唯一入口已上移到 [MoveGate]**。
     *
     * 原来这里有一份自己的三路判据实现，阈值常量也散落在本类的 companion 里。
     * 那是「判据分裂」的温床 —— 判据有两份就一定会有一份忘了改。
     * 现在全项目只有 [MoveGate.evaluate] 一处实现，诊断面板也读同一份。
     *
     * @return 完整判定结果（含命中的证据），诊断面板要用
     */
    private fun judgeMoving(d: io.github.supit.rideassist.core.protocol.model.BmsRealtimeData): MoveGate.Verdict =
        MoveGate.evaluate(d, speedFilter.realSpeed)

    /**
     * 便捷包装：只要布尔结论的地方用它。
     *
     * ⚠️ 诊断面板要显示「哪一路判定的」，那里必须用 [judgeMoving] 拿完整结果。
     */
    private fun isMovingNow(d: io.github.supit.rideassist.core.protocol.model.BmsRealtimeData): Boolean =
        judgeMoving(d).moving

    private fun observeBatteryData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.realtime.collect { d ->
                    // ⚠️⚠️ 断连时 Repository 会把 realtime 重置为全0 的
                    // BmsRealtimeData()。这里**绝不能**继续往队列塞0——
                    // StateFlow 每次重置都会 emit，180 个采样点很快就把
                    // 历史极值全部冲掉，用户看到「Max/Min 被莫名清空」。
                    //
                    // hasDeviceData=false 表示这帧没有真实设备数据（断连/未连接）。
                    // 此时只重绘、不采样，让曲线和极值保持在断连前的状态。
                    if (!d.hasDeviceData) {
                        redrawWithoutSampling()
                        return@collect
                    }
                    // ⚠️ 采样点存 Activity 作用域的 tracker —— 断连/切页都不清空
                    // ⭐ 三张卡的 Max 统一用会话峰值（peakRecords 同源，
                    // 随会话落盘恢复），滑动窗口滚动不再丢失早期峰值
                    renderCurve(
                        binding.chartPower, tracker.powerPoints,
                        binding.tvPowerNow, binding.tvPowerStat, d.power, UNIT_WATT,
                        trackerNow = { tracker.powerNow = it },
                        maxOverride = tracker.peakRecords[io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.CARD_POWER]?.value
                   ,
                        alwaysRecord = true
                    )
                    renderCurve(
                        binding.chartCurrent, tracker.currentPoints,
                        binding.tvCurrentNow, binding.tvCurrentStat, d.totalCurrent, UNIT_AMPERE,
                        decimals = currentDecimals(d.totalCurrent),
                        trackerNow = { tracker.currentNow = it },
                        maxOverride = tracker.peakRecords[io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.CARD_CURRENT]?.value,
                        alwaysRecord = true
                    )
                    renderCurve(
                        binding.chartDiff, tracker.diffPoints,
                        binding.tvDiffNow, binding.tvDiffStat, d.cellDiff, UNIT_MV,
                        trackerNow = { tracker.diffNow = it },
                        maxOverride = tracker.peakRecords[io.github.supit.rideassist.ui.realtime.SessionSpeedTracker.CARD_DIFF]?.value
                   ,
                        alwaysRecord = true
                    )
                    // ⭐ 电量快照写进Tracker，供骑行页算「预计可行驶里程」
                    // 用协议的「电池剩余容量 Ah」，电压取**标称总压**——
                    // 实时总压随负载抖动，会让停车时能量虚高。
                    tracker.updateBatterySnapshot(
                        remainingAh = d.remainingCapacity,
                        nominalVolt = d.nominalTotalVolt,
                    )
                    // ⭐ SOC 跨帧跟踪 → 实测有效容量 / 实测 SOH（纯派生，无需协议支持）
                    tracker.updateSocTrack(
                        soc = d.soc,
                        remainingAh = d.remainingCapacity,
                        ratedAh = d.ratedCapacity,
                    )
                    // ⭐ 充电 ETA 用：刷新「末次放电帧」时刻（≥1A 放电才算）
                    tracker.noteDischarge(d.totalCurrent)
                    // ⭐⭐ 速度峰值快照已上移到 YybmsApp 的应用级采集器，
                    //    与功率/电流/压差三项同层级：用 tracker.liveSpeedKmh 做速度证据
                    //    （前台本页写、退后台悬浮前台服务写）。以前只在这里比对，
                    //    切到导航后 Fragment STOPPED → 「最大速度 @ 时间」记录卡冻结。
                    // 峰值被应用级采集器刷新时，本页也要跟着重绘记录卡 + 压差历史
                    renderPeakSection()

                    // ⭐ 骑行统计：状态机由 YybmsApp 统一驱动，本页只做积分
                    val verdict = judgeMoving(d)
                    val moving = verdict.moving
                    tracker.accumulateRideStats(
                        // ⚠️⚠️ 必须传**真实速度**（低通后的浮点值），不是 `shown`——
                        // `shown` 做了「<5km/h 归零 + 取整」，用那种值积分里程
                        // 会**系统性低估**（爬坡/等红灯的真实速度被抹成 0）。
                        // Tracker 里用**逐段梯形积分**，不是「均速 × 时长」。
                        speedKmhReal = speedFilter.realSpeed ?: 0f,
                        powerW = d.power,
                        amps = verdict.absCurrent,
                        moving = moving,
                    )
                }
            }
        }
    }

    /**
     * 断连/无设备数据时：**只重绘已有队列，不追加任何采样点**。
     *
     * 这样 Max/Min 与曲线走势都能跨断连保留（用户要求：
     * 「除非退出 APP，否则不清空，包括断连」）。
     */
    private fun redrawWithoutSampling() {
        val b = _binding ?: return
        // ⭐ 历史模式下曲线已定格在快照上，断连重绘不许把它刷掉
        val historyFixed = curveMode == CurveMode.HISTORY
        // ⚠️ 同样要过「全零不画」这道闸：静止时队列里全是 0，
        // 无条件重绘会画出贴底横线（用户要求"没有活动就不要画曲线"）
        if (!historyFixed) {
            setSeriesIfActive(b.chartPower, tracker.powerPoints)
            setSeriesIfActive(b.chartCurrent, tracker.currentPoints)
            setSeriesIfActive(b.chartDiff, tracker.diffPoints)
        }
        val w = statWindow(historyFixed)
        applyStat(b.tvPowerStat, statOf(tracker.powerPoints, UNIT_WATT, window = w))
        applyStat(b.tvCurrentStat, statOf(tracker.currentPoints, UNIT_AMPERE, window = w))
        applyStat(b.tvDiffStat, statOf(tracker.diffPoints, UNIT_MV, window = w))
        // 大水印数值也保持上一次的真实值，不跳成 0
        b.tvPowerNow.text = String.format("%.0f", tracker.powerNow)
        // 电流同样遵循「负值 1 位小数」，否则充电时这里会显示成 -0
        val cur = tracker.currentNow
        b.tvCurrentNow.text = if (cur < 0f) String.format("%.1f", cur)
            else String.format("%.0f", cur)
        b.tvDiffNow.text = String.format("%.0f", tracker.diffNow)
    }

    /**
     * 只有序列里存在「非静止」数据时才绘制，否则传空序列让 drawSeries 跳过。
     *
     * 与 [renderCurve] 的全零判断保持一致，避免两条路径表现不同。
     */
    private fun setSeriesIfActive(
        chart: io.github.supit.rideassist.ui.widget.TrendChartView,
        q: ArrayDeque<Float>
    ) {
        // ⭐ 只取尾部窗口：队列现在是全会话保留，全量遍历+拷贝没必要，
        //    TrendChartView 自己还会按 6dp 步长再裁到可视容量
        val tail = io.github.supit.rideassist.util.SeriesMath.tail(q, RECENT_WINDOW)
        val active = tail.any { kotlin.math.abs(it) >= IDLE_EPSILON_MV }
        chart.setSingleSeries(if (active) tail else FloatArray(0))
    }

    /**
     * 渲染一条曲线。
     *
     * @param q 采样队列（存 Activity 作用域，跨断连保留）
     * @param nowView 当前值大字（叠加显示在卡片上）
     * @param statView 「最大/最小」小字
     * @param unit 仅用于 Max/Min 文案；水印单位是独立 TextView，这里不拼进去
     */
    private fun renderCurve(
        chart: io.github.supit.rideassist.ui.widget.TrendChartView,
        q: ArrayDeque<Float>,
        nowView: android.widget.TextView,
        statView: android.widget.TextView,
        now: Float,
        unit: String,
        decimals: Int = 0,
        trackerNow: (Float) -> Unit = {},
        maxOverride: Float? = null,
        alwaysRecord: Boolean = false,
    ) {
        // ⭐⭐ **未开始记录 → 一律不画**。
        //
        // ⭐ 四张卡统一读 [isRecording] 的锁存状态（由 YybmsApp 应用级采集器
        // 唯一驱动），**不是**瞬时电流：用户需求"启动记录后持续记录……而不是
        // 电流小于 1A 就不记录，而且把曲线隐藏了"。滑行/等红灯时电流会短暂
        // 掉到 1A 以下，若用瞬时判断曲线会整段消失；用锁存器则全程连续记录。
        //
        // ⚠️ 曾经电流卡单独传 `currentAmp` 去调 `updateRecordingState()`，
        // 误把「状态是否变化」当成「是否在记录」，导致电流曲线每帧被清空。
        // 状态机驱动点只能有一处，这里只读不写。
        val moving = isRecording()
        // ⭐ 历史模式下曲线已定格在快照上，只更新大字与统计，不许覆盖曲线
        val historyFixed = curveMode == CurveMode.HISTORY
        if (!moving) {
            // ⚠️ 即使不画曲线，也要把当前值写回 tracker ——
            // 否则切页回来走 redrawFromTracker 时读到的是初始 0，
            // 会出现「压差 0mV」这种明显错误（用户实测发现）。
            trackerNow(now)
            // ⭐ 电流卡（alwaysRecord）：0A / 充电负电流也是有效状态，
            //    始终入队 —— 否则右上角 Max/Min 在未骑行时永远空白
            if (alwaysRecord) {
                if (q.size >= MAX_POINTS) q.removeFirst()
                q.addLast(now)
            }
            // 保留队列不清空，一旦开动立刻能接着画
            if (!historyFixed) chart.setSingleSeries(FloatArray(0))
            nowView.text = if (decimals > 0) String.format("%.${decimals}f", now)
            else String.format("%.0f", now)
            applyStat(statView, statOf(q, unit, maxOverride, statWindow(historyFixed)))
            return
        }

        trackerNow(now)
        if (q.size >= MAX_POINTS) q.removeFirst()
        q.addLast(now)
        if (!historyFixed) {
            chart.setSingleSeries(
                io.github.supit.rideassist.util.SeriesMath.tail(q, RECENT_WINDOW)
            )
        }
        // 水印只写数值，单位由布局里独立的 TextView 承载（字号更小）
        nowView.text = if (decimals > 0) String.format("%.${decimals}f", now)
        else String.format("%.0f", now)
        applyStat(statView, statOf(q, unit, maxOverride, statWindow(historyFixed)))
    }

    /**
     * 电流小数位数：**负值（充电）用 1 位小数**，否则取整。
     *
     * 需求原文："电流为负数的时候，显示1位小数点"
     * 充电电流通常只有零点几安，取整会全部显示成「0」，看不出在不在充。
     *
     * ⭐ 不需要「持续 5 秒」防抖 —— 之前那个计时器是为了躲dv jitter，
     * 但负值本身已经足够明确，看到负数就是充电，直接给小数位即可。
     *
     * @return 1 = 用 1 位小数；0 = 用整数
     */
    private fun currentDecimals(raw: Float): Int = if (raw < 0f) 1 else 0

    /**
     * 「最大 x单位  最小 x单位」
     *
     * ⚠️ 无数据时返回**空串**而非 `--`（用户要求"不要显示那个 --"）。
     * 空串时 [renderCurve]/[redrawWithoutSampling] 会把 statView设为 GONE，
     * 不留空位。同理速度卡的 `Max` 也一样。
     */
    private fun statOf(
        q: ArrayDeque<Float>,
        unit: String,
        maxOverride: Float? = null,
        window: Int = SessionSpeedTracker.RECENT_WINDOW,
    ): String {
        if (q.isEmpty()) return ""     // 无数据 → 空串，不是 "--"
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        // ⭐ 从尾部倒着数 `window` 个点：实时模式只看近期（反映当前状态），
        //    历史模式传 Int.MAX_VALUE 即全会话最低值（用户 2026-11-08 定案）。
        //    用 descendingIterator 而不是 toFloatArray() —— 队列现在是全会话保留，
        //    全量拷贝几万个点纯属浪费。
        var counted = 0
        val it = q.descendingIterator()
        while (it.hasNext() && counted < window) {
            val v = it.next()
            counted++
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        if (counted == 0) return ""
        // ⭐ 统一口径（用户要求）：三张卡的 Max 都用会话峰值
        // （peakRecords[X].value，随会话落盘恢复）—— 窗口只有最近
        // RECENT_WINDOW 点，骑行久了早期峰值会被滚动丢弃，所以要用会话峰值兜底
        if (maxOverride != null && maxOverride > mx) mx = maxOverride
        // ⚠️ 三个空格分隔：只有 1 个时读起来像"5mV最小"
        return String.format("Max %d%s   Min %d%s", mx.toInt(), unit, mn.toInt(), unit)
    }

    /**
     * 设置速度卡右上角的「Max N」。
     *
     * ⚠️ **没有任何速度数据时整块隐藏**（用户要求"如果没有速度的时候不显示 Max 0"）。
     * 之前无条件显示，导致没定位信号时右上角孤零零挂着「Max 0」，
     * 看起来像"最高速度是 0"，容易误读。
     */
    private fun applySpeedMax() {
        val b = _binding ?: return
        // ⭐⭐ 只看 maxSpeed，**不再要求 speedPoints 非空**：
        //    曲线采样仍留在本页（退后台不采样），但会话极速由悬浮前台服务
        //    照样在记。若继续依赖曲线点，全程在导航里骑完再回来看会出现
        //    「明明有极速，速度卡 Max 却是空的」。
        if (tracker.maxSpeed <= 0) {
            b.tvGpsMax.text = ""
            // ⚠️⚠️ **必须用 INVISIBLE，不能用 GONE**。
            // 标题行是 wrap_content，高度由最高的子控件决定；
            // `tv_gps_max` 的 20sp 比「速度」标题的 15sp 高，
            // 一旦 GONE，标题行就塌陷到 15sp —— 表现为「速度卡片顶部留白消失」，
            // 且因为 applySpeedMax 只在 renderSpeed/redrawFromTracker 里调用，
            // 切曲线时不会重跑，**留白再也回不来**（用户实测确认）。
            // INVISIBLE 保留占位高度，只是不绘制，看起来"文字消失"但布局不变。
            b.tvGpsMax.visibility = View.INVISIBLE
            return
        }
        b.tvGpsMax.text = getString(R.string.gps_max_fmt, tracker.maxSpeed)
        b.tvGpsMax.visibility = View.VISIBLE
    }

    /**
     * 设置「Max/Min」小字：无内容时隐藏控件，避免留下"--"或空占位。
     *
     * ⚠️ 用 **INVISIBLE** 而非 GONE —— 与 [applySpeedMax] 同一理由：
     * 控件一旦 GONE，所在行的高度就按剩余子控件重算，布局会跳动。
     * INVISIBLE 保留占位，视觉上「没有文字」但布局稳定。
     */
    private fun applyStat(view: android.widget.TextView, text: String) {
        if (text.isBlank()) {
            view.text = ""
            view.visibility = View.INVISIBLE
        } else {
            view.text = text
            view.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopGpsUpdates()
        stopRealtimeClock()
        // ⚠️ 必须释放加速度计，否则传感器监听持有已销毁的 View 树 → 泄漏
        releaseLossEstimator()
        // ⚠️ 必须取消待执行的单击，否则它会在 binding 置空后再跑，触发空指针
        _binding = null
    }

    companion object {
        /** 采样容量统一由 Tracker 定义，避免两处数字不一致 */
        private const val MAX_POINTS = SessionSpeedTracker.MAX_POINTS

        /** 实时曲线的取数窗口 / 统计窗口，同样只由 Tracker 定义一份 */
        private const val RECENT_WINDOW = SessionSpeedTracker.RECENT_WINDOW

        /** 卡片数量：速度 / 功率 / 电流 / 压差（索引与 [CurveMode] 及快照数组对齐） */
        private const val CARD_COUNT = 4

        /**
         * 「静止」判定阈值，单位是 **mV**（压差量纲）。
         *
         * ⚠️ 压差不能用 0.05 判（实机教训）：单体压差常态就是 3~8mV
         * （同批次电芯的内阻差异），用 0.05mV 判等于"永不静止"，
         * 静止时压差卡照样画线、照样显示 Max/Min。
         * 实测静止时压差在 4~6mV 波动。
         *
         * 所以阈值取 **0.5mV**：能盖住实测的静止波动，
         * 又远低于"真的有压差变化"（通常 >5mV）。
         * 功率/电流用同一个阈值毫无影响（0.5W/0.5A 已是很大的活动量）。
         */
        private const val IDLE_EPSILON_MV = 0.5f

        /** GPS 漂移排查日志 tag（`adb logcat -s RideAssistGps`） */
        private const val TAG_GPS = "RideAssistGps"

        /**
         * 速度帧的**新鲜度**上限。
         *
         * `startGpsUpdates()` 会先用 `getLastKnownLocation()` 补一个立即值，
         * 那可能是几十分钟前骑行途中的缓存位置 —— 速度是当时的值，
         * 直接采信就会「打开 APP 瞬间凭空出现一个速度点」。
         */
        private const val GPS_FRESH_MS = 3_000L

        /** 精度差于该值（米）的速度一律不采信 */
        private const val GPS_MAX_ACCURACY_M = 50f

        /**
         * ⚠️ 三个移动判据阈值（电流 1A / 功率 150W / 速度 5km/h）已**上移**到
         * [MoveGate]。原因是历史上这里和 `isMovingNow()` 各存一份，
         * 判据有两份就一定会有一份忘改 —— 这正是本项目两次严重 bug 的共同根因。
         * 现在全部阈值与判据只有 [MoveGate] 一处。
         */

        /** 速度卡的索引（0=速度 1=功率 2=电流 3=压差） */
        private const val CARD_SPEED = 0

        /** 功率卡索引（顺序：速度/功率/电流/压差） */
        private const val CARD_POWER = 1

        /** 电流卡索引（顺序：速度/功率/电流/压差） */
        private const val CARD_CURRENT = 2

        /** 压差卡索引（顺序：速度/功率/电流/压差） */
        private const val CARD_DIFF = 3


        /** 时钟刷新间隔 1 秒（逐秒变化，避免整点跳变看起来像卡住） */
        private const val CLOCK_INTERVAL_MS = 1_000L

        /** 低于该值（km/h）视为静止，直接显示 0。
         * 用户要求 5km/h 以下一律显示 0——GPS 静态漂移常在 2~4km/h，
         * 阈值太小会导致停着车也一直跳数字。 */
        /** 压差记录用的时刻格式化（HH:mm） */
        private val CLOCK_FORMAT =
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

        /** GPS 丢失不足这么多秒**不显示**提示卡（GPS 抖动很常见） */
        private const val GPS_LOST_SHOW_THRESHOLD_MS = 2000L

        /**
         * GPS 需**连续这么多次刷新都拿到速度**，才真正清零丢失时长。
         *
         * ⭐ 用户要求（2026-10-10）："连续 3 次刷新，都获取到 GPS 速度，
         * 才退出 GPS 丢失状态，不然会错误退出。"
         *
         * ⭐ 用**次数**而不是**时长**判定：GPS 刷新频率不固定
         * （系统调度、后台、省电策略都会影响间隔），
         * 用固定时长会出现「3 次刷新间隔太长 → 等了几秒才确认」的迟滞。
         * 数次数与刷新频率解耦，行为可预期。
         */
        private const val GPS_RECOVER_CONFIRM_COUNT = 3


        /** 超过该速度（km/h）主数值与 Max 都转红色告警 */
        private const val SPEED_DANGER = 50
        // ===== 单位常量（与 strings.xml 保持一致）=====
        // ⚠️ 这些单位**只用于 Max/Min 统计文案**。
        // 水印上的单位是独立 TextView（字号更小），由布局静态提供，别拼进数值里。
        private const val SPEED_UNIT = "km/h"
        private const val UNIT_WATT = "W"
        private const val UNIT_AMPERE = "A"
        private const val UNIT_MV = "mV"
    }
}
package io.github.supit.rideassist.ui.widget

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import io.github.supit.rideassist.R

/**
 * ⭐ **统一的轻提示**（用户 2026-11-10）。
 *
 * 需求原文：
 * > 「像已取消自动连接这种提示，背景要有点透明度，要有淡入淡出的效果，
 * > 查看有没有其他的类似的提示」
 * > 「而且要居中」
 *
 * 所以 APP 里**所有**轻提示（原来是 5 处 Snackbar + 15 处 Toast）都走这里，
 * 统一成同一种外观与位置：
 *
 * - **水平居中、垂直靠底**：左右居中，浮在屏幕底部往上一点 ——
 *   ⚠️ **不是垂直居中**（用户 2026-11-10 纠正：「水平居中，不是让你垂直居中」）：
 *   垂直居中会把提示怼在屏幕正中，正好挡住正在看的内容。
 * - **位置**：浮在**底部标签页（BottomNavigationView）按钮上方**，不贴屏幕底边。
 * - **背景**：深灰 `#1F1F1F` + **80% 不透明度** + 14dp 圆角，
 *   底下页面能隐约透出来，比实心黑条轻；
 * - **三段式动画**（用户 2026-11-10：「这个提示要从底部位移上来，
 *   带点透明度变化，停够时间再自己退走」）：
 *   1. 从下方 56dp **移上来** + 淡入（360ms，减速插值）
 *   2. **停够** 2.2s / 3.8s（从动画结束开始计时）
 *   3. 滑回下方 + 淡出（360ms，加速插值）后移除
 *
 * ## 为什么不用 Snackbar
 * Snackbar 只能贴在 CoordinatorLayout 底部，想居中只能靠 `translationY` 硬凑
 * （页面高度 / 状态栏 / 键盘弹出都会让它错位）。而且它必须挂在有 View 宿主的
 * 页面上，`BroadcastReceiver` 里根本用不了。
 *
 * ## 为什么不用系统 Toast
 * Android 11 起**文字 Toast 的外观由系统接管**，`setView()` 会被忽略，
 * 样式改不了；旧系统（API < 30）自绘 view 的话，圆角 / 透明度能改，
 * 但淡入淡出动画又由系统接管，做不到完全统一。
 *
 * ## 为什么不用 Dialog 浮层
 * Dialog 是浮动窗口，会被**系统栏区域裁剪**：`Gravity.BOTTOM` + `lp.y` 偏移量
 * 算不准，实测提示还是贴着屏幕底边（试了两版都无效）。
 * 所以改成把 TextView **直接加到 Activity 的 `android.R.id.content`（FrameLayout）**，
 * 位置完全由布局系统保证 —— `bottomMargin = 底部导航栏高度 + 12dp`。
 *
 * 取不到 Activity（例如 `BroadcastReceiver`）时退回系统 Toast ——
 * 那两处都是后台更新提示，交给系统渲染更稳。
 */
object Feedback {

    /** 底色：深灰 0x1F1F1F */
    private const val BG_COLOR = 0xFF1F1F1F.toInt()

    /** 背景不透明度：0xCC = 204/255 = **80%**（用户 2026-11-10：「80% 透明度试试」） */
    private const val BG_ALPHA = 0xCC

    private const val CORNER_DP = 14
    private const val PAD_H_DP = 22
    private const val PAD_V_DP = 14
    private const val TEXT_SP = 15f

    /**
     * ⭐ 三段式动画节奏（用户 2026-11-10：「这个提示要从底部位移上来，
     * 带点透明度变化，停够时间再自己退走」）：
     *
     * 1. **移上来**：[RISE_MS] 内从下方 [SLIDE_UP_DP] 处滑到定位点，同时透明度 0 → 1
     * 2. **停够**：[HOLD_SHORT_MS] / [HOLD_LONG_MS]（从动画结束开始计时）
     * 3. **退走**：[EXIT_MS] 内滑回下方 + 透明度 1 → 0，然后移除 View
     */
    private const val RISE_MS = 360L
    private const val EXIT_MS = 360L
    private const val HOLD_SHORT_MS = 2200L
    private const val HOLD_LONG_MS = 3800L

    /**
     * 入场时的上滑距离：提示从最终定位点**下方这么多 dp** 处冒上来，
     * 视觉上就是「从底部位移上来」，而不是凭空淡入。
     */
    private const val SLIDE_UP_DP = 56

    /** 提示与底部标签页（BottomNavigationView）之间的间距 */
    private const val BOTTOM_NAV_GAP_DP = 12

    /**
     * 找不到底部标签页时（独立设置页等）的兜底离底距离。
     * 取 96dp ≈ 标准标签页栏高度，保证**任何情况下都不会贴着屏幕底边**。
     */
    private const val FALLBACK_BOTTOM_DP = 96

    /** 标记当前正在显示的提示 View（用 tag 找，避免静态持有 View 引用） */
    private const val TAG_FEEDBACK = "rideassist_feedback"

    /** 当前正在显示的提示（用 tag 找，不静态持有 View，避免泄漏 Activity） */

    /**
     * 显示一条居中轻提示。
     *
     * @param context 任意 Context（Fragment / Activity / Receiver 都行）
     * @param text    文案
     * @param long    是否长驻（对应系统 Toast 的 LENGTH_LONG）
     */
    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        val activity = context.findActivity()
        if (activity == null || activity.isFinishing ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)
        ) {
            // 拿不到 Activity（BroadcastReceiver / 后台）：交给系统 Toast，
            // 反正系统那套本来就是居中 + 半透明圆角 + 淡入淡出。
            Toast.makeText(
                context.applicationContext,
                text,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            ).show()
            return
        }
        showCentered(activity, text, if (long) HOLD_LONG_MS else HOLD_SHORT_MS)
    }

    /** 从 View 拿宿主（页面里用起来最顺手） */
    fun show(anchor: View, text: CharSequence, long: Boolean = false) {
        show(anchor.context, text, long)
    }

    // ==================== 内部实现 ====================

    private fun showCentered(activity: Activity, text: CharSequence, holdMs: Long) {
        // ⚠️ **不要用 Dialog 浮层**：浮动窗口会被系统栏区域裁剪，
        //    gravity + y 的偏移量算不准（实测提示还是贴着底）。
        //    改成直接把 View 加到 Activity 的 content 容器里 ——
        //    位置由布局系统保证：bottomMargin = 底部导航栏高度 + 间距，
        //    稳稳落在**底部标签页按钮上方**。
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: run {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
            return
        }

        // 上一条还没消失就先收掉，避免叠成一摞（用 tag 找，不持有静态 View 引用）
        root.findViewWithTag<View>(TAG_FEEDBACK)?.let { root.removeView(it) }

        val tv = TextView(activity).apply {
            this.text = text
            tag = TAG_FEEDBACK
            setTextColor(Color.WHITE)
            textSize = TEXT_SP
            gravity = Gravity.CENTER
            setPadding(
                activity.dp(PAD_H_DP), activity.dp(PAD_V_DP),
                activity.dp(PAD_H_DP), activity.dp(PAD_V_DP),
            )
            background = roundedBackground(activity)
            // ⚠️ 入场初始态：位于最终定位点**下方** SLIDE_UP_DP，透明度 0
            //    → 动画把它「移上来」的同时淡入（用户 2026-11-10：
            //    「这个提示要从底部位移上来，带点透明度变化」）
            translationY = activity.dp(SLIDE_UP_DP).toFloat()
            alpha = 0f
        }

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        )
        // ⭐ 抬到**底部标签页（BottomNavigationView）上方**（用户 2026-11-10：
        //    「起码得在底部标签页按钮上」），别贴着屏幕底边。
        lp.bottomMargin = bottomInsetPx(activity)
        root.addView(tv, lp)

        // ⭐ ① 从底部**移上来** + 淡入（减速插值，滑到位时更利落）
        // ⚠️ 同一个 View 的 ViewPropertyAnimator 只能跑一个动画，
        //    所以「停够时间」的延迟必须挂在**这次动画的 withEndAction** 上，
        //    不能再单独 tv.animate().start() 一次（那会把上升动画顶掉）。
        tv.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(RISE_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // ⭐ ② 停够时间 → ③ 滑回去 + 淡出 → 移除
                //    计时从**入场动画结束**开始算，保证「停够时间」。
                tv.postDelayed({
                    if (tv.parent == null) return@postDelayed
                    tv.animate()
                        .translationY(activity.dp(SLIDE_UP_DP).toFloat())
                        .alpha(0f)
                        .setDuration(EXIT_MS)
                        .setInterpolator(AccelerateInterpolator())
                        .withEndAction {
                            if (tv.parent != null) (tv.parent as? ViewGroup)?.removeView(tv)
                        }
                        .start()
                }, holdMs)
            }
            .start()
    }

    /**
     * ⭐ 提示浮在**底部标签页按钮上方**（用户 2026-11-10：
     * 「起码得在底部标签页按钮上」），离底距离 = 底部导航栏高度 + 间距。
     *
     * 独立 Activity（连接设置页 / 胎压设置页）没有底部导航栏 → 用兜底值，
     * 提示贴底一点点，不至于浮在屏幕正中。
     */
    private fun bottomInsetPx(activity: Activity): Int {
        val content = activity.findViewById<View>(android.R.id.content)
        val nav = content?.findViewById<View>(R.id.bottom_nav)
        val navHeight = nav?.height ?: 0
        val base = if (navHeight > 0) navHeight else activity.dp(FALLBACK_BOTTOM_DP)
        return base + activity.dp(BOTTOM_NAV_GAP_DP)
    }

    /** 圆角 + 半透明底色 */
    private fun roundedBackground(context: Context, cornerDp: Int = CORNER_DP): GradientDrawable =
        GradientDrawable().apply {            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(cornerDp).toFloat()
            setColor(
                Color.argb(
                    BG_ALPHA,
                    Color.red(BG_COLOR),
                    Color.green(BG_COLOR),
                    Color.blue(BG_COLOR),
                )
            )
        }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

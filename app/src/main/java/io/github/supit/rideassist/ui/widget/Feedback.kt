package io.github.supit.rideassist.ui.widget

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
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
 * - **水平居中、垂直靠底**：左右居中，浮在屏幕底部往上一点（离底 88dp）——
 *   ⚠️ **不是垂直居中**（用户 2026-11-10 纠正：「水平居中，不是让你垂直居中」）：
 *   垂直居中会把提示怼在屏幕正中，正好挡住正在看的内容。
 * - **背景**：深灰 `#1F1F1F` + **80% 不透明度** + 14dp 圆角，
 *   底下页面能隐约透出来，比实心黑条轻；
 * - **淡入淡出**：进 300ms 淡入，停一会儿，出 350ms 淡出后销毁（用户嫌快，放慢了）。
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
 * 所以这里用 **Dialog 自绘浮层**：位置、背景、动画全部自己说了算。
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

    /** ⭐ 淡入淡出放慢（用户 2026-11-10：「速度放慢点」） */
    private const val FADE_IN_MS = 300L
    private const val FADE_OUT_MS = 350L
    private const val HOLD_SHORT_MS = 2000L
    private const val HOLD_LONG_MS = 3500L

    /** 提示与底部标签页（BottomNavigationView）之间的间距 */
    private const val BOTTOM_NAV_GAP_DP = 12

    /** 找不到底部标签页时（独立设置页等）的兜底离底距离 */
    private const val FALLBACK_BOTTOM_DP = 32

    /** 当前正在显示的浮层：新的提示来了先把旧的收掉，避免叠成一摞 */
    private var currentDialog: Dialog? = null

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
        // 上一个还没消失就先收掉，避免提示叠成一摞
        currentDialog?.dismiss()

        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = TEXT_SP
            gravity = Gravity.CENTER
            setPadding(
                activity.dp(PAD_H_DP), activity.dp(PAD_V_DP),
                activity.dp(PAD_H_DP), activity.dp(PAD_V_DP),
            )
            background = roundedBackground(activity)
            // ⚠️ 淡入从 0 开始；出淡出结束后才 dismiss，否则会「闪一下再消失」
            alpha = 0f
        }

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(tv)
        dialog.setCanceledOnTouchOutside(false)
        // ⚠️ 不吃触摸、不抢焦点：提示只是提示，不能挡住下面的操作
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            // ⭐ **水平居中 + 垂直靠底**（用户 2026-11-10 纠正：
            //    「水平居中，不是让你垂直居中」）。
            //    垂直居中会把提示怼在屏幕正中，挡住正在看的内容，很丑。
            setGravity(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            // ⚠️ **不要压暗整屏**（用户 2026-11-10：「不要影响整个画面啊，变暗干啥」）：
            //    Dialog 的默认主题带 FLAG_DIM_BEHIND，会把整个画面调暗。
            //    这里把 dim 彻底关掉，只有提示自己浮着。
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            // 不要系统默认的窗口动画，淡入淡出我们自己演
            setWindowAnimations(0)
        }

        dialog.setOnDismissListener {
            if (currentDialog === dialog) currentDialog = null
        }

        try {
            dialog.show()
        } catch (_: Exception) {
            // Activity 正在销毁时 show 会抛 WindowManager$BadTokenException
            return
        }
        currentDialog = dialog

        // ⚠️ show() 之后 window 才 attach，此时改 gravity / y 偏移才真正生效
        dialog.window?.let { w ->
            val lp = w.attributes
            lp.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            // y 为负 = 离底边往上抬
            // ⭐ 抬到**底部标签页（BottomNavigationView）上方**（用户 2026-11-10：
            //    「起码得在底部标签页按钮上」），别贴着屏幕底边。
            lp.y = -bottomInsetPx(activity)
            w.attributes = lp
        }

        // ⭐ 淡入
        tv.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        // ⭐ 停留 → 淡出 → 销毁
        tv.postDelayed({
            if (!dialog.isShowing) return@postDelayed
            tv.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .withEndAction { if (dialog.isShowing) dialog.dismiss() }
                .start()
        }, holdMs)
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

package io.github.supit.rideassist.ui.overlay

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.supit.rideassist.R
import io.github.supit.rideassist.YybmsAppHolder
import io.github.supit.rideassist.data.prefs.AppPrefs
import io.github.supit.rideassist.databinding.FragmentOverlayBinding
import io.github.supit.rideassist.ui.overlay.OverlayItems.saveOrder
import io.github.supit.rideassist.ui.widget.Feedback

/**
 * 悬浮显示设置页 —— 顶部「启动悬浮信息」+「打开导航软件」，
 * 下方悬浮项目列表（开关 + 字号），**长按拖动排序**（ItemTouchHelper），
 * 顺序由 prefs.overlayOrder 持久化。
 */
class OverlayFragment : Fragment() {

    private var _binding: FragmentOverlayBinding? = null
    private val b get() = _binding!!
    private val prefs by lazy { YybmsAppHolder.from(requireContext()).prefs }

    private lateinit var adapter: OverlayItemsAdapter
    private lateinit var quickAdapter: QuickNavAdapter
    private lateinit var quickTouchHelper: androidx.recyclerview.widget.ItemTouchHelper

    /** ⭐ 颜色按钮的默认着色，选「自动」时原样还原（材质按钮的 tint 由主题决定） */
    private var defaultBtnTint: ColorStateList? = null
    private var defaultBtnTextColor: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOverlayBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnOverlayToggle.setOnClickListener { toggleOverlay() }

        // ⭐ 悬浮字体颜色：一个按钮，点击弹颜色列表，选完按钮本身染成所选颜色。
        //    ⚠️ 颜色名与 ARGB 在函数内同一个列表里构造，不用两份数组，不会错位。
        defaultBtnTint = b.btnOverlayTextColor.backgroundTintList
            ?: ColorStateList.valueOf(0xFF6750A4.toInt())
        defaultBtnTextColor = b.btnOverlayTextColor.currentTextColor
        renderTextColorButton(prefs.overlayTextColor)
        b.btnOverlayTextColor.setOnClickListener { showTextColorPicker() }

        // ⭐ 打开导航软件：首次点击弹选择器（只设定不打开），之后直接打开；长按重选
        b.btnNavApp.setOnClickListener {
            if (prefs.overlayJumpOnNav) {
                (activity as? io.github.supit.rideassist.ui.main.MainActivity)?.gotoRealtimeSpeedCard()
            }
            TpmsOverlayService.expectNav = true   // ⭐ 切到导航：此期间长按不隐藏
            ensureOverlayRunning()
            val pkg = prefs.navAppPkg
            if (pkg.isBlank()) showAppPicker() else launchApp(pkg)
        }
        b.btnNavApp.setOnLongClickListener {
            showAppPicker()
            true
        }

        // ⭐ 快捷导航网格（2 列，长按拖动排序）+ 底部「+快捷导航」新增
        quickTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
            ) {
                override fun onMove(
                    rv: RecyclerView,
                    vh: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean = quickAdapter.onMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)

                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun isLongPressDragEnabled() = false   // 拖动只从左侧手柄触发
            })
        quickAdapter = QuickNavAdapter(prefs,
            { },
            {
                if (prefs.overlayJumpOnNav) {
                    (activity as? io.github.supit.rideassist.ui.main.MainActivity)?.gotoRealtimeSpeedCard()
                }
                ensureOverlayRunning()
            },
            { dest, lat, lon -> showNavModeDialog(dest, lat, lon) },
            quickTouchHelper)
        b.recyclerQuickNav.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        b.recyclerQuickNav.adapter = quickAdapter
        quickTouchHelper.attachToRecyclerView(b.recyclerQuickNav)
        quickAdapter.editCallback = { idx -> editQuickNav(idx) }
        quickAdapter.reload()
        b.btnAddQuickNav.setOnClickListener { editQuickNav(-1) }

        // ⭐⭐ 悬浮项目排序/开关/字号变化一律走服务内的 orderListener 重建行，
        //    **不再 stop/start 服务**（重启会重新 addView，把悬浮框位置打回默认点）。
        adapter = OverlayItemsAdapter(prefs)
        // ⭐ 悬浮项目开关改成「一行一个」（原来 2 列挤一行），仍由
        //    ItemTouchHelper 长按拖动排序，顺序保存口径不变。
        b.recyclerOverlayItems.layoutManager = LinearLayoutManager(requireContext())

        // ⭐ 背景透明度滑条：拖动实时预览（bgAlphaListener 直达已运行的服务）
        // ⭐ 初始值与存储同步（否则滑条显示 XML 默认 70，与实际不同步）
        b.sliderBgAlpha.value = prefs.overlayBgAlpha.coerceIn(0, 100).toFloat()
        b.tvBgAlpha.text = "${prefs.overlayBgAlpha} %"

        // ⭐ 导航后自动悬浮显示开关
        b.switchAutoOnNav.isChecked = prefs.overlayAutoOnNav
        b.switchAutoOnNav.setOnCheckedChangeListener { _, checked ->
            prefs.overlayAutoOnNav = checked
        }
        // ⭐ 导航后跳转页面开关
        b.switchJumpOnNav.isChecked = prefs.overlayJumpOnNav
        b.switchJumpOnNav.setOnCheckedChangeListener { _, checked ->
            prefs.overlayJumpOnNav = checked
        }

        // ⭐ 悬浮项目默认**展开**（用户要求「不要隐藏」），点击表头仍可收起
        b.recyclerOverlayItems.visibility = View.VISIBLE
        b.tvArrow.text = "▾"
        b.headerItems.setOnClickListener {
            val show = b.recyclerOverlayItems.visibility == View.GONE
            b.recyclerOverlayItems.visibility = if (show) View.VISIBLE else View.GONE
            b.tvArrow.text = if (show) "▾" else "▸"
        }
        b.sliderBgAlpha.addOnChangeListener { _, v, _ ->
            val a = v.toInt()
            prefs.overlayBgAlpha = a
            b.tvBgAlpha.text = "$a %"
            io.github.supit.rideassist.ui.overlay.TpmsOverlayService.bgAlphaListener?.invoke(a)
        }
        b.recyclerOverlayItems.adapter = adapter
        ItemTouchHelper(ItemTouchCallback(adapter)).attachToRecyclerView(b.recyclerOverlayItems)
    }

    /** 拖动排序回调（ItemTouchHelper 的 onMove 调用） */
    private inner class ItemTouchCallback(
        private val adapter: OverlayItemsAdapter,
    ) : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        override fun onMove(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean = adapter.onMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun isLongPressDragEnabled() = true

        override fun getDragDirs(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
        ): Int = if (vh.bindingAdapterPosition == 0) 0   // 背景行固定顶部不可拖
        else super.getDragDirs(rv, vh)
    }

    override fun onResume() {
        super.onResume()
        refreshToggleText()
    }

    /** 顶部按钮：启动/关闭悬浮（未授权悬浮窗时跳系统授权页） */
    private fun toggleOverlay() {
        val ctx = requireContext()
        if (!android.provider.Settings.canDrawOverlays(ctx)) {
            Feedback.show(ctx, getString(R.string.overlay_perm_hint), long = true)
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}")
                )
            )
            return
        }
        val svc = android.content.Intent(ctx, TpmsOverlayService::class.java)
        if (TpmsOverlayService.isRunning) {
            ctx.stopService(svc)
        } else {
            ctx.startService(svc)
        }
        // startService 异步：稍等服务 onCreate 跑完再刷按钮文字
        b.btnOverlayToggle.postDelayed({ if (_binding != null) refreshToggleText() }, 400)
    }

    /**
     * ⭐ 任何方式切到导航软件前确保悬浮信息已开启（用户要求）。
     * 无悬浮窗权限时静默跳过——不打断导航流程。
     */
    private fun ensureOverlayRunning() {
        // ⭐ 用户可用开关关闭「导航后自动悬浮显示」
        if (!prefs.overlayAutoOnNav) return
        val ctx = requireContext()
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        if (!TpmsOverlayService.isRunning) {
            ctx.startService(android.content.Intent(ctx, TpmsOverlayService::class.java))
        }
        b.btnOverlayToggle.postDelayed({ if (_binding != null) refreshToggleText() }, 400)
    }

    private fun refreshToggleText() {
        b.btnOverlayToggle.text = getString(
            if (TpmsOverlayService.isRunning) R.string.overlay_stop else R.string.overlay_start
        )
    }

    /**
     * ⭐ 悬浮字体颜色选项。**0 = 自动**（沿用悬浮服务按实际时间黑白切换）。
     *
     * 颜色名与 ARGB 放在同一个列表里，避免「名字数组」和「值数组」两份
     * 各自维护、日后增删一项时错位（本项目「判据/常量只留一份」的老规矩）。
     * ⚠️ 函数内构造而不是字段初始化 —— Fragment 字段求值在构造期，
     *    那时还没有 Context，`getString()` 会直接抛 not attached。
     */
    private fun colorChoices(): List<Pair<String, Int>> = listOf(
        getString(R.string.overlay_text_color_auto) to 0,
        getString(R.string.overlay_text_color_white) to 0xFFFFFFFF.toInt(),
        getString(R.string.overlay_text_color_black) to 0xFF000000.toInt(),
        getString(R.string.overlay_text_color_red) to 0xFFFF3B30.toInt(),
        getString(R.string.overlay_text_color_orange) to 0xFFFF9500.toInt(),
        getString(R.string.overlay_text_color_yellow) to 0xFFFFD60A.toInt(),
        getString(R.string.overlay_text_color_green) to 0xFF34C759.toInt(),
        getString(R.string.overlay_text_color_cyan) to 0xFF00C7BE.toInt(),
        getString(R.string.overlay_text_color_blue) to 0xFF0A84FF.toInt(),
        getString(R.string.overlay_text_color_purple) to 0xFFAF52DE.toInt(),
    )

    /** 点击颜色按钮 → 选色 → 存偏好 → 染按钮 → 通知已运行的悬浮窗立刻换色 */
    private fun showTextColorPicker() {
        val ctx = requireContext()
        val choices = colorChoices()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.overlay_text_color_title))
            .setItems(choices.map { it.first }.toTypedArray()) { _, which ->
                val color = choices[which].second
                prefs.overlayTextColor = color
                renderTextColorButton(color)
                // ⭐ 不重启服务：listener 直达已运行的悬浮窗，
                //    悬浮框位置 / 临时隐藏状态 / GPS 订阅都不会丢。
                TpmsOverlayService.textColorListener?.invoke(color)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 把按钮渲染成当前选中色；亮底黑字、暗底白字，保证颜色名始终可读 */
    private fun renderTextColorButton(color: Int) {
        val btn = b.btnOverlayTextColor
        if (color == 0) {
            btn.backgroundTintList = defaultBtnTint
            btn.setTextColor(defaultBtnTextColor)
            btn.text = getString(R.string.overlay_text_color_auto)
            return
        }
        btn.backgroundTintList = ColorStateList.valueOf(color)
        val lum = Color.red(color) * 0.299 +
            Color.green(color) * 0.587 + Color.blue(color) * 0.114
        btn.setTextColor(if (lum > 150) Color.BLACK else Color.WHITE)
        btn.text = colorChoices().firstOrNull { it.second == color }?.first
            ?: getString(R.string.overlay_text_color_auto)
    }

    /** 弹出应用选择器（排除本 APP 与系统应用），选中后记忆包名（只设定不打开） */
    private fun showAppPicker() {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != ctx.packageName }
            .sortedBy { it.first }
        val labels = apps.map { it.first }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.overlay_picker_title))
            .setItems(labels) { _, which ->
                // ⭐ 首次选择只设定目标，不打开（用户要求）
                prefs.navAppPkg = apps[which].second
                Feedback.show(ctx, getString(R.string.nav_app_set_done, apps[which].first))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 快捷导航编辑对话框。
     * @param index 编辑的条目下标；-1 = 新增
     */
    private fun editQuickNav(index: Int) {
        val ctx = requireContext()
        val entries = QuickNavAdapter.readEntries(prefs)
        val editing = index in entries.indices
        // ⭐ 两字段（正常输入逻辑）：按钮名称（第一行，必填）/ 地点（第二行，可选）
        // ⭐ 第二行回显规则：若 value 与 label 相同（自动补齐的历史）→ 显示空，
        //    方便用户只改名称；若 value 与 label 不同（显式填的坐标/链接）→ 原样显示
        // ⭐ 新增时 index=-1，不能直接取 entries[index]（越界崩溃）
        val e = entries.getOrNull(index) ?: QuickNavAdapter.Entry("", "")
        val valueShown = if (editing && e.value == e.label) "" else
            if (editing) e.value else ""
        val labelInput = android.widget.EditText(ctx).apply {
            hint = getString(R.string.quick_nav_label_hint)
            if (editing) setText(e.label)
            setSelection(text.length)
        }
        val valueInput = android.widget.EditText(ctx).apply {
            hint = getString(R.string.quick_nav_value_hint)
            if (editing) setText(valueShown)
            setSelection(text.length)
        }
        val dlgContainer = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(labelInput)
            addView(valueInput)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.overlay_quick_nav))
            .setView(dlgContainer)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val label = labelInput.text.toString().trim()
                val valueTyped = valueInput.text.toString().trim()
                // ⭐ 按钮（自定义名称）必填；第二行空 → 地点=按钮名称（存入 value 供
                //    搜索，但下次编辑不回显，方便修改）
                if (label.isNotBlank()) {
                    val value = valueTyped.ifBlank { label }
                    val list = QuickNavAdapter.readEntries(prefs)
                    if (editing) list[index] = QuickNavAdapter.Entry(value, label)
                    else list.add(QuickNavAdapter.Entry(value, label))
                    QuickNavAdapter.writeEntries(prefs, list)
                }
                quickAdapter.reload()
            }
            .setNeutralButton("删除") { _, _ ->
                if (editing) {
                    val list = QuickNavAdapter.readEntries(prefs).toMutableList()
                    list.removeAt(index)
                    QuickNavAdapter.writeEntries(prefs, list)
                }
                quickAdapter.reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** ⭐ 精确坐标点击：选择「直接导航」或「路径规划」 */
    private fun showNavModeDialog(dest: String, lat: String, lon: String) {
        val ctx = requireContext()
        fun launch(uri: String) {
            runCatching {
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(uri))
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(dest)
            .setItems(arrayOf("直接导航", "路径规划")) { _, which ->
                when (which) {
                    0 -> launch(QuickNavAdapter.buildNaviUri(dest, lat, lon))
                    1 -> launch(QuickNavAdapter.buildPoiUri(dest, lat, lon))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun quickNavEntries() = QuickNavAdapter.readEntries(prefs)
    private fun saveQuickNav(entries: List<QuickNavAdapter.Entry>) =
        QuickNavAdapter.writeEntries(prefs, entries)

    /**
     * 快捷导航点击 URI（运行时构造，不落盘）：
     * ① 完整链接（含 ://）原样使用；② 地名 → 高德关键词导航；③ 空 → 按钮名称当关键词
     */
    private fun buildQuickNavUri(name: String, input: String): String {
        return when {
            input.contains("://") -> input
            input.isNotBlank() -> {
                val kw = java.net.URLEncoder.encode(input, "UTF-8")
                "androidamap://keywordNavi?sourceApplication=myapp&keyword=$kw&style=0"
            }
            else -> {
                val kw = java.net.URLEncoder.encode(name, "UTF-8")
                "androidamap://keywordNavi?sourceApplication=myapp&keyword=$kw&style=0"
            }
        }
    }


    private fun launchApp(pkg: String) {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

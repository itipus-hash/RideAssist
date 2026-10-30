package com.yanyang.yybms.ui.overlay

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yanyang.yybms.R
import com.yanyang.yybms.YybmsAppHolder
import com.yanyang.yybms.data.prefs.AppPrefs
import com.yanyang.yybms.databinding.FragmentOverlayBinding
import com.yanyang.yybms.ui.overlay.OverlayItems.saveOrder

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

        // ⭐ 打开导航软件：首次点击弹选择器（只设定不打开），之后直接打开；长按重选
        b.btnNavApp.setOnClickListener {
            if (prefs.overlayJumpOnNav) {
                (activity as? com.yanyang.yybms.ui.main.MainActivity)?.gotoRealtimeSpeedCard()
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
                    (activity as? com.yanyang.yybms.ui.main.MainActivity)?.gotoRealtimeSpeedCard()
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

        adapter = OverlayItemsAdapter(
            prefs,
            onUiChanged = { restartIfRunning() },
        )
        b.recyclerOverlayItems.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)

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

        // ⭐ 悬浮项目折叠（默认收起，点击展开/收起）
        b.headerItems.setOnClickListener {
            val show = b.recyclerOverlayItems.visibility == View.GONE
            b.recyclerOverlayItems.visibility = if (show) View.VISIBLE else View.GONE
            b.tvArrow.text = if (show) "▾" else "▸"
        }
        b.sliderBgAlpha.addOnChangeListener { _, v, _ ->
            val a = v.toInt()
            prefs.overlayBgAlpha = a
            b.tvBgAlpha.text = "$a %"
            com.yanyang.yybms.ui.overlay.TpmsOverlayService.bgAlphaListener?.invoke(a)
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
            android.widget.Toast.makeText(
                ctx, getString(R.string.overlay_perm_hint), android.widget.Toast.LENGTH_LONG
            ).show()
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

    private fun restartIfRunning() {
        if (!TpmsOverlayService.isRunning) return
        val ctx = requireContext()
        ctx.stopService(android.content.Intent(ctx, TpmsOverlayService::class.java))
        ctx.startService(android.content.Intent(ctx, TpmsOverlayService::class.java))
    }

    private fun refreshToggleText() {
        b.btnOverlayToggle.text = getString(
            if (TpmsOverlayService.isRunning) R.string.overlay_stop else R.string.overlay_start
        )
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
                android.widget.Toast.makeText(
                    ctx, getString(R.string.nav_app_set_done, apps[which].first),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
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

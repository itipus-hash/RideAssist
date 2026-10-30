package com.yanyang.yybms.ui.overlay

import android.view.LayoutInflater
import androidx.recyclerview.widget.RecyclerView
import com.yanyang.yybms.R
import com.yanyang.yybms.data.prefs.AppPrefs
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import com.yanyang.yybms.ui.overlay.OverlayItems.saveOrder

/**
 * 悬浮项目列表适配器：每行 名称 + 字号切换 + 开关。
 * 排序由 ItemTouchHelper 驱动（长按拖动），onMove 里持久化新顺序。
 */
class OverlayItemsAdapter(
    private val prefs: AppPrefs,
    private val onUiChanged: () -> Unit,
) : RecyclerView.Adapter<OverlayItemsAdapter.VH>() {

    val defs = OverlayItems.ordered(prefs).toMutableList()

    class VH(val root: ViewGroup) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.view_overlay_item_row, parent, false) as ViewGroup
        )

    override fun getItemCount(): Int = defs.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val def = defs[position]
        val label = holder.root.findViewById<android.widget.TextView>(R.id.tv_label)
        val btnSize = holder.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_size)
        val sw = holder.root.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_item)
        val sizeNames = arrayOf(
            holder.root.context.getString(R.string.overlay_size_small),
            holder.root.context.getString(R.string.overlay_size_mid),
            holder.root.context.getString(R.string.overlay_size_large),
        )

        label.text = holder.root.context.getString(def.labelRes)
        btnSize.text = sizeNames[def.getSize(prefs).coerceIn(0, 2)]
        // 先读状态再挂监听，避免回收复用时回调误写
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = def.getShow(prefs)
        // 透明度行无开关
        sw.visibility = if (def.key == "bg") android.view.View.GONE else android.view.View.VISIBLE
        sw.setOnCheckedChangeListener { _, checked ->
            def.setShow(prefs, checked)
            TpmsOverlayService.orderListener?.invoke()
        }
        btnSize.setOnClickListener {
            val next = (def.getSize(prefs) + 1) % 3
            def.setSize(prefs, next)
            btnSize.text = sizeNames[next]
            TpmsOverlayService.orderListener?.invoke()
        }
    }

    /** ItemTouchHelper 拖动回调：交换并持久化顺序 */
    fun onMove(from: Int, to: Int): Boolean {
        if (from !in defs.indices || to !in defs.indices) return false
        defs[from] = defs[to].also { defs[to] = defs[from] }
        saveOrder(prefs, defs.map { it.key })
        notifyItemMoved(from, to)
        // ⭐ 直接通知已运行的悬浮窗重建行（stop/start 异步合并会导致顺序不生效）
        TpmsOverlayService.orderListener?.invoke()
        onUiChanged()
        return true
    }
}

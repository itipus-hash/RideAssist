package io.github.supit.rideassist.ui.overlay

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.supit.rideassist.R
import io.github.supit.rideassist.data.prefs.AppPrefs

/**
 * 快捷导航按钮网格适配器（2 列）。
 *
 * ## 单按钮三合一手势（用户方案）
 * - **快速点按**（<500ms 无移动）→ 打开链接
 * - **按住拖动**（移动超 slop）→ 排序（任意方向，顺序持久化）
 * - **按住不动 ≥500ms** → 进入内容编辑
 *
 * ⚠️⚠️ **索引必须实时取**：拖动排序只发 `notifyItemMoved`，ViewHolder
 * 跟着 ItemView 一起挪位置、**不会重新 onBind**，但它携带的
 * `bindingAdapterPosition` 已经变成新位置。手势回调里绝不能用
 * `onBindViewHolder` 参数 `position` 的闭包快照 —— 否则排序后长按
 * 会把旧下标交给 `editCallback`，编辑对话框取到的是别的按钮
 * （用户 2026-11-10 实测 bug）。见 [resolveQuickNavIndex]。
 */
class QuickNavAdapter(
    private val prefs: AppPrefs,
    private val onChanged: () -> Unit,
    /** 点击快捷导航打开链接前回调（Fragment 用来确保悬浮信息已开启） */
    private val onBeforeLaunch: () -> Unit,
    /** ⭐ 精确坐标点击：回调 (dest, lat, lon) 由 Fragment 弹「直接导航/路径规划」选择 */
    private val onCoordinateNav: (dest: String, lat: String, lon: String) -> Unit,
    private val touchHelper: androidx.recyclerview.widget.ItemTouchHelper,
) : RecyclerView.Adapter<QuickNavAdapter.VH>() {

    /**
     * ⭐ 合并格（用户方案）：一格智能识别三类输入——
     * 含 // = 直达链接；坐标格式（经度,纬度）= 经纬度；其他 = 地名（搜索+规划）。
     * label=自定义按钮名称（空=显示 value）。
     */
    data class Entry(val value: String, val label: String)

    val entries = mutableListOf<Entry>()

    fun reload() {
        entries.clear()
        entries.addAll(readEntries(prefs))
        notifyDataSetChanged()
    }

    class VH(val btn: com.google.android.material.button.MaterialButton) :
        RecyclerView.ViewHolder(btn)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.view_quick_nav_item, parent, false)
                as com.google.android.material.button.MaterialButton
        )

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        // ⭐ 绑定时只渲染**按钮文字**：position 只用来取显示内容，绝不能
        //    把手势回调需要的下标固化进闭包（原因见类 KDoc）。
        val bound = entries[position]
        holder.btn.text = bound.label.ifBlank { bound.value }

        val ctx = holder.btn.context
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var moved = false
        var editOpened = false
        var pendingEdit: Runnable? = null

        fun cancelPendingEdit() {
            pendingEdit?.let { handler.removeCallbacks(it) }
            pendingEdit = null
        }

        holder.btn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    moved = false
                    editOpened = false   // ⭐ 每次按下重置（否则编辑过一次的按钮永久失效）
                    cancelPendingEdit()
                    // ⭐ 按住超过 500ms 立即进入编辑，无需松手（500ms 内拖动=排序）
                    val runnable = Runnable {
                        if (moved) return@Runnable
                        // ⭐⭐ 实时下标：排序后 ViewHolder 不重新 onBind，
                        //    只有 bindingAdapterPosition 是当前正确位置。
                        val idx = resolveQuickNavIndex(
                            entries.size, holder.bindingAdapterPosition
                        ) ?: return@Runnable
                        editOpened = true
                        editCallback?.invoke(idx)
                    }
                    pendingEdit = runnable
                    handler.postDelayed(runnable, HOLD_TO_EDIT_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        moved = true
                        cancelPendingEdit()                       // 取消编辑计时
                        touchHelper.startDrag(holder)             // 接管拖动排序
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPendingEdit()
                    // ⭐ 编辑模式已触发 → 抬起不再当点击打开链接
                    // ⭐ 链接 / 地名同样按**实时位置**取，避免排序后打开别的按钮
                    val idx = resolveQuickNavIndex(entries.size, holder.bindingAdapterPosition)
                    if (ev.actionMasked == MotionEvent.ACTION_UP && !moved && !editOpened && idx != null) {
                        val entry = entries[idx]
                        val value = entry.value
                        val name = entry.label.ifBlank { entry.value }
                        // ⭐ 判定顺序（用户要求）：含 // → 直接响应链接（最优先）；
                        //    坐标 → 弹「直接导航/路径规划」；其余 → 地名搜索
                        if (value.contains("//")) {
                            // ⭐ 链接（网页等）不是导航：不跳页面、不自动开悬浮
                            runCatching {
                                val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(value.trim()))
                                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                v.context.startActivity(i)
                            }
                            return@setOnTouchListener true
                        }
                        // ⭐ 坐标/地名是真导航：跳实时页聚焦速度卡 + 自动开悬浮
                        onBeforeLaunch()
                        TpmsOverlayService.expectNav = true   // ⭐ 切到导航：此期间长按不隐藏
                        val coord = LON_LAT_RE.matchEntire(value)
                        if (coord != null) {
                            onCoordinateNav(name, coord.groupValues[2], coord.groupValues[1])
                            return@setOnTouchListener true
                        }
                        runCatching {
                            val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(buildKeywordUri(value)))
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            v.context.startActivity(i)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** ItemTouchHelper 拖动：交换并持久化（内容不动只换顺序） */
    fun onMove(from: Int, to: Int): Boolean {
        if (from !in entries.indices || to !in entries.indices) return false
        entries[from] = entries[to].also { entries[to] = entries[from] }
        writeEntries(prefs, entries)
        notifyItemMoved(from, to)
        onChanged()
        return true
    }

    var editCallback: ((Int) -> Unit)? = null

    companion object {
        /** 按住多久进入编辑（此时间内移动 = 拖动排序） */
        private const val HOLD_TO_EDIT_MS = 500L

        /**
         * 读取快捷导航条目 —— **只认 {value,label} 两个字段**。
         *
         * ⚠️ 历史上有过三代格式（{value,label} / {dest,label,uri} / {name,uri}），
         *    读取时的兼容分支会把 value / label 写颠倒（用户 2026-11-09 实测踩过）。
         *    现在历史数据已由 `tools/migrate_quick_nav.py` 一次性迁成新格式，
         *    代码里**不再保留任何历史格式的兼容逻辑**。
         *
         * - value：链接 / 地名 / 坐标（"含 ://" 判定为直达链接）
         * - label：按钮显示名（空则显示 value）
         */
        fun readEntries(prefs: AppPrefs): MutableList<Entry> {
            val out = mutableListOf<Entry>()
            runCatching {
                val arr = org.json.JSONArray(prefs.quickNavList)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val value = o.optString("value", "")
                    val label = o.optString("label", "")
                    if (value.isNotBlank()) out.add(Entry(value, label))
                }
            }
            return out
        }

        fun writeEntries(prefs: AppPrefs, entries: List<Entry>) {
            val arr = org.json.JSONArray()
            entries.forEach { e ->
                arr.put(org.json.JSONObject().apply {
                    put("value", e.value)
                    put("label", e.label)
                })
            }
            prefs.quickNavList = arr.toString()
        }

        private val LON_LAT_RE = Regex(
            "^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$"
        )

        /** ⭐ 坐标直接导航：androidamap://navi（用户给的格式） */
        fun buildNaviUri(name: String, lat: String, lon: String): String {
            val poi = java.net.URLEncoder.encode(name, "UTF-8")
            return "androidamap://navi?sourceApplication=app&lat=$lat&lon=$lon&dev=0&style=2&poi=$poi"
        }

        /** ⭐ 坐标规划路径：amapuri://route/plan（用户给的格式） */
        fun buildPoiUri(name: String, lat: String, lon: String): String {
            val dname = java.net.URLEncoder.encode(name, "UTF-8")
            return "amapuri://route/plan/?dname=$dname&dlat=$lat&dlon=$lon&dev=0&t=0"
        }

        /** ⭐ 地名规划路径：amapuri://route/plan?dname（用户给的格式） */
        fun buildKeywordUri(keyword: String): String {
            val dname = java.net.URLEncoder.encode(keyword, "UTF-8")
            return "amapuri://route/plan/?dname=$dname&dev=0&t=0"
        }
    }
}

/**
 * ⭐ 把 ViewHolder 的**当前** `bindingAdapterPosition` 解析成条目下标。
 *
 * 与 Android 无关的纯函数（刻意放在顶层而不是 Adapter 里），方便纯 JVM 单测
 * 钉死「排序后不能用 bind 时 position」这条口径：
 * - `notifyItemMoved` 之后 ViewHolder 不重新 onBind，但位置已经变了；
 * - `RecyclerView.NO_POSITION`(-1) / 越界 / 空列表一律返回 `null`，
 *   绝不把 -1 传进 `editQuickNav` —— 那里 -1 代表「新增」，会误弹新增框。
 */
internal fun resolveQuickNavIndex(size: Int, bindingAdapterPosition: Int): Int? =
    bindingAdapterPosition.takeIf { it in 0 until size }

# RecyclerView 拖动排序 —— 可复用实现

从 RideAssist 抽出来的两套拖动排序。零第三方依赖，只要 `androidx.recyclerview`。
直接拷 `DragSortHelper.kt` 到新项目即可，改的是「怎么存顺序」和「顺序变了通知谁」。

两套的区别：

| | 方案 A：长按整行拖 | 方案 B：按下即滑就拖 |
|---|---|---|
| 触发 | 长按 500ms（`isLongPressDragEnabled = true`） | 手指移动超过 touchSlop |
| 适合 | 设置类列表（开关、字号行） | 按钮网格（点按要能用，不能等长按） |
| 长按占用 | 被拖动占用 | 可另外绑「长按 = 编辑」 |
| 本项目用例 | 悬浮项目列表（`OverlayItemsAdapter`） | 快捷导航 2 列网格（`QuickNavAdapter`） |

---

## 一、`DragSortHelper.kt`（整份可拷）

```kotlin
package com.example.dragsort

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

// ============================================================
// 方案 A：长按整行拖动
// ============================================================

/**
 * 长按整行拖动排序。
 *
 * @param onMove 数据层移动回调。**必须**在这里做三件事：改 list → 存顺序 →
 *               `notifyItemMoved(from, to)`。返回值表示是否接受这次移动。
 * @param canDrag 某一行是否允许被拖动（第 0 行固定、表头行禁止等）。
 */
class RowDragCallback(
    private val onMove: (from: Int, to: Int) -> Boolean,
    private val canDrag: (position: Int) -> Boolean = { true },
) : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP or ItemTouchHelper.DOWN or
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
    0, // swipe 方向 = 0：不启用滑动删除
) {

    override fun onMove(
        rv: RecyclerView,
        vh: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        // ⚠️ 必须用 bindingAdapterPosition，它是「当前」位置。
        //    不能用 onBindViewHolder 时传进来的那个 position（拖动后不重新 bind，快照已过期）。
        val from = vh.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        if (!canDrag(from)) return false
        return onMove(from, to)
    }

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun isLongPressDragEnabled() = true

    /** 个别行禁止拖动：返回 0 = 该行拖不动，也不会把别人挤走 */
    override fun getDragDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
        if (canDrag(vh.bindingAdapterPosition)) super.getDragDirs(rv, vh) else 0

    /** 可选：拖动中给个视觉反馈（本项目没加，想要可以留） */
    override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(vh, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            vh?.itemView?.apply {
                alpha = 0.6f
                scaleX = 1.02f
                scaleY = 1.02f
            }
        }
    }

    override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
        super.clearView(rv, vh)
        vh.itemView.apply {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }
    }
}

// ============================================================
// 方案 B：手滑即拖 + 长按编辑 + 点按执行（三合一）
// ============================================================

/**
 * 一个格子上的三种手势，互不打架：
 *
 * - **点按**（松开时没移动、没进编辑）→ `onTap`
 * - **移动超过 touchSlop** → 立刻交给 [touchHelper] 拖动排序
 * - **按住不动 ≥ holdMs** → `onLongHold`（不用松手）
 *
 * ⚠️ 配合的 ItemTouchHelper 必须 `isLongPressDragEnabled() = false`，
 *    否则长按会被 ItemTouchHelper 抢去拖动，就永远进不了编辑。
 */
class GridDragTouchDelegate(
    private val touchHelper: ItemTouchHelper,
    private val onTap: (holder: RecyclerView.ViewHolder) -> Unit,
    private val onLongHold: (holder: RecyclerView.ViewHolder) -> Unit,
    private val holdMs: Long = 500L,
) {
    private val handler = Handler(Looper.getMainLooper())

    /** 每个 ViewHolder 存一份手势状态，避免回收复用时串味 */
    private val states = HashMap<RecyclerView.ViewHolder, State>()

    private class State {
        var downX = 0f
        var downY = 0f
        var moved = false
        var longHoldFired = false
        var pending: Runnable? = null
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attach(holder: RecyclerView.ViewHolder, view: View) {
        val st = states.getOrPut(holder) { State() }
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop

        // 必须显式 clickable，否则某些控件收不到事件
        view.isClickable = true

        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // ⚠️ 用 rawX/rawY：拖动时 View 会被平移，x/y 的基准会跟着漂
                    st.downX = ev.rawX
                    st.downY = ev.rawY
                    st.moved = false
                    st.longHoldFired = false // ⚠️ 每次按下必须重置，否则编辑过一次的格子永久失效
                    st.pending?.let { handler.removeCallbacks(it) }
                    val r = Runnable {
                        if (st.moved) return@Runnable
                        if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) return@Runnable
                        st.longHoldFired = true
                        onLongHold(holder) // 实时下标由调用方从 holder.bindingAdapterPosition 取
                    }
                    st.pending = r
                    handler.postDelayed(r, holdMs)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - st.downX
                    val dy = ev.rawY - st.downY
                    if (!st.moved &&
                        (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)
                    ) {
                        st.moved = true
                        st.pending?.let { handler.removeCallbacks(it) }
                        st.pending = null
                        touchHelper.startDrag(holder) // 之后就由 ItemTouchHelper 接管事件
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    st.pending?.let { handler.removeCallbacks(it) }
                    st.pending = null
                    if (ev.actionMasked == MotionEvent.ACTION_UP &&
                        !st.moved && !st.longHoldFired
                    ) {
                        if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) onTap(holder)
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 列表清空 / Adapter 换数据时调一下，别让 HashMap 涨着 */
    fun clear() {
        states.values.forEach { it.pending?.let { r -> handler.removeCallbacks(r) } }
        states.clear()
    }
}

// ============================================================
// 顺序持久化：只存 key，不存 index
// ============================================================

/**
 * 把「保存的 key 顺序」还原成实际对象列表。
 *
 * - 保存的 key 按顺序映射回对象
 * - 保存里有、但对象表里已删除的 key → 自动丢弃
 * - 对象表里有、但保存里没有的（**新增项**）→ 追加到末尾
 *
 * 最后这条最关键：老用户 SP 里没有新项目的 key，不追加就永远看不见新项目。
 */
fun <T> restoreOrder(
    all: List<T>,
    savedKeys: String,
    keyOf: (T) -> String,
): MutableList<T> {
    val keys = savedKeys.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val out = ArrayList<T>(all.size)
    // 按保存的顺序取对象；已删除的 key 自然取不到 → 丢弃
    keys.forEach { k -> all.firstOrNull { keyOf(it) == k }?.let { hit -> out.add(hit) } }
    // 保存里没有的新增项 → 追加到末尾
    all.forEach { item -> if (out.none { keyOf(it) == keyOf(item) }) out.add(item) }
    return out
}

/** 存顺序：只存 key 的逗号串。新增/删除项目后 index 全变，存 index 必错 */
fun <T> saveOrder(items: List<T>, keyOf: (T) -> String): String =
    items.joinToString(",") { keyOf(it) }
```

---

## 二、接入（三步）

### 1. Adapter 里加 `moveItem`

```kotlin
class MyAdapter(...) : RecyclerView.Adapter<MyAdapter.VH>() {

    val items = restoreOrder(ALL_ITEMS, prefs.myOrder, { it.key }).toMutableList()

    fun moveItem(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from !in items.indices || to !in items.indices) return false

        // ⭐ 用 remove + add（真·插入），不要写 items[from] = items[to].also { ... } 的交换。
        //    ItemTouchHelper 是逐格调 onMove 的，两者都碰巧对；
        //    但 remove+add 在 from/to 相隔多格时也正确，交换会直接丢数据。
        items.add(to, items.removeAt(from))

        prefs.myOrder = saveOrder(items) { it.key } // 只落顺序
        notifyItemMoved(from, to)                   // ⚠️ 只发这一条，别 notifyDataSetChanged
        onOrderChanged()                            // 通知外部刷新（见下面第 3 条）
        return true
    }
}
```

### 2. Fragment / Activity 里挂上

```kotlin
// 方案 A：长按整行拖
val cb = RowDragCallback(
    onMove = { from, to -> adapter.moveItem(from, to) },
    canDrag = { pos -> pos != 0 }, // 第 0 行（比如背景/表头）固定
)
ItemTouchHelper(cb).attachToRecyclerView(binding.recycler)

// 方案 B：网格三合一
val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP or ItemTouchHelper.DOWN or
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
) {
    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
        adapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition)

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit
    override fun isLongPressDragEnabled() = false // ⚠️ 必须是 false，否则长按进不了编辑
})
helper.attachToRecyclerView(binding.recycler)

val delegate = GridDragTouchDelegate(
    touchHelper = helper,
    onTap = { holder ->
        val i = holder.bindingAdapterPosition
        if (i in adapter.items.indices) doAction(adapter.items[i])
    },
    onLongHold = { holder ->
        val i = holder.bindingAdapterPosition
        if (i in adapter.items.indices) showEditDialog(i)
    },
)
// 在 onBindViewHolder 里：delegate.attach(holder, holder.itemView)
```

### 3. 「顺序变了」只通知渲染层，绝不重建容器

```kotlin
private fun onOrderChanged() {
    // ✅ 对：只让已经在跑的东西重读顺序、重建它自己的子 View
    myOverlayService.orderListener?.invoke()

    // ❌ 错（本项目真踩过）：重启前台服务 / 重建窗口
    //    restartService()  → 重新 addView → 窗口位置被打回默认点
    //    表现为「一拖动排序，悬浮窗就跳回右上角」
}
```

---

## 三、五条必踩的坑

### 1. 拖动后拿 `position` 会拿到旧下标

`notifyItemMoved` 之后 ViewHolder **不重新 onBind**，它跟着 itemView 挪了位置，
`bindingAdapterPosition` 已经更新，但 `onBindViewHolder` 那个参数 `position`
和闭包里抓到的值还是旧的。

后果：排序后长按 A 格，弹出来的是 B 格的编辑框。

```kotlin
// ❌ 闭包快照
override fun onBindViewHolder(holder: VH, position: Int) {
    holder.btn.setOnLongClickListener { edit(position); true }   // 排序后错位
}

// ✅ 实时读
override fun onBindViewHolder(holder: VH, position: Int) {
    holder.btn.setOnLongClickListener {
        val i = holder.bindingAdapterPosition                        // 永远是当前
        if (i in items.indices) edit(i)
        true
    }
}
```

建议把这段判据抽成纯函数，方便写单测钉死：

```kotlin
internal fun resolveIndex(size: Int, bindingAdapterPosition: Int): Int? =
    bindingAdapterPosition.takeIf { it in 0 until size }  // -1 / 越界 → null
```

`NO_POSITION`（-1）一定要拦掉：如果 -1 在你的业务里表示「新增」，
传进去就会误弹新增框（本项目就这么错过一次）。

### 2. 数据移动用 remove+add，不用交换

```kotlin
// ⚠️ 只在 from/to 相邻时正确，相隔多格会丢一条
items[from] = items[to].also { items[to] = items[from] }

// ✅ 任何情况都对
items.add(to, items.removeAt(from))
```

### 3. 只能 `notifyItemMoved`，不能 `notifyDataSetChanged`

`notifyDataSetChanged` 会让所有 ViewHolder 重建 + 拖动动画消失 + 手指下的 View 被换掉
（拖动直接中断）。同理，**每秒刷新数值的列表里不要整表 rebuild**。

### 4. 顺序存 key，不存 index

见上面的 `restoreOrder`。存 index 的话：删一个项目 → 后面全错位；
新增一个项目 → 老用户永远看不到（保存的顺序里没有它）。

### 5. 方案 B 的两个细节

- `isLongPressDragEnabled()` 必须是 `false`。设 `true` 的话长按被 ItemTouchHelper
  抢走变成拖动，你绑的「长按编辑」永远不触发。
- `longHoldFired` 标志必须在 `ACTION_DOWN` 重置。否则编辑过一次的格子，
  第二次点按会被判成「已经在编辑中」而不响应（本项目实测过）。

---

## 四、和本项目的耦合点（复用时要替换）

| 本项目 | 复用改成 |
|---|---|
| `AppPrefs.overlayOrder`（SharedPreferences 字符串） | 你项目里存顺序的地方；建议照样存逗号串 |
| `OverlayItems.ordered(prefs)` / `saveOrder(prefs, keys)` | 换成 `restoreOrder()` / `saveOrder()` |
| `TpmsOverlayService.orderListener?.invoke()` | 你的「渲染层刷新」回调 |
| `OverlayItems.saveOrder` 里 key = `def.key` | 你的数据里取稳定不变的那个字段做 key，**别用 index、别用显示文案** |

---

## 五、源码位置（本项目）

| 文件 | 内容 |
|---|---|
| `ui/overlay/OverlayItemsAdapter.kt` | 方案 A 的数据层 `onMove`（第 63–80 行，含「不重启服务」的注释） |
| `ui/overlay/OverlayFragment.kt` | `RowDragCallback` 等价物 `ItemTouchCallback`（第 160–182 行）、挂载处（第 157 行） |
| `ui/overlay/QuickNavAdapter.kt` | 方案 B 的完整三合一手势（第 63–167 行）+ `resolveQuickNavIndex`（第 244 行） |
| `ui/overlay/OverlayItems.kt` | `ordered()` / `saveOrder()`（第 103–112 行） |
| `app/src/test/.../QuickNavIndexTest.kt` | 上面那条「实时下标」的单测 |

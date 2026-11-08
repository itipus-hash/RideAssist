package io.github.supit.rideassist.ui.diag

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextPaint
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.supit.rideassist.R
import io.github.supit.rideassist.YybmsAppHolder
import io.github.supit.rideassist.core.gate.MoveGate
import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData
import io.github.supit.rideassist.databinding.ActivityDiagBinding
import io.github.supit.rideassist.ui.realtime.SessionSpeedTracker
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ============================================================================
 *  ⭐⭐⭐ **诊断面板** —— 本项目所有「无法验证」的交付都靠它收口
 * ============================================================================
 *
 * ## 存在的理由
 * 本项目多次出现「改了但不知道对不对」的困境，根因是：
 * **无有效数据时，UI 元素会被显隐逻辑隐藏掉**，
 * 于是「改对了」和「根本没数据」在截图上长得一模一样。
 *
 * 已中招的实例：
 * - 「停车时预计可行驶里程上涨」→ 修复后无法确认是否真的不涨了
 * - `Kw` → `Kwh` 单位 → 无电耗值时单位被 GONE，截图里看不到
 * - 穿隧道功率判据 150W → 无隧道场景，无法验证
 * - 异常电芯标记 → 压差卡无记录时第四行不显示
 *
 * ## 本页把「藏起来的东西」全部摊开
 *
 * | 区块 | 内容 |
 * |---|---|
 * | ① 原始累加器 | 里程 / 耗能 / 回收 / 净耗能 / 骑行时长 / 采样点数 |
 * | ② 预计里程的**分子分母并排** | 剩余能量 Wh ÷ 单位电耗 Wh/km = 预计里程 |
 * | ③ 移动判定 | 三路证据的实时值 + **命中哪一路** + 门控对照表 |
 * | ④ 派生指标 | 压差趋势斜率 / 实测有效容量 / 实测 SOH / SOC 跨度 |
 * | ⑤ 告警诊断 | 每一类告警的「硬件位｜实测值｜阈值」三列并排 |
 * | ⑥ 协议自检锚点 | 均衡截止 3.9V、压差阈值 20mV 等量纲明确的字段 |
 *
 * ⚠️ ② 是本页最核心的价值：把公式的三项**同时**显示，
 * 任何一个数字不合理都能立刻定位是分子还是分母的问题 ——
 * 这正是当初「停车里程上涨」误判的根本原因（只查了分子）。
 */
class DiagActivity : AppCompatActivity() {

    private lateinit var b: ActivityDiagBinding

    /** ⭐ 与实时页/骑行页/诊断页**同一份** tracker（Application 级单例） */
    private val tracker: SessionSpeedTracker by lazy {
        YybmsAppHolder.from(this).sessionTracker
    }

    private val repo by lazy { YybmsAppHolder.from(this).monitorRepository }
    private val app by lazy { YybmsAppHolder.from(this) }

    // ⚠️ 「最后一次有效数据」不放这里：诊断面板每次打开都是新 Activity 实例，
    //    自己的字段一重建就没了（用户实测「断开后再进来电池信息全空」）。
    //    快照改由 Application 持有，见 [io.github.supit.rideassist.YybmsApp.lastRealtime]。

    private val ui = Handler(Looper.getMainLooper())

    /** 逐秒刷新；只刷新文本，不做任何采样 */
    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDiagBinding.inflate(layoutInflater)
        setContentView(b.root)

        // ⭐ 不走 setSupportActionBar：它会把菜单托管给 Activity 的
        // onCreateOptionsMenu（默认空菜单），代码里手动 add 的菜单项可能被清掉。
        // 本页只用 Toolbar 显示标题 + 右上角关闭按钮，自己管理足够。
        // 标题居中（XML 里 titleCentered），关闭按钮放**右上角**菜单位（用户要求）。
        // 左侧不放 navigationIcon —— 左侧是「返回」语义，右上角才是「关闭」。
        b.toolbar.menu.add(getString(R.string.diag_close)).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            icon = androidx.core.content.ContextCompat.getDrawable(
                this@DiagActivity, android.R.drawable.ic_menu_close_clear_cancel,
            )
            setOnMenuItemClickListener {
                finish()
                true
            }
        }

        // ⭐ 门控对照表是**静态**的，只渲染一次

        setupDiagPrefs()

        b.btnReset.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.diag_reset_title)
                .setMessage(R.string.diag_reset_msg)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    // ⭐ 只清会话内存。本地阈值 / 设备信息在独立的 device_cache 文件里，
                    //    不会被这个按钮波及（用户 2026-11-08 明确要求）
                    tracker.resetSession()
                    Toast.makeText(
                        this,
                        getString(R.string.diag_reset_done) + "（本地阈值 / 设备缓存保留）",
                        Toast.LENGTH_SHORT
                    ).show()
                    render()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        // ⭐ 订阅实时数据：断连/未连接时也要能显示（显示「未连接」而不是空白）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.realtime.collect { d ->
                    // 断开时优先用**本地磁盘快照**（跨 APP 重启也有效），
                    // 没有再退回 Application 里的内存快照
                    val snap = if (d.hasDeviceData) null
                    else io.github.supit.rideassist.data.prefs.BatterySnapshotStore
                        .load(app.deviceCache)
                    val shown = if (d.hasDeviceData) d
                    else snap?.toDisplayData() ?: app.lastRealtime
                    val stamp = if (d.hasDeviceData) 0L
                    else snap?.savedAtMs ?: app.lastRealtimeAtMs
                    renderDevice(
                        shown,
                        live = d.hasDeviceData,
                        savedAtMs = stamp,
                        profile = io.github.supit.rideassist.data.prefs.DeviceProfileStore
                            .load(app.deviceCache),
                    )
                    renderAlarms(d)
                }
            }
        }
        ui.post(tick)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick)
    }

    // ==================== 渲染 ====================

    /** 全量重绘：累加器 / 预计里程分解 / 移动判定 / 派生指标 */
    private fun render() {
        val s = tracker.debugSnapshot()

        // ---------- ④ 派生指标 ----------
        // ⚠️ 原来的「压差趋势斜率」已删除（用户 2026-11-08：「根本没用」）：
        // 它以 mV/min 做线性回归，压差本身在 4~8mV 量级抖动，斜率几乎全是噪声，
        // 既不能指导判断也占版面。
        b.tvDerived.text = buildString {
            if (s.measuredCapacityAh > 0f) {
                appendLine("实测有效容量　${fmt(s.measuredCapacityAh.toDouble(), 2)} Ah")
                appendLine("实测 SOH　　　${fmt(s.measuredSoh.toDouble(), 1)} %")
            } else {
                // 用户定的版式：主行只说"样本不足"，说明**另起一行且不带括号**
                // （原来挤在括号里自动折行，看着像排版坏了）
                appendLine("实测有效容量　样本不足")
                append(
                    "已跟踪 SOC 跨度 %.1f%%，需满 %d%%".format(
                        s.socSpanTracked,
                        SessionSpeedTracker.MIN_SOC_SPAN_FOR_CAPACITY.toInt()
                    )
                )
            }
        }
    }

/** ⑤ 告警诊断 + ⑥ 保护参数 + 设备信息 —— 三块都用「隐形表格」控件渲染 */
    private fun renderAlarms(raw: BmsRealtimeData) {
        // ⭐⭐ 没有实时数据时，用**本地缓存的阈值**把表格撑起来（用户要求）：
        //「可以直接显示本地化保存过的阈值，实测值显示 -」。
        // 之前这里 `!hasDeviceData` 就整块 return 成「未连接设备」，
        // 于是缓存阈值根本没机会展示 —— 等于阈值仍然要等保护板。
        val cached = io.github.supit.rideassist.data.prefs.ProtectParamsStore
            .load(YybmsAppHolder.from(this).deviceCache)
        val d = if (!raw.hasProtectParams && cached != null) cached.applyTo(raw) else raw

        if (!d.hasDeviceData) {
            if (!d.hasProtectParams) {
                // 既没有实时数据、也没有任何阈值来源：如实说"未连接"
                b.llAlarms.removeAllViews()
                b.tvAlarms.text = getString(R.string.diag_no_device)
                return
            }
            // 有缓存阈值：继续往下渲染，实测值列会全是 "-"
            // ⚠️⚠️ 这里**绝不能** llIdentity.removeAllViews()：
            //    identity 表由 renderDevice 全权渲染（本函数在它之后执行），
            //    在这里清空会把刚填好的 15 行全抹掉 ——
            //    「表格怎么都出不来」的根因就是这行（用户 2026-11-08）
        }
      fillTable(
    container = b.llAlarms,
            header = listOf("告警项", "实测值", "阈值"),
            rows = d.alarmDiagnostics(),
      // 第 2、3 列右对齐，数值小数点自然成列
 numericFrom = 1,
      )
        val eff = d.effectiveAlarms()
        // ⭐ 阈值来源必须写出来（用户要求阈值固化到本地后能分辨来源）：
        // 没读到 → 明确说"未判定"，而不是让表格里一片 "-" 看着像正常
        b.tvAlarms.text = buildString {
            append("阈值来源：")
            append(
                when {
                    !d.hasProtectParams -> "未读到（电压 / 电流 / 温度类未判定）"
                    d.protectParamsFromCache -> "本地缓存 " + savedAtText(d.protectParamsSavedAtMs)
                    else -> "本次从保护板读到的"
                }
            )
            append("\n")
            append(
                if (eff.isEmpty()) "生效告警：无"
                else "生效告警：" + eff.joinToString("  ") { it.label }
            )
        }

        // ⚠️⚠️ 这里**不要再填 llIdentity**：renderDevice 已用「静态 + 动态」15 行
        //    合并渲染，这里的旧调用会把它覆盖成纯静态 9 行
        //    （用户 2026-11-08 实测「动态行怎么不在表格里」的根因就是它）。
        //    profile 的加载也挪到了 renderDevice 调用点。

        // ⚠️ 原来的「均衡参数」独立卡已删除：内容已并入保护板信息表
        //    （用户 2026-11-08：上面已经有了，不要再单独搞一个格子）
    }

    /** 区块标题（卡片内的小标题），与隐形表格同字体风格 */
    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        setTextAppearance(R.style.DiagCell)
  this.text = text
        alpha = 0.7f
    }

    /**
     * ⭐⭐⭐ **隐形表格**：把二维数据渲染成一组真控件。
     *
     * ## 为什么不用「TextView 补空格」或「─ │ 画线」
     * 两种都试过，都失败：
     * - 补空格：依赖 monospace 字体 + 字符宽度函数**完全**正确，
     *   `℃`(U+2103) 这类符号一旦算错宽度，整列串位；
     * - 画线：实机字体里 `─` `│` **不是等宽字符**，横线断成虚线、竖线接不上。
     *
     * ## 做法
     * 每行一个 `LinearLayout`，每格一个 `TextView`，
     * 列宽用 `weight` 按「该列最大内容显示宽度」按比例分配 ——
     * **列位由 layout 保证**，不依赖任何字符宽度假设。
     *
     * @param numericFrom 从第几列开始右对齐（数值列右对齐才能逐行比大小）
     */
    private fun fillTable(
        container: LinearLayout,
        header: List<String>?,
        rows: List<List<String>>,
        numericFrom: Int = Int.MAX_VALUE,
        /** 数值列是否居中（默认右对齐；保护板信息这类字符串值居中更好看） */
        centerValues: Boolean = false,
        /** 值等于这些内容的单元格染成绿色（如均衡「已开启」） */
        greenValues: Set<String> = emptySet(),
    ) {
        container.removeAllViews()
        if (rows.isNotEmpty()) {
            android.util.Log.i(
                "FillTable",
                "rows=${rows.size} cols=${rows[0].size} container=${container.id}"
            )
        }
        if (rows.isEmpty() && header == null) return
        val cols = maxOf(header?.size ?: 0, rows.maxOfOrNull { it.size } ?: 0)
        if (cols == 0) return

// ⚠️ 列宽**不能靠 weight 按比例分配**：weight 只保证「比例」，
        // 不保证「最小宽度」。三列时数值列会被压到放不下 "3.655V" 而折行
        //（实测 `-20.0℃` 被拆成两行）。
        // 正确做法：用真实字体 **measureText** 量出每列最大内容宽度，
        // 数值列固定这个宽度（列位由固定宽度保证），剩余空间全给标签列。
   val paint = TextPaint().apply {
    typeface = android.graphics.Typeface.MONOSPACE
        textSize = dp(12).toFloat()
        isAntiAlias = true
      }
     val padPx = dp(4)
        val numericWidth = IntArray(cols) { 0 }
        for (c in 0 until cols) {
       if (c < numericFrom) continue
            val cells = buildList {
        header?.let { add(it.getOrElse(c) { "" }) }
  addAll(rows.map { it.getOrElse(c) { "" } })
       }
      // ⭐ 额外留 6dp 余量：列宽原本刚好等于最长内容宽度，
      //    表头「实测值」三个字会顶到边框上（用户反馈「挤」）。
      numericWidth[c] = cells.maxOf {
                paint.measureText(it) + padPx * 2
       }.toInt() + dp(6)
        }

fun addRow(cells: List<String>, isHeader: Boolean) {
     val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
 for (c in 0 until cols) {
       val cell = cells.getOrElse(c) { "" }
   val tv = TextView(this).apply {
         setTextAppearance(R.style.DiagCell)
     text = cell
            gravity = when {
                // ⭐ 缺失值（`-`）居中：右对齐时它会孤零零贴在列的最右边，
                //    看着像"忘了填"。居中后至少视觉上是"这里没有值"（用户要求）
                cell == "-" -> android.view.Gravity.CENTER
                c < numericFrom -> android.view.Gravity.START
                centerValues -> android.view.Gravity.CENTER
                else -> android.view.Gravity.END
            }
            if (cell in greenValues) {
                setTextColor(0xFF2E7D32.toInt())   // 深绿：均衡「已开启」
            }
  if (isHeader) alpha = 0.85f
    // ⭐ 数值列固定宽度 + 不换行 —— 列位由此保证，不会因内容长度而漂移
         if (c >= numericFrom) {
          maxLines = 1
   setSingleLine()
            setPadding(padPx, 0, padPx, 0)
       } else {
       setPadding(padPx, 0, padPx, 0)
       }
       // ⭐ 表格线用 shape stroke 画，**不用 ─ │ 字符**
      // （实机 monospace 字体里这两个字符不等宽，横线会断成虚线）
      setBackgroundResource(
   if (isHeader) R.drawable.diag_cell_header else R.drawable.diag_cell_border,
     )
   }
            val lp = if (c >= numericFrom) {
          LinearLayout.LayoutParams(numericWidth[c], LinearLayout.LayoutParams.WRAP_CONTENT)
      } else {
   // 标签列吃掉剩余空间
LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
          }
      line.addView(tv, lp)
    }
    container.addView(line)
        }

header?.let { addRow(it, isHeader = true) }
 rows.forEach { addRow(it, isHeader = false) }
        android.util.Log.i(
            "FillTable",
            "added ${rows.size + (if (header != null) 1 else 0)} rows, children=${container.childCount}"
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ==================== 连接设置 + BMS 报警（自设置页迁入） ====================

    /**
     * 诊断面板顶部的两组开关（用户要求迁入）：
     * - 连接设置：启动自动连接 + 保护板信息（只读）
     * - BMS 报警：震动 / 弹窗
     * 读写与原设置页**同一组 prefs key**，行为全 APP 一致。
     */
    private fun setupDiagPrefs() {
        val prefs = io.github.supit.rideassist.YybmsAppHolder.from(this).prefs

        // 先读初始状态再挂监听，避免回调误写
        b.switchDiagAutoConnect.isChecked = prefs.autoConnectEnabled
        b.switchDiagAutoConnect.setOnCheckedChangeListener { _, checked ->
            prefs.autoConnectEnabled = checked
        }

        b.switchDiagVibrate.isChecked = prefs.vibrateEnabled
        b.switchDiagVibrate.setOnCheckedChangeListener { _, checked ->
            prefs.vibrateEnabled = checked
        }

        b.switchDiagDialog.isChecked = prefs.alertDialogEnabled
        b.switchDiagDialog.setOnCheckedChangeListener { _, checked ->
            prefs.alertDialogEnabled = checked
        }

        refreshSavedDevice(prefs)
    }

    override fun onResume() {
        super.onResume()
        // 回到面板时刷新保护板信息（用户可能刚在首页连上别的设备）
        val prefs = io.github.supit.rideassist.YybmsAppHolder.from(this).prefs
        refreshSavedDevice(prefs)
    }

    private fun refreshSavedDevice(prefs: io.github.supit.rideassist.data.prefs.AppPrefs) {
        // ⭐ 设备名与 MAC 放**同一行**（用户要求）：中间用两个空格隔开，
        //    不再分两行占高度
        b.tvDiagSavedDevice.text = if (prefs.hasSavedDevice) {
            "${prefs.lastDeviceName ?: "未知设备"}  ${prefs.lastDeviceMac}"
        } else {
            getString(R.string.no_saved_device)
        }
    }

    /**
     * ⭐ **保护板信息（合并版）**：静态身份 + 电池动态量合并成一张表
     *（用户 2026-11-08：电池信息与保护板信息有重复，合并到保护板信息）。
     *
     * - 静态行来自 [DeviceProfile]（保护板型号/版本/SN/电池类型/串数/额定容量…）
     * - 动态行来自实时数据（总压/电流/功率/SOC/剩余容量/循环/均衡）
     * - 温度与断开标注在表格下方（长文本不适合放固定宽度列）
     */
    private fun renderDevice(
        raw: BmsRealtimeData,
        live: Boolean,
        savedAtMs: Long = 0L,
        profile: io.github.supit.rideassist.core.protocol.model.DeviceProfile? = null,
    ) {
        // 快照/实时数据可能缺设备信息帧字段（电池类型、固件、探头数、串数），
        // 用 DeviceProfile 缓存兜底
        val d = raw.copy(
            batteryType = if (raw.batteryType >= 0) raw.batteryType
            else profile?.batteryType ?: -1,
            firmwareVersion = raw.firmwareVersion
                .takeIf { it.isNotBlank() && it != "--" }
                ?: profile?.firmwareVersion?.takeIf { it.isNotBlank() } ?: "--",
            tempProbeCount = if (raw.tempProbeCount > 0) raw.tempProbeCount
            else profile?.tempProbeCount ?: 0,
            cellCount = if (raw.cellCount > 0) raw.cellCount else profile?.cellCount ?: 0,
            maxCellNumSupported = if (raw.maxCellNumSupported > 0) raw.maxCellNumSupported
            else profile?.maxCellNumSupported ?: 0,
        )


        // ⭐ 温度与首页同规则：探头数过滤 + **同值合并**（TempSlotDedup）。
        // ⚠️ 去重比较的是**数值字符串** —— 必须传纯数值（不带 T1/T2 前缀），
        //    带了前缀「T1 30.0℃」「T2 30.0℃」字符串不同，永远合并不了
        //    （用户 2026-11-08 实测「T1 T2 不是一样吗，还一起显示」）。
        val probeCount = d.tempProbeCount
        val rawTemps = buildList {
            add("MOS" to d.mosTemp)
            if (probeCount >= 1) add("T1" to d.temp1)
            if (probeCount >= 2) add("T2" to d.temp2)
            if (probeCount >= 3) add("T4" to d.temp3)
        }
        val display = rawTemps.map { it.second?.let { v -> "%.1f℃".format(v) } }
        val hidden = io.github.supit.rideassist.util.TempSlotDedup.hiddenIndices(display)
        val tempText = rawTemps.mapIndexed { i, (label, _) ->
            if (hidden[i] || display[i] == null) null else "$label ${display[i]}"
        }.filterNotNull().joinToString("  ")

        // ⭐ 温度行放进表格「温度探头」下面（用户要求）
        val rows = buildList {
            val base = profile?.identityRows() ?: d.deviceIdentity()
            val probeIdx = base.indexOfFirst { it[0] == "温度探头" }
            base.forEachIndexed { i, row ->
                add(row)
                if (i == probeIdx && tempText.isNotBlank()) add(listOf("温度", tempText))
            }
            // ⭐ 总压 / 电流 / 功率合一行（用户 2026-11-09）
            add(
                listOf(
                    "总压",
                    "%.2fV / %.2fA / %.0fW".format(d.totalVoltage, d.totalCurrent, d.power)
                )
            )
            add(listOf("SOC", "${d.soc.toInt()} %"))
            add(listOf("剩余容量", "%.2f Ah / %.2f Ah".format(d.remainingCapacity, d.ratedCapacity)))
            add(listOf("循环", "${d.cycleCount} 次"))
            add(
                listOf(
                    "均衡状态",
                    if (d.balanceOn) {
                        if (d.balancingCells.isEmpty()) "已开启"
                        else "已开启（第 ${d.balancingCells.joinToString(",")} 串）"
                    } else "未开启"
                )
            )
            // ⭐ 均衡参数紧跟均衡状态（原来在独立的「均衡参数」卡里，合并进表）
            //    这两项**也已进本地缓存**（BatterySnapshot 的 balanceCutoffVolt /
            //    balanceDiffThreshold），所以断开后照样显示（用户 2026-11-08）
            if (d.balanceCutoffVolt > 0f) {
                add(listOf("均衡截止电压", "%.3f V".format(d.balanceCutoffVolt)))
            }
            if (d.balanceDiffThreshold > 0f) {
                add(listOf("均衡压差阈值", "%.1f mV".format(d.balanceDiffThreshold)))
            }
        }
        fillTable(
            b.llIdentity, header = null, rows = rows, numericFrom = 1, centerValues = true,
            greenValues = if (d.balanceOn) setOf(
                if (d.balancingCells.isEmpty()) "已开启"
                else "已开启（第 ${d.balancingCells.joinToString(",")} 串）"
            ) else emptySet(),
        )

        // ⭐ 动态量已在上方表格里（总压/SOC/循环/均衡/温度），
        //    这里只放**表格放不下的长文本**：断开标注
        b.tvDevice.text = if (!live && savedAtMs > 0L) {
            "（${formatStamp(savedAtMs)} 缓存数据。）"
        } else ""
    }

    private fun fmt(v: Double, d: Int): String = String.format(Locale.US, "%.${d}f", v)

    /** 墙钟毫秒 → HH:mm */
    private fun clockOf(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            Locale.US, "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }

    /** 时刻 → MM-dd HH:mm（缓存标注共用） */
    private fun formatStamp(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            Locale.US, "%02d-%02d %02d:%02d",
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }

    /** 本地缓存阈值的保存时刻，形如「（11-08 20:31 保存）」 */
    private fun savedAtText(ms: Long): String {
        if (ms <= 0L) return "（时间未知）"
        return "（${formatStamp(ms)} 保存）"
    }

    private fun fmtMs(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }
}
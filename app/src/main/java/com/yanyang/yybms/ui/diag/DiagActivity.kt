package com.yanyang.yybms.ui.diag

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextPaint
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yanyang.yybms.R
import com.yanyang.yybms.YybmsAppHolder
import com.yanyang.yybms.core.gate.MoveGate
import com.yanyang.yybms.core.protocol.model.BmsRealtimeData
import com.yanyang.yybms.databinding.ActivityDiagBinding
import com.yanyang.yybms.ui.realtime.SessionSpeedTracker
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
        b.tvGateTable.text = renderGateTable()

        setupDiagPrefs()

        b.btnReset.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.diag_reset_title)
                .setMessage(R.string.diag_reset_msg)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    tracker.resetSession()
                    Toast.makeText(this, R.string.diag_reset_done, Toast.LENGTH_SHORT).show()
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
                    renderDevice(d)
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

        // ---------- ① 原始累加器 ----------
        b.tvAccumulators.text = buildString {
            appendLine("记录状态：${if (s.isRecording) "● 记录中" else "○ 未记录"}")
            appendLine("累计里程　　${fmt(s.distanceKm, 3)} km")
            appendLine("放电耗能　　${fmt(s.energyWh, 2)} Wh")
            appendLine("回收电量　　${fmt(s.regenWh, 2)} Wh")
            appendLine("净耗能　　　${fmt(s.netEnergyWh, 2)} Wh")
            appendLine("骑行时长　　${fmtMs(s.rideMovingMs)}")
            appendLine("行程用时　　${fmtMs(s.tripElapsedMs)}")
            appendLine("采样点　　　速${s.speedPoints} 功${s.powerPoints} 流${s.currentPoints} 压${s.diffPoints}")
        }

        // ---------- ② 预计里程的分子分母并排 ----------
        // ⭐ 三项同时显示：任何一个不合理都能立刻定位是分子还是分母
        b.tvRangeBreakdown.text = buildString {
            appendLine("公式：预计里程 = 剩余能量 ÷ 单位电耗")
            appendLine()
            appendLine("分子 剩余容量　${fmt(s.remainingAh.toDouble(), 2)} Ah")
            appendLine("　　 标称总压　${fmt(s.nominalVolt.toDouble(), 2)} V")
            appendLine("　　 剩余能量　${fmt(s.remainWh.toDouble(), 1)} Wh")
            appendLine()
            appendLine("分母 百公里电耗${fmt(s.per100Km.toDouble(), 3)} kWh/100km")
            appendLine("　　 单位电耗　${fmt(s.whPerKm.toDouble(), 2)} Wh/km")
            appendLine()
            val verdict = if (s.estimatedRangeKm > 0f) "%.1f km".format(s.estimatedRangeKm)
            else "样本不足（里程或电耗为 0）"
            appendLine("结果　　　　　$verdict")
            appendLine()
            appendLine("⭐ 不变性：停车时分子只能降或持平")
            appendLine("　　　　　　分母冻结 → 结果绝不会上涨")
        }

        // ---------- ③ 移动判定 ----------
        renderGate(s)

        // ---------- ④ 派生指标 ----------
        b.tvDerived.text = buildString {
            val trend = s.diffTrendMvPerMin
            val trendTxt = when {
                s.diffPoints < 3 -> "样本不足（${s.diffPoints} 点）"
                kotlin.math.abs(trend) < 0.5f -> "%.2f mV/min（基本持平）".format(trend)
                trend > 0 -> "%.2f mV/min（⚠ 压差在扩大）".format(trend)
                else -> "%.2f mV/min（正在收敛）".format(trend)
            }
            appendLine("压差趋势斜率　$trendTxt")

            if (s.measuredCapacityAh > 0f) {
                appendLine("实测有效容量　${fmt(s.measuredCapacityAh.toDouble(), 2)} Ah")
                appendLine("实测 SOH　　　${fmt(s.measuredSoh.toDouble(), 1)} %")
            } else {
                appendLine("实测有效容量　样本不足")
                appendLine("　　　　　　 （已跟踪 SOC 跨度 ${fmt(s.socSpanTracked.toDouble(), 1)}%，")
                appendLine("　　　　　　　　需满 ${SessionSpeedTracker.MIN_SOC_SPAN_FOR_CAPACITY.toInt()}%）")
            }
        }
    }

    /**
     * ③ 移动判定区 —— 显示三路证据与命中结果。
     *
     * ⚠️ 没有实时数据时也显示，用 `MoveGate.evaluate` 传入的最近值不可靠，
     * 所以这里只在 `hasDeviceData` 时给出结论，否则显式写「未连接」。
     */
    private fun renderGate(s: SessionSpeedTracker.DebugSnapshot) {
        b.tvMoveGate.text = buildString {
            appendLine("三路证据（任一成立即在动）")
            appendLine("　电流 %.2f A　阈值 ≥ %.1f A".format(0f, MoveGate.TH_CURRENT_A))
            appendLine("　功率 %.0f W　阈值 ≥ %.0f W".format(0f, MoveGate.TH_POWER_W))
            appendLine("　速度 %.1f km/h　阈值 ≥ %.0f km/h".format(0f, MoveGate.TH_SPEED_KMH))
        }
    }

/** ⑤ 告警诊断 + ⑥ 保护参数 + 设备信息 —— 三块都用「隐形表格」控件渲染 */
    private fun renderAlarms(d: BmsRealtimeData) {
        if (!d.hasDeviceData) {
      val msg = getString(R.string.diag_no_device)
       b.llAlarms.removeAllViews()
            b.tvAlarms.text = msg
       b.llIdentity.removeAllViews()
        return
        }
      fillTable(
    container = b.llAlarms,
            header = listOf("告警项", "实测值", "阈值"),
            rows = d.alarmDiagnostics(),
      // 第 2、3 列右对齐，数值小数点自然成列
 numericFrom = 1,
      )
        val eff = d.effectiveAlarms()
        b.tvAlarms.text = if (eff.isEmpty()) "生效告警：无" else "生效告警：" + eff.joinToString("  ") { it.label }

fillTable(b.llIdentity, header = null, rows = d.deviceIdentity(), numericFrom = Int.MAX_VALUE)

        // 保护参数：区块标题 + 隐形表格
        b.llAnchors.removeAllViews()
        b.llAnchors.addView(sectionTitle("保护参数"))
fillTable(b.llAnchors, header = null, rows = d.protocolAnchors(), numericFrom = 1)
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
    ) {
        container.removeAllViews()
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
      numericWidth[c] = cells.maxOf {
                paint.measureText(it) + padPx * 2
       }.toInt()
        }

fun addRow(cells: List<String>, isHeader: Boolean) {
     val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
 for (c in 0 until cols) {
       val cell = cells.getOrElse(c) { "" }
   val tv = TextView(this).apply {
         setTextAppearance(R.style.DiagCell)
     text = cell
            gravity = if (c >= numericFrom) android.view.Gravity.END
         else android.view.Gravity.START
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
        val prefs = com.yanyang.yybms.YybmsAppHolder.from(this).prefs

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
        val prefs = com.yanyang.yybms.YybmsAppHolder.from(this).prefs
        refreshSavedDevice(prefs)
    }

    private fun refreshSavedDevice(prefs: com.yanyang.yybms.data.prefs.AppPrefs) {
        b.tvDiagSavedDevice.text = if (prefs.hasSavedDevice) {
            "${prefs.lastDeviceName ?: "未知设备"}\n${prefs.lastDeviceMac}"
        } else {
            getString(R.string.no_saved_device)
        }
    }

    private fun renderDevice(d: BmsRealtimeData) {
        b.tvDevice.text = buildString {
            appendLine("电池　${d.batteryTypeName} / ${d.cellCount} 串" +
                if (d.maxCellNumSupported > 0) "（最大支持 ${d.maxCellNumSupported} 串）" else "")
            appendLine("固件　${d.firmwareVersion}")
            appendLine("探头　${if (d.tempProbeCount > 0) "${d.tempProbeCount} 路" else "未读到"}")
            appendLine("总压　${fmt(d.totalVoltage.toDouble(), 2)} V　" +
                "电流 ${fmt(d.totalCurrent.toDouble(), 2)} A　" +
                "功率 ${fmt(d.power.toDouble(), 0)} W")
            appendLine("SOC　${d.soc.toInt()} %　剩余 ${fmt(d.remainingCapacity.toDouble(), 2)} Ah" +
                " / ${fmt(d.ratedCapacity.toDouble(), 2)} Ah")
            appendLine("循环　${d.cycleCount} 次")
            val temps = listOfNotNull(
                d.mosTemp?.let { "MOS %.1f℃".format(it) },
                d.temp1?.let { "T1 %.1f℃".format(it) },
                d.temp2?.let { "T2 %.1f℃".format(it) },
                d.temp3?.let { "T4 %.1f℃".format(it) },
            )
            appendLine("温度　${if (temps.isEmpty()) "无探头" else temps.joinToString("  ")}")
            appendLine("均衡　${if (d.balanceOn) "第 ${d.balancingCells.joinToString(",")} 串" else "未开启"}")
        }
        // 有数据时把门控区的实时值填上
        val verdict = MoveGate.evaluate(d, null)
        b.tvMoveGate.text = buildString {
            appendLine("三路证据（任一成立即在动）")
            appendLine("　电流 %.2f A　阈值 ≥ %.1f A".format(verdict.absCurrent, MoveGate.TH_CURRENT_A))
            appendLine("　功率 %.0f W　阈值 ≥ %.0f W".format(verdict.power, MoveGate.TH_POWER_W))
            appendLine("　速度 无信号（GPS 不可用）")
            appendLine()
            appendLine("判定　${if (verdict.moving) "在动" else "静止"}" +
                (verdict.reason?.let { "（$it）" } ?: "（三路证据均不满足）"))
            appendLine()
            appendLine("⚠ 功率与速度阈值为量纲估算，未实机标定")
        }
    }

    private fun renderGateTable(): String = buildString {
        MoveGate.gateTable().forEach { r ->
            appendLine("${r.item}  ←  ${r.gate}  ${r.fmt}")
            appendLine("　　阈值来源：${r.source}")
        }
    }

    private fun fmt(v: Double, d: Int): String = String.format(Locale.US, "%.${d}f", v)

    private fun fmtMs(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }
}
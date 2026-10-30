package io.github.powerycy.rideassist.ui.settings

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.powerycy.rideassist.R
import io.github.powerycy.rideassist.YybmsAppHolder
import io.github.powerycy.rideassist.databinding.ActivitySettingsBinding

/**
 * 设置页（2026-10-20 重构）：
 * 连接设置与 BMS 报警开关已移入**诊断面板**（用户要求），本页只留胎压相关配置：
 * - 胎压告警阈值（低压/高压/高温）
 * - 已绑定传感器信息（只读；绑定/解绑在首页胎压卡长按操作）
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { YybmsAppHolder.from(this).prefs }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ⭐ 关闭按钮放右上角菜单（用户要求，与诊断面板一致）
        binding.toolbar.menu.add(getString(R.string.diag_close)).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            icon = androidx.core.content.ContextCompat.getDrawable(
                this@SettingsActivity, android.R.drawable.ic_menu_close_clear_cancel,
            )
            setOnMenuItemClickListener {
                finish()
                true
            }
        }

        setupTpmsThresholds()
        setupBatteryModel()

        // ⭐ 绑定信息含「传感器电量」—— 电量来自实时收包缓存，
        // 订阅 state 让本页打开期间跟随刷新（新包到达即更新）
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                tpms.state.collect { refreshBoundInfo(it) }
            }
        }
    }

    // ==================== 胎压告警阈值 ====================

    /**
     * 胎压阈值三行（低压/高压/高温）：点击弹出输入框修改。
     *
     * ## 为什么用 EditText 弹窗而不是滑杆/数字滚轮
     * 一年调不了几次的东西，不值得上自定义控件；一个输入框 + 范围校验足够。
     * 范围校验挡的是手滑（低压填 15），不是攻击。
     */
    private fun setupTpmsThresholds() {
        refreshTpmsThresholds()
        binding.rowTpmsLow.setOnClickListener {
            editThreshold(
                title = getString(R.string.tpms_low_threshold),
                current = prefs.tpmsLowBar,
                min = 0.5f, max = 4.0f, isInt = false,
            ) { prefs.tpmsLowBar = it }
        }
        binding.rowTpmsHigh.setOnClickListener {
            editThreshold(
                title = getString(R.string.tpms_high_threshold),
                current = prefs.tpmsHighBar,
                min = 1.0f, max = 5.0f, isInt = false,
            ) { prefs.tpmsHighBar = it }
        }
        binding.rowTpmsTemp.setOnClickListener {
            editThreshold(
                title = getString(R.string.tpms_temp_threshold),
                current = prefs.tpmsHighTemp.toFloat(),
                min = 30f, max = 120f, isInt = true,
            ) { prefs.tpmsHighTemp = it.toInt() }
        }
    }

    private fun refreshTpmsThresholds() {
        binding.tvTpmsLow.text = getString(R.string.tpms_value_bar, prefs.tpmsLowBar)
        binding.tvTpmsHigh.text = getString(R.string.tpms_value_bar, prefs.tpmsHighBar)
        binding.tvTpmsTemp.text = getString(R.string.tpms_value_temp, prefs.tpmsHighTemp)
    }

    private fun editThreshold(
        title: String,
        current: Float,
        min: Float,
        max: Float,
        isInt: Boolean,
        save: (Float) -> Unit,
    ) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (isInt) current.toInt().toString() else String.format(java.util.Locale.US, "%.2f", current))
            setSelection(text.length)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val v = input.text.toString().toFloatOrNull()
                if (v == null || v < min || v > max) {
                    Toast.makeText(
                        this,
                        getString(R.string.tpms_threshold_invalid, min, max),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                save(v)
                refreshTpmsThresholds()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ==================== 已绑定传感器信息 ====================

    private val tpms by lazy { YybmsAppHolder.from(this).tpmsMonitor }

    override fun onResume() {
        super.onResume()
        refreshBoundInfo(tpms.state.value)
    }


    private fun refreshBoundInfo(state: io.github.powerycy.rideassist.core.ble.tpms.TpmsMonitor.UiState) {
        // ⭐ 本卡只保留 SN（用户要求）；温度/胎压/电量/更新时间在下方「传感器数据」卡
        binding.tvTpmsBoundFront.text =
            prefs.tpmsFrontSn?.let { getString(R.string.tpms_info_sn_fmt, it) }
                ?: getString(R.string.tpms_info_unbound)
        binding.tvTpmsBoundRear.text =
            prefs.tpmsRearSn?.let { getString(R.string.tpms_info_sn_fmt, it) }
                ?: getString(R.string.tpms_info_unbound)
        binding.tvTpmsBatteryModel.text = prefs.tpmsBatteryModel
        refreshSensorData(state)
    }

    // ==================== 传感器数据（前/后轮：胎压/温度/电量/更新时间） ====================

    /**
     * 电量判断 —— **逆向 DJTPMS 官方逻辑**（`setTpmsData`/`setVoltageMsg`）：
     * - 电压值有效（0 < V < 25.5）：V > 2.5V = 正常，否则低电
     * - 电压 = 0.0 或 25.5（传感器不报电压）：看广播里的 VBAT 标志位（bit0），
     *   0 = 正常，1 = 低电
     * 本车实测两颗传感器的电压字节均为 0，官方显示「电量正常」即走的标志位分支 ——
     * 传感器只广播 1bit 电量状态，没有连续电压。
     */
    private fun batteryText(r: io.github.powerycy.rideassist.core.ble.tpms.TpmsPacket.Reading): String =
        if ((r.voltage > 0 && r.voltage < 25.5 && r.voltage <= 2.5) ||
            ((r.voltage == 0.0 || r.voltage == 25.5) && r.lowBattery)
        ) {
            getString(R.string.tpms_battery_low)
        } else {
            getString(R.string.tpms_battery_normal)
        }

    /** 相对时间（不带「更新于」前缀，用户要求）：<1min 刚刚，<10min N分钟前，≥10min 绝对时刻 */
    private fun relativeUpdateText(ts: Long): String {
        val diffMin = (System.currentTimeMillis() - ts) / 60_000L
        return when {
            diffMin < 1L -> getString(R.string.tpms_rel_just)
            diffMin < 10L -> getString(R.string.tpms_rel_min_ago, diffMin.toInt())
            else -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(ts))
        }
    }

    private fun refreshSensorData(state: io.github.powerycy.rideassist.core.ble.tpms.TpmsMonitor.UiState) {
        val lowText = getString(R.string.tpms_battery_low)
        val lowColor = androidx.core.content.ContextCompat.getColor(this, R.color.abnormal)
        fun applyRow(view: android.widget.TextView, pos: Int) {
            val posName = getString(
                if (pos == io.github.powerycy.rideassist.core.ble.tpms.TpmsMonitor.POS_FRONT)
                    R.string.tpms_front else R.string.tpms_rear
            )
            val sn = if (pos == io.github.powerycy.rideassist.core.ble.tpms.TpmsMonitor.POS_FRONT)
                prefs.tpmsFrontSn else prefs.tpmsRearSn
            if (sn.isNullOrBlank()) {
                view.text = "$posName " + getString(R.string.tpms_info_unbound)
                return
            }
            val r = state.readings[sn]
            if (r == null) {
                view.text = "$posName " + getString(R.string.tpms_no_data_short)
                return
            }
            val bat = batteryText(r)
            val pressureText = String.format(java.util.Locale.US, "%.2fbar", r.bar)
            val full = "$posName $pressureText · ${r.tempC}℃ · $bat · " +
                relativeUpdateText(r.timestampMs)
            // ⭐ 标红规则（与首页胎压卡同口径）：
            // 胎压越界（<低压 或 >高压）标红胎压值；电量不足标红那四个字
            val pressureAlarm = r.bar < prefs.tpmsLowBar || r.bar > prefs.tpmsHighBar
            val batteryLow = bat == lowText
            if (pressureAlarm || batteryLow) {
                val sp = android.text.SpannableString(full)
                if (pressureAlarm) {
                    val start = full.indexOf(pressureText)
                    sp.setSpan(
                        android.text.style.ForegroundColorSpan(lowColor),
                        start, start + pressureText.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (batteryLow) {
                    val start = full.indexOf(bat)
                    sp.setSpan(
                        android.text.style.ForegroundColorSpan(lowColor),
                        start, start + bat.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                view.text = sp
            } else {
                view.text = full
            }
        }
        applyRow(binding.tvSensorFront, io.github.powerycy.rideassist.core.ble.tpms.TpmsMonitor.POS_FRONT)
        applyRow(binding.tvSensorRear, io.github.powerycy.rideassist.core.ble.tpms.TpmsMonitor.POS_REAR)
    }

    /** 传感器电池型号：点击弹输入框修改（默认 CR1632，方便更换时知道型号） */
    private fun setupBatteryModel() {
        binding.tvTpmsBatteryModel.text = prefs.tpmsBatteryModel
        binding.rowTpmsBattery.setOnClickListener { editBatteryModel() }
    }

    private fun editBatteryModel() {
        val input = android.widget.EditText(this).apply {
            setText(prefs.tpmsBatteryModel)
            setSelection(text.length)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.tpms_battery_model))
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    prefs.tpmsBatteryModel = v
                    binding.tvTpmsBatteryModel.text = v
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

package io.github.supit.rideassist.ui.settings

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.supit.rideassist.R
import io.github.supit.rideassist.YybmsAppHolder
import io.github.supit.rideassist.data.prefs.AppPrefs
import io.github.supit.rideassist.databinding.ActivityConnectionSettingsBinding
import io.github.supit.rideassist.util.RecordThreshold
import io.github.supit.rideassist.util.TpmsDisplayMode

/**
 * ⭐ **独立的「连接设置」页**（用户 2026-11-09）：从首页连接卡的齿轮进入。
 *
 * 刻意与 [SettingsActivity]（胎压阈值 / 已绑定传感器）物理分开：
 * 连接设置是「连上保护板之后才用得上」的东西，混进胎压页既不好找，
 * 也会让两处设置互相挤占版面。
 *
 * 目前七项，风格一致（文字在左、控件直接在右、无说明文案）：
 * 1. **连接后跳转** —— 连上保护板后切到哪个标签页，默认「首页」；
 * 2. **实时曲线默认**（2026-11-09 新增）—— 进入实时页时曲线默认
 *    「显示曲线」还是「不显示曲线」，默认后者（保持旧体验）；
 * 3. **最大压差记录阈值（mV）**（2026-11-10 新增）—— 聚焦压差卡的历史卡
 *    只记录压差严格大于该值的行程，默认 50；
 * 4. **极速记录阈值（Km/h）**（2026-11-10 新增）—— 聚焦速度卡的历史卡
 *    只记录速度严格大于该值的行程，默认 50；
 * 5. **电流记录阈值（A）**（2026-11-10 新增）—— 聚焦电流卡的历史卡
 *    只记录电流严格大于该值的行程，默认 50；
 * 6. **功率记录阈值（W）**（2026-11-10 新增）—— 聚焦功率卡的历史卡
 *    只记录功率严格大于该值的行程，默认 **1000**（量级不同，别跟着写 50）；
 * 7. **实时卡片胎压显示**（2026-11-10 新增）—— 实时页的胎压什么时候显示：
 *    「始终显示」/「始终不显示」/「胎压异常时显示」，默认「始终显示」。
 *
 * ⚠️ 3~6 四项留空 / 填非法值（负数、NaN、非数字）一律按各自的
 *    [RecordThreshold] 默认值处理：失焦、按键盘「完成」、退出页面三个时机
 *    都会把输入框回填成实际生效值，用户不会看到「空着但其实是默认值」的困惑状态。
 *
 * ⚠️ 第 7 项**不依赖保护板**（胎压是独立的一路蓝牙），所以首页连接卡的齿轮
 *    从 2026-11-10 起**未连接时也显示**，否则没连上板子就进不了这个设置。
 */
class ConnectionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionSettingsBinding
    private val prefs by lazy { YybmsAppHolder.from(this).prefs }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 与设置页 / 信息面板一致：右上角关闭按钮
        binding.toolbar.menu.add(getString(R.string.diag_close)).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            icon = ContextCompat.getDrawable(
                this@ConnectionSettingsActivity,
                android.R.drawable.ic_menu_close_clear_cancel,
            )
            setOnMenuItemClickListener {
                finish()
                true
            }
        }

        setupConnectJumpTab()
        setupCurveDefault()
        setupRecordThresholds()
        setupRealtimeTpmsDisplay()
    }

    /**
     * ⭐ 用户按返回键 / 系统手势退出时，把还没提交的输入也算进去：
     *    否则「改了数字直接返回」会悄悄丢掉这次修改。
     *    这里与失焦回调走同一个 [commitThreshold]，重复调用无副作用。
     */
    override fun onPause() {
        super.onPause()
        commitThreshold(binding.etDiffThreshold, RecordThreshold.DEFAULT) {
            prefs.diffRecordThresholdMv = it
        }
        commitThreshold(binding.etTopSpeedThreshold, RecordThreshold.DEFAULT) {
            prefs.topSpeedRecordThresholdKmh = it
        }
        commitThreshold(binding.etCurrentThreshold, RecordThreshold.DEFAULT) {
            prefs.currentRecordThresholdA = it
        }
        commitThreshold(binding.etPowerThreshold, RecordThreshold.DEFAULT_POWER) {
            prefs.powerRecordThresholdW = it
        }
    }

    /**
     * ⚠️ `keys` 顺序必须与 `R.array.connect_jump_tab_labels` 完全一致。
     *
     * ⚠️ Spinner 首次 layout 会自动回调一次 onItemSelected，用「值没变就不写」
     *    挡住这次回调，避免把默认值误写进 SP 或覆盖掉用户已保存的选择。
     */
    private fun setupConnectJumpTab() {
        val keys = listOf(
            AppPrefs.TAB_HOME,
            AppPrefs.TAB_REALTIME,
            AppPrefs.TAB_DATA,
            AppPrefs.TAB_OVERLAY,
        )
        val spinner = binding.spinnerConnectJumpTab
        val saved = keys.indexOf(prefs.connectJumpTab)
        spinner.setSelection(if (saved >= 0) saved else 0, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View?, position: Int, id: Long,
            ) {
                val key = keys.getOrElse(position) { AppPrefs.TAB_HOME }
                if (prefs.connectJumpTab != key) prefs.connectJumpTab = key
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    /**
     * ⭐ **实时曲线默认**（用户 2026-11-09）：决定进入实时页时曲线是
     * 「显示曲线」（LIVE）还是「不显示曲线」（OFF），由实时页在切入时
     * 读取本偏好复位初始状态。
     *
     * ⚠️ `R.array.curve_default_labels` 顺序：0 = 显示曲线（true），
     *    1 = 不显示曲线（false）。默认选 1，保持 2026-11-08「打开 APP
     *    默认不显示曲线」的既有体验。
     *
     * ⚠️ 与 [setupConnectJumpTab] 同理，用「值没变就不写」挡住 Spinner
     *    首次 layout 的自动回调，别把默认值当成用户选择写进 SP。
     */
    private fun setupCurveDefault() {
        val spinner = binding.spinnerRealtimeCurveDefault
        spinner.setSelection(if (prefs.realtimeCurveShowByDefault) 0 else 1, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View?, position: Int, id: Long,
            ) {
                val show = position != 1
                if (prefs.realtimeCurveShowByDefault != show) {
                    prefs.realtimeCurveShowByDefault = show
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    /**
     * ⭐ 两项记录阈值（用户 2026-11-10）。
     *
     * 「没填写或者留空，填充默认数值」→ 统一交给 [RecordThreshold.parse]：
     * 空串 / 空白 / 非数字 / 负数 / NaN / 无穷 一律归一到 50。
     *
     * 提交时机有三个，保证不会「输入了却没生效」：
     * 1. 输入框失焦（点别处、切到别的输入框）；
     * 2. 键盘右下角「完成」（[EditorInfo.IME_ACTION_DONE]）；
     * 3. 页面 [onPause]（直接按返回键）。
     *
     * 提交后立即把输入框回填成**实际生效值**，让「空着」这种中间态立刻收敛，
     * 用户看到什么就是什么，不用猜。
     */
    private fun setupRecordThresholds() {
        bindThresholdInput(
            binding.etDiffThreshold,
            prefs.diffRecordThresholdMv,
            RecordThreshold.DEFAULT,
        ) { prefs.diffRecordThresholdMv = it }
        bindThresholdInput(
            binding.etTopSpeedThreshold,
            prefs.topSpeedRecordThresholdKmh,
            RecordThreshold.DEFAULT,
        ) { prefs.topSpeedRecordThresholdKmh = it }
        bindThresholdInput(
            binding.etCurrentThreshold,
            prefs.currentRecordThresholdA,
            RecordThreshold.DEFAULT,
        ) { prefs.currentRecordThresholdA = it }
        // ⚠️ 功率默认 1000W，不是 50
        bindThresholdInput(
            binding.etPowerThreshold,
            prefs.powerRecordThresholdW,
            RecordThreshold.DEFAULT_POWER,
        ) { prefs.powerRecordThresholdW = it }
    }

    private fun bindThresholdInput(
        edit: EditText,
        current: Float,
        default: Float,
        save: (Float) -> Unit,
    ) {
        edit.setText(RecordThreshold.label(current, default))
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitThreshold(edit, default, save)
        }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitThreshold(edit, default, save)
                edit.clearFocus()
                true
            } else {
                false
            }
        }
    }

    /** 解析 → 写偏好 → 回填「实际生效值」；对同一个输入框重复调用是幂等的 */
    private fun commitThreshold(edit: EditText, default: Float, save: (Float) -> Unit) {
        val value = RecordThreshold.parse(edit.text.toString(), default)
        save(value)
        val label = RecordThreshold.label(value, default)
        if (edit.text.toString() != label) edit.setText(label)
    }

    /**
     * ⭐ **实时卡片胎压显示**（用户 2026-11-10）。
     *
     * 三选一：始终显示 / 始终不显示 / 胎压异常时显示。
     * ⚠️ 只作用于实时页**速度卡内那一行**胎压；
     *    聚焦时时间卡两侧的胎压始终显示，不受本设置影响
     *    （用户 2026-11-10 明确：「只想管卡内那一行、聚焦时始终显示」）。
     *
     * ⚠️ 顺序必须与 [TpmsDisplayMode.ALL]（= `R.array.realtime_tpms_display_labels`）
     *    完全一致：0 = always，1 = never，2 = abnormal。
     *
     * ⚠️ 与 [setupConnectJumpTab] 同理，用「值没变就不写」挡住 Spinner
     *    首次 layout 的自动回调，别把默认值当成用户选择写进 SP。
     */
    private fun setupRealtimeTpmsDisplay() {
        val keys = TpmsDisplayMode.ALL
        val spinner = binding.spinnerRealtimeTpmsDisplay
        val saved = keys.indexOf(prefs.realtimeTpmsDisplay)
        spinner.setSelection(if (saved >= 0) saved else 0, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View?, position: Int, id: Long,
            ) {
                val key = keys.getOrElse(position) { TpmsDisplayMode.DEFAULT }
                if (prefs.realtimeTpmsDisplay != key) prefs.realtimeTpmsDisplay = key
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }
}

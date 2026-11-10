package io.github.supit.rideassist.ui.settings

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
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
 * 1. **连接BMS后自动跳转**（2026-11-11 改名）—— 连上保护板后是否切页、切到哪：
 *    「不跳转」/「实时·页面」/「数据·页面」/「导航·页面」，默认「不跳转」；
 * 2. **实时界面曲线显示**（2026-11-11 改名）—— 进入实时页时曲线「默认显示」
 *    还是「默认不显示」，默认后者（保持旧体验）；
 * 3. **压差记录阈值（mV）**（2026-11-10 新增）—— 聚焦压差卡的历史卡
 *    只记录压差严格大于该值的行程，默认 50；
 * 4. **速度记录阈值（Km/h）**（2026-11-10 新增）—— 聚焦速度卡的历史卡
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
 * ⚠️ **点输入框以外的地方会自动结束编辑**（用户 2026-11-11）：
 *    原生 EditText 点空白处不会失焦、键盘一直挂着，所以在
 *    [dispatchTouchEvent] 里拦 ACTION_DOWN —— 落点不在任一阈值输入框内就
 *    清焦点 + 收键盘（清焦点同时触发 commitThreshold 回填，见该函数注释）。
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
     * ⭐ **点输入框以外的地方自动结束编辑**（用户 2026-11-11：
     * 「点击输入阈值的时候，如果不要修改，点击其他地方，能自动退出编辑吗」）。
     *
     * 原生 EditText 只在**焦点转移**时才失焦：点标题文字、卡片空白、工具栏
     * 都不会让它失焦 —— 键盘一直挂着、光标一直闪，非得按返回键才能退出。
     *
     * 这里在 Activity 层拦 `ACTION_DOWN`：落点不在任何一个阈值输入框内，
     * 就清掉焦点并收起键盘。清焦点会顺带触发 [bindThresholdInput] 里的
     * `onFocusChange` → [commitThreshold]，把「空着 / 非法值」回填成实际生效值，
     * 与按返回键、按「完成」是同一个口径（用户什么都没改就退出，值也不会丢）。
     *
     * ⚠️ 判断的是「是否落在**任一**输入框内」，不是只判当前那个：
     *    否则从压差框点到功率框时，会先收键盘再弹出来，明显闪一下。
     * ⚠️ 用 [MotionEvent.getRawX] / [getRawY] 与 `getGlobalVisibleRect` 配对
     *    （都是屏幕坐标）；用 `getX/getY` 会差一个状态栏偏移，点输入框边缘判错。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText && !isTouchInsideThresholdInput(ev)) {
                focused.clearFocus()
                hideKeyboard(focused)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 触摸点是否落在四个阈值输入框里的任意一个内 */
    private fun isTouchInsideThresholdInput(ev: MotionEvent): Boolean {
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        val rect = Rect()
        return thresholdInputs().any { it.getGlobalVisibleRect(rect) && rect.contains(x, y) }
    }

    /** 四个记录阈值输入框（顺序无关，只用于命中判定） */
    private fun thresholdInputs(): List<EditText> = listOf(
        binding.etDiffThreshold,
        binding.etTopSpeedThreshold,
        binding.etCurrentThreshold,
        binding.etPowerThreshold,
    )

    /** 收起软键盘 */
    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
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
     * ⭐ 给下拉框套「文字居中」的适配器（用户 2026-11-11：
     * 「备选和弹出来的菜单…都要居中」）。
     *
     * **为什么不用布局里的 `android:entries`**：
     * 系统默认的条目布局是**左对齐**的，而 `android:gravity` 设在 Spinner 上
     * 只作用于控件本身、传不到条目里的 TextView —— 想让文字居中只能换
     * adapter 的 item 布局（`item_spinner_center` / `item_spinner_dropdown_center`），
     * XML 属性没有捷径。
     *
     * ⚠️ 必须在 `setSelection()` **之前**调用：先设 adapter 再定选中项，
     *    否则选中位置会被随后的 `setAdapter()` 重置回第 0 项。
     */
    private fun bindCenteredSpinner(spinner: Spinner, labelsRes: Int) {
        val adapter = ArrayAdapter(
            this,
            R.layout.item_spinner_center,
            resources.getStringArray(labelsRes),
        )
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown_center)
        spinner.adapter = adapter
    }

    /**
     * ⭐ **连接BMS后自动跳转**（用户 2026-11-11 改名 + 改选项）。
     *
     * 选项（顺序必须与 `R.array.connect_jump_tab_labels` 完全一致）：
     * 0 = 不跳转（[AppPrefs.TAB_NONE]）、1 = 实时·页面、2 = 数据·页面、3 = 导航·页面。
     *
     * ⚠️ Spinner 首次 layout 会自动回调一次 onItemSelected，用「值没变就不写」
     *    挡住这次回调，避免把默认值误写进 SP 或覆盖掉用户已保存的选择。
     */
    private fun setupConnectJumpTab() {
        val keys = listOf(
            AppPrefs.TAB_NONE,
            AppPrefs.TAB_REALTIME,
            AppPrefs.TAB_DATA,
            AppPrefs.TAB_OVERLAY,
        )
        val spinner = binding.spinnerConnectJumpTab
        bindCenteredSpinner(spinner, R.array.connect_jump_tab_labels)
        val saved = keys.indexOf(prefs.connectJumpTab)
        spinner.setSelection(if (saved >= 0) saved else 0, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View?, position: Int, id: Long,
            ) {
                val key = keys.getOrElse(position) { AppPrefs.TAB_NONE }
                if (prefs.connectJumpTab != key) prefs.connectJumpTab = key
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    /**
     * ⭐ **实时界面曲线显示**（用户 2026-11-09 新增 / 2026-11-11 改名）。
     *
     * 决定进入实时页时曲线是「默认显示」（LIVE）还是「默认不显示」（OFF），
     * 由实时页在切入时读取本偏好复位初始状态。
     *
     * ⚠️ `R.array.curve_default_labels` 顺序：0 = 默认显示（true），
     *    1 = 默认不显示（false）。默认选 1，保持 2026-11-08「打开 APP
     *    默认不显示曲线」的既有体验。
     *
     * ⚠️ 与 [setupConnectJumpTab] 同理，用「值没变就不写」挡住 Spinner
     *    首次 layout 的自动回调，别把默认值当成用户选择写进 SP。
     */
    private fun setupCurveDefault() {
        val spinner = binding.spinnerRealtimeCurveDefault
        bindCenteredSpinner(spinner, R.array.curve_default_labels)
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
                hideKeyboard(edit)
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
        bindCenteredSpinner(spinner, R.array.realtime_tpms_display_labels)
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

package io.github.supit.rideassist.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.supit.rideassist.R
import io.github.supit.rideassist.YybmsAppHolder
import io.github.supit.rideassist.data.prefs.AppPrefs
import io.github.supit.rideassist.databinding.ActivityConnectionSettingsBinding

/**
 * ⭐ **独立的「连接设置」页**（用户 2026-11-09）：从首页连接卡的齿轮进入。
 *
 * 刻意与 [SettingsActivity]（胎压阈值 / 已绑定传感器）物理分开：
 * 连接设置是「连上保护板之后才用得上」的东西，混进胎压页既不好找，
 * 也会让两处设置互相挤占版面。
 *
 * 目前只有一项：**连接后跳转** —— 文字在左、下拉框直接在右，
 * 没有说明文案，也没有「不跳转」（选「首页」就等于不跳转）。
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
}

package io.github.supit.rideassist.ui.main

import android.os.Bundle
import android.view.View
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.github.supit.rideassist.R
import io.github.supit.rideassist.databinding.ActivityMainBinding
import io.github.supit.rideassist.ui.cells.CellDetailFragment
import io.github.supit.rideassist.ui.realtime.RealtimeFragment
import io.github.supit.rideassist.ui.monitor.MonitorFragment
import io.github.supit.rideassist.ui.ride.RideDataFragment
import io.github.supit.rideassist.ui.settings.SettingsActivity

/**
 * 主界面：实时监控单页。
 * 底部导航三个页签：首页 / 实时 / 数据。
 * 点击单体电芯进入压差详情页。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTag: String = TAG_MONITOR

    companion object {
        private const val TAG_MONITOR = "monitor"
        private const val TAG_CELL_DETAIL = "cell_detail"
        private const val TAG_REALTIME = "realtime"
        private const val TAG_RIDE_DATA = "ride_data"
        private const val TAG_OVERLAY = "overlay"
        private const val KEY_TAG = "current_tab"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // ⭐ 物理返回键：在单体电芯详情页时回首页（否则直接退出 APP）
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentTag == TAG_CELL_DETAIL) closeCellDetail() else finish()
                }
            })
        // ⭐ 布局里已用 app:menu 直接挂菜单（MaterialToolbar 自绘）——
        //    再调 setSupportActionBar 会被 ActionBar 接管并丢弃 app:menu，
        //    实机上齿轮图标整个消失（点不到设置的根因），二者只能留一个。
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(android.content.Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_close -> {
                    closeCellDetail()
                    true
                }
                else -> false
            }
        }

        // 底部导航：首页 / 实时 / 数据
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { showFragment(TAG_MONITOR); true }
                R.id.nav_realtime -> { showFragment(TAG_REALTIME); true }
                R.id.nav_data -> { showFragment(TAG_RIDE_DATA); true }
                R.id.nav_overlay -> { showFragment(TAG_OVERLAY); true }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
            showFragment(TAG_MONITOR)
            // ⭐ 预创建实时页（add+hide）：GPS 订阅随 APP 启动立即生效 ——
            // 之前 RealtimeFragment 懒加载，首次进入 APP 停在首页时
            // 里程/速度根本没在采集，要切到实时页才开始（用户实测反馈）
            val fm = supportFragmentManager
            if (fm.findFragmentByTag(TAG_REALTIME) == null) {
                val f = RealtimeFragment()
                fm.beginTransaction()
                    .add(R.id.fragment_container, f, TAG_REALTIME)
                    .hide(f)
                    .commitAllowingStateLoss()
            }
        } else {
            currentTag = savedInstanceState.getString(KEY_TAG, TAG_MONITOR)
            showFragment(currentTag)
        }
    }

    /**
     * ⭐ TPMS 占空比扫描的生命周期挂钩：
     * 前台才扫（退后台系统本来也会屏蔽无过滤扫描），回前台自动恢复。
     */
    override fun onStart() {
        super.onStart()
        io.github.supit.rideassist.YybmsAppHolder.from(this).tpmsMonitor.start()
    }

    override fun onStop() {
        super.onStop()
        io.github.supit.rideassist.YybmsAppHolder.from(this).tpmsMonitor.stop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAG, currentTag)
    }

    override fun onSupportNavigateUp(): Boolean {
        // 详情页返回：恢复底部导航
        if (currentTag != TAG_MONITOR && currentTag != TAG_REALTIME && currentTag != TAG_RIDE_DATA) {
            binding.bottomNav.visibility = android.view.View.VISIBLE
            showFragment(TAG_MONITOR)
            binding.bottomNav.selectedItemId = R.id.nav_home
            return true
        }
        return super.onSupportNavigateUp()
    }

    /**
     * ⭐ 供 Fragment 调用：切到实时页并聚焦速度卡片（点任何导航按钮时）。
     * 滚动到速度卡 + 闪烁两下提示。
     */
    fun gotoRealtimeSpeedCard() {
        binding.bottomNav.selectedItemId = R.id.nav_realtime
        showFragment(TAG_REALTIME)
        binding.root.post {
            val frag = supportFragmentManager.findFragmentByTag(TAG_REALTIME) ?: return@post
            val view = frag.view ?: return@post
            val scroll = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.scroll_view)
                ?: return@post
            val card = view.findViewById<View>(R.id.card_speed) ?: return@post
            scroll.smoothScrollTo(0, card.top)
            // ⭐ 聚焦速度卡（等价于单击卡片，用户要求的模式）
            (frag as? io.github.supit.rideassist.ui.realtime.RealtimeFragment)?.focusSpeedCard()
            // 闪烁两下提示聚焦
            card.animate().alpha(0.35f).setDuration(220).withEndAction {
                card.animate().alpha(1f).setDuration(220).withEndAction {
                    card.animate().alpha(0.35f).setDuration(220).withEndAction {
                        card.animate().alpha(1f).setDuration(220).start()
                    }.start()
                }.start()
            }
        }
    }


    /** 供 Fragment 调用：进入单体压差详情 */
    fun openCellDetail() {
        binding.bottomNav.visibility = android.view.View.GONE
        showFragment(TAG_CELL_DETAIL)
    }

    /** ⭐ 关闭单体电芯详情页：回首页 + 恢复底部导航 + 恢复主菜单（扳手） */
    fun closeCellDetail() {
        binding.bottomNav.visibility = android.view.View.VISIBLE
        showFragment(TAG_MONITOR)
    }

    private fun showFragment
(tag: String) {
        currentTag = tag
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()

        /**
         * ⭐ 只**隐藏**其它 Fragment，不再 remove + 重建。
         *
         * 之前每次切页都 `remove` 全部再 `add` 新实例，导致：
         * 1. Fragment 的 View 整个重建，`onViewCreated` 重跑，页面有明显闪烁；
         * 2. GPS 订阅被反复注销/重注册，切页期间**采样中断**；
         * 3. 曲线看起来像"被清空了一下"（新建 Fragment 瞬间队列还没重绘）。
         *
         * hide/show 保留 Fragment 实例与 View，采样连续、视觉无闪烁。
         * 用 show/hide 代替 replace 是官方 `MyApplication` 单例 + 事件总线的
         * 常见做法，也更符合用户「切换页面数据不能丢」的要求。
         */
        fm.fragments.forEach { existing ->
            if (existing.tag == tag) transaction.show(existing)
            else transaction.hide(existing)
        }

        val target = fm.findFragmentByTag(tag)
        if (target == null) {
            val fragment: Fragment = when (tag) {
                TAG_CELL_DETAIL -> CellDetailFragment()
                TAG_REALTIME -> RealtimeFragment()
                TAG_RIDE_DATA -> RideDataFragment()
                TAG_OVERLAY -> io.github.supit.rideassist.ui.overlay.OverlayFragment()
                else -> MonitorFragment()
            }
            transaction.add(R.id.fragment_container, fragment, tag)
        }
        transaction.commitAllowingStateLoss()

        // 详情页显示标题栏 + 右上角 ✕（不显示扳手，防止误入胎压设置）；
        // 其它页隐藏工具栏，避免留空白
        if (tag == TAG_CELL_DETAIL) {
            binding.toolbar.visibility = android.view.View.VISIBLE
            binding.toolbar.title = getString(R.string.title_cell_detail)
            // ⭐ app:menu 挂的扳手与详情页 ✕ 互斥：clear 后换专用关闭菜单
            if (binding.toolbar.menu.findItem(R.id.action_settings) != null) {
                binding.toolbar.menu.clear()
                binding.toolbar.inflateMenu(R.menu.menu_close)
            }
        } else {
            binding.toolbar.visibility = android.view.View.GONE
            // ⭐ 返回普通页时恢复主菜单（供首页右上角扳手使用）
            if (binding.toolbar.menu.findItem(R.id.action_settings) == null) {
                binding.toolbar.menu.clear()
                binding.toolbar.inflateMenu(R.menu.menu_main)
            }
        }
    }
}
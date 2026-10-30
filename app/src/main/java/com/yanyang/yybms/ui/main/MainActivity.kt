package com.yanyang.yybms.ui.main

import android.os.Bundle
import android.view.View
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.yanyang.yybms.R
import com.yanyang.yybms.databinding.ActivityMainBinding
import com.yanyang.yybms.ui.cells.CellDetailFragment
import com.yanyang.yybms.ui.realtime.RealtimeFragment
import com.yanyang.yybms.ui.monitor.MonitorFragment
import com.yanyang.yybms.ui.ride.RideDataFragment
import com.yanyang.yybms.ui.settings.SettingsActivity

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
        setSupportActionBar(binding.toolbar)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(android.content.Intent(this, SettingsActivity::class.java))
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
        com.yanyang.yybms.YybmsAppHolder.from(this).tpmsMonitor.start()
    }

    override fun onStop() {
        super.onStop()
        com.yanyang.yybms.YybmsAppHolder.from(this).tpmsMonitor.stop()
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
            (frag as? com.yanyang.yybms.ui.realtime.RealtimeFragment)?.focusSpeedCard()
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
                TAG_OVERLAY -> com.yanyang.yybms.ui.overlay.OverlayFragment()
                else -> MonitorFragment()
            }
            transaction.add(R.id.fragment_container, fragment, tag)
        }
        transaction.commitAllowingStateLoss()

        // 详情页显示返回按钮与标题栏；首页/实时页隐藏工具栏，避免留空白
        if (tag == TAG_CELL_DETAIL) {
            binding.toolbar.visibility = android.view.View.VISIBLE
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = getString(R.string.title_cell_detail)
        } else {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            binding.toolbar.visibility = android.view.View.GONE
        }
    }
}
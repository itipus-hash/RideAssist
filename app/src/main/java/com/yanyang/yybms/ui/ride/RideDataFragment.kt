package com.yanyang.yybms.ui.ride

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.yanyang.yybms.R
import com.yanyang.yybms.YybmsAppHolder
import com.yanyang.yybms.databinding.FragmentRideDataBinding
import com.yanyang.yybms.ui.realtime.SessionSpeedTracker
import java.util.Locale

/**
 * 底部「数据」页：本次骑行的汇总统计。
 *
 * ## 数据来源
 * 全部来自 Activity 作用域的 [SessionSpeedTracker]（与实时页**同一份**）：
 * - 四项峰值快照：`peakRecords`，由实时页每帧比对刷新
 * - 骑行时长 / 均速：`rideMovingMs` / `avgSpeed`
 *
 * ⭐ 本页**不订阅**电池数据流，纯被动读取——
 * 免得两个页面各自 collect 造成重复采样/重复统计。
 * 只在 [onResume] 与每秒定时器里重绘一次。
 *
 * ⭐ Fragment 用 hide/show 保留实例（见 MainActivity.showFragment），
 * 所以 onResume 时数据是延续的，不需要重建。
 */
class RideDataFragment : Fragment() {

    private var _binding: FragmentRideDataBinding? = null
    private val binding get() = _binding!!

    /** ⭐ 与实时页/诊断页**同一份** tracker（Application 级单例） */
    private val tracker: SessionSpeedTracker
        get() = YybmsAppHolder.from(requireContext()).sessionTracker

    /** 上一次绘制的 [SessionSpeedTracker.peakRevision]，用来跳过无变化的重绘 */
    private var lastRevision: Long = -1L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRideDataBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 首次进入强制全量绘制
        lastRevision = -1L
        render()
    }

    override fun onResume() {
        super.onResume()
        // ⚠️ 回到本页时时长/平均值可能都变了，强制重绘
        lastRevision = -1L
        render()
        // 每秒刷新一次：骑行时长在走，平均值也在变（峰值不会变，见 renderPeak）
        binding.root.postDelayed(ticker, 1000L)
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            // ⭐ postDelayed 而不是 Handler：随 View 生命周期自动消失，
            // 切页后不会再回来跑（避免后台空转）
            view?.postDelayed(this, 1000L)
        }
    }

    private fun render() {
        val b = _binding ?: return

        // 当前时间：与首页时间卡同源同款（HH:mm，24 小时制）。
        // ⚠️ 本页 ticker 本来就每秒跑一次，时钟搭它的车即可，
        // 不必像首页那样另起 Handler（首页的 ticker 间隔不同、职责不同）。
        b.tvCurrentTime.text = CLOCK_FORMAT.format(java.util.Date())

        renderSummary(moving = tracker.rideMovingMs > 0L)
        renderPeaks()
    }

    /**
     * 第一行：预估里程（独占一行，大字突出）
     * 第二行：开始时间 / 行程用时 / 行程均速
     * 第三行：百公里能耗 / 骑行用时 / 骑行均速
     *
     * ⚠️ 两个「用时」的区别，别混：
     * - **行程用时** [SessionSpeedTracker.elapsedSinceStartMs]：墙钟总跨度，**含停车**
     * - **骑行用时** [SessionSpeedTracker.rideMovingMs]：只累加「在动」的时间，停车不计入
     * 同理两个均速：行程均速含停车（会被拉低），骑行均速剔除停车。
     * 两者之差 = 停车总时长。
     */
    private fun renderSummary(moving: Boolean) {
        val b = _binding ?: return
        // ⚠️ 未产生值时一律用占位符，不要重复六遍同一句话 ——
        // 视觉噪音极大且占六倍空间。空状态提示只在页面下方说一次。
        val dash = getString(R.string.ride_placeholder)

        val started = tracker.rideStartWallMs
        val recording = moving || started != 0L

        // ⭐ 数值与单位**成对显隐**（用户要求："有数值才一起显示"）：
        // 数值是占位符 `--` 时独留一条「km/h」很奇怪 ——
        // 看起来像"有单位却读不出值"。无值时单位一起 GONE。
        val hasStart = started != 0L
        b.tvRideStartedAt.text = if (hasStart)
            START_CLOCK_FORMAT.format(java.util.Date(started)) else dash

        b.tvRideDuration.text = if (moving) formatDuration(tracker.rideMovingMs) else dash

        val tripMs = tracker.elapsedSinceStartMs()
        b.tvRideTripDuration.text = if (tripMs <= 0L) dash else formatDuration(tripMs)

        // ⭐ 均值保留 1 位小数：数值本身就小（十几km/h），
        // 取整会让不同骑行看起来几乎一样，失去参考价值。
        val avgS = tracker.avgSpeed
        val hasAvgS = avgS > 0f
        b.tvRideAvgSpeed.text = if (hasAvgS) String.format(Locale.US, "%.1f", avgS) else dash
        b.tvRideAvgSpeedUnit.visibility = if (hasAvgS) View.VISIBLE else View.GONE

        // 百公里耗能：里程太小时不计算（噪声大）
        val per100 = tracker.energyPer100Km
        val hasEnergy = per100 > 0f
        b.tvRideEnergyPer100.text = if (hasEnergy) String.format(Locale.US, "%.1f", per100) else dash
        b.tvRideEnergyPer100Unit.visibility = if (hasEnergy) View.VISIBLE else View.GONE

        val tripAvg = tracker.tripAvgSpeed
        val hasTripAvg = tripAvg > 0f
        b.tvRideTripAvgSpeed.text =
            if (hasTripAvg) String.format(Locale.US, "%.1f", tripAvg) else dash
        b.tvRideTripAvgSpeedUnit.visibility = if (hasTripAvg) View.VISIBLE else View.GONE

        // 预估里程（独占第一行大字）
        // ⚠️ 标签写「预估」而非直接报数：GPS 测速有误差、爬坡只测水平分量，
        // 精度约 ±10%。措辞上如实反映不确定性。
        val km = tracker.distanceKm
        val hasKm = km >= 0.005
        b.tvRideDistance.text = if (hasKm) String.format(Locale.US, "%.2f", km) else dash
        b.tvRideDistanceUnit.visibility = if (hasKm) View.VISIBLE else View.GONE

        // 预计可行驶里程 = 剩余能量 ÷ 百公里电耗。
        // ⚠️ 电耗需要足够里程才稳定（<10m 不计算），所以刚开始骑行时这里是占位符，
        // 属于预期行为 —— 与预估里程/均速同一套「没有有效值就不编数字」的规则。
        val range = tracker.estimatedRangeKm
        val hasRange = range > 0f
        b.tvRideEstimatedRange.text =
            if (hasRange) String.format(Locale.US, "%.1f", range) else dash
        b.tvRideEstimatedRangeUnit.visibility = if (hasRange) View.VISIBLE else View.GONE

        b.tvEmptyHint.visibility = if (recording) View.GONE else View.VISIBLE
    }

    /** 四项峰值：无记录时整行 GONE，不留空白占位 */
    private fun renderPeaks() {
        val b = _binding ?: return
        val recs = tracker.peakRecords

        bindPeak(b.rowPeakSpeed, b.tvPeakSpeed, recs[SessionSpeedTracker.CARD_SPEED], "km/h")
        bindPeak(b.rowPeakPower, b.tvPeakPower, recs[SessionSpeedTracker.CARD_POWER], "W")
        bindPeak(b.rowPeakCurrent, b.tvPeakCurrent, recs[SessionSpeedTracker.CARD_CURRENT], "A")

        val diff = recs[SessionSpeedTracker.CARD_DIFF]
        bindPeak(b.rowPeakDiff, b.tvPeakDiff, diff, "mV")
        // 压差详情**分四行**（用户要求：电流 / 最高 / 最低 各一行，
        // 另加一行标出「偏离最多的电芯」—— 一致性诊断）
        b.tvPeakDiffDetail.visibility = if (diff == null) View.GONE else View.VISIBLE
        if (diff != null) {
            b.tvPeakDiffDetail.text = buildString {
                append(getString(R.string.ride_peak_diff_current, formatCurrent(diff.current)))
                append('\n')
                append(
                    getString(
                        R.string.peak_detail_max_cell,
                        diff.maxCellIndex, diff.maxCellVolt,
                    )
                )
                append('\n')
                append(
                    getString(
                        R.string.peak_detail_min_cell,
                        diff.minCellIndex, diff.minCellVolt,
                    )
                )
                // ⭐ 只有能算出偏离电芯时才显示（少于 2 颗电芯无「均值」可言）
                if (diff.outlierCellIndex > 0) {
                    append('\n')
                    append(formatOutlierCell(diff))
                    // ⭐ 推测内阻差 = 偏离电压 ÷ 电流（用户要求：压差/电流）。
                    // outlierDeviation 单位是 V（formatOutlierCell 里 ×1000 显 mV），
                    // ÷ A → mΩ。电流太小（|I|<1A）时除数不稳，不显示这一行。
                    if (kotlin.math.abs(diff.current) >= 1f) {
                        append('\n')
                        append(
                            getString(
                                R.string.peak_detail_ir,
                                kotlin.math.abs(diff.outlierDeviation * 1000f / diff.current),
                            )
                        )
                    }
                }
            }
        }

        lastRevision = tracker.peakRevision
    }

    /**
     * 格式化「偏离最多」那一行。
     *
     * ⚠️ 偏离量以 **mV** 展示（×1000）：单体压差本就是几十毫伏级，
     * 用 V 显示会变成 `0.085V`，小数点后三位全是有效数字，反而难读。
     *
     * 偏离方向用「偏高 / 偏低」文字而非正负号 —— 中文语境更直观。
     */
    private fun formatOutlierCell(rec: SessionSpeedTracker.PeakRecord): String = getString(
        R.string.peak_detail_outlier_cell,
        rec.outlierCellIndex,
        getString(
            if (rec.outlierDeviation >= 0f) R.string.peak_outlier_high
            else R.string.peak_outlier_low
        ),
        Math.abs(rec.outlierDeviation * 1000f),
    )

    /**
     * 绑定一行的峰值。
     *
     * @param unit 单位（km/h / W / A / mV）
     */
    private fun bindPeak(
        row: View,
        valueView: android.widget.TextView,
        rec: SessionSpeedTracker.PeakRecord?,
        unit: String,
    ) {
        if (rec == null) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        valueView.text = getString(
            R.string.ride_peak_value_unit_fmt,
            formatPeakValue(rec.value),
            unit,
            rec.clock,
        )
    }

    /**
     * 峰值数值口径：**与实时页记录卡一致（取整）**。
     *
     * ⚠️ 压差别套用「电流负值 1 位小数」那套规则——
     * 那是展示**当前电流**的，读数在跳；峰值是稳定的一个数，取整更干净。
     */
    private fun formatPeakValue(v: Float): String =
        if (v < 0f) String.format(Locale.US, "%.1f", v)
        else v.toInt().toString()

    /** 压差详情里的电流：沿用「负值 1 位小数」口径（充电时 -0.x 要看得见） */
    private fun formatCurrent(v: Float): String =
        if (v < 0f) String.format(Locale.US, "%.1f", v)
        else v.toInt().toString()

    /**
     * 时长格式化为中文单位：`1 时 05 分 30 秒` / `12 分 54 秒`。
     *
     * ⚠️ 用「时 分 秒」文字而不是 `HH:mm:ss`：
     * 本页「开始时间」那一栏已经用冒号表示时刻，
     * 同页再出现冒号会让人分不清「这是时间点还是时长」。
     *
     * ⚠️ 秒/分**不补零**（「5 分 3 秒」而非「05 分 03 秒」）：
     * 中文语境下补零反而像时间戳。可读性优先。
     */
    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            getString(R.string.ride_duration_hms, h.toInt(), m.toInt(), s.toInt())
        } else {
            getString(R.string.ride_duration_ms, m.toInt(), s.toInt())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.root.removeCallbacks(ticker)
        _binding = null
    }

    companion object {
        private val START_CLOCK_FORMAT =
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

        /** 数据页时间卡：与首页时间卡同一格式（HH:mm，24 小时制） */
        private val CLOCK_FORMAT =
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    }
}
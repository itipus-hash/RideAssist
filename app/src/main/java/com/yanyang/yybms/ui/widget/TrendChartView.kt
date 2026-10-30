package com.yanyang.yybms.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.yanyang.yybms.R

/**
 * 简易折线图：SOC、总电压、总电流三条曲线叠加。
 *
 * 不引入三方图表库，纯 Canvas 实现，避免 APK 体积膨胀。
 * 曲线各自独立缩放（各自 max 归一化），共用横轴时间。
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var socData: FloatArray = FloatArray(0)
    private var voltageData: FloatArray = FloatArray(0)
    private var currentData: FloatArray = FloatArray(0)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.chart_grid)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    private val socColor = ContextCompat.getColor(context, R.color.chart_soc)
    private val voltageColor = ContextCompat.getColor(context, R.color.chart_voltage)
    private val currentColor = ContextCompat.getColor(context, R.color.chart_current)

    private val leftPadding = 16f
    private val rightPadding = 16f
    private val topPadding = 16f
    private val bottomPadding = 24f

    /**
     * **X 轴固定像素间距**（相邻两个采样点的水平距离）。
     *
     * ⭐ 这是「曲线永远只占一个屏幕、不往左推丢数据」的实现核心：
     * 固定间距意味着每来一个新点，曲线是**从右边向左生长**，
     * 旧点等距向左滚出画布 —— 而不是把整条曲线压缩重排
     * （后者会让曲线整体抖动，且看不出"滚动了多久"）。
     *
     * 取值 6dp：3 分钟 180 点 × 6dp = 1080dp，足够宽；
     * 在 360dp 宽的屏幕上可见约 60 点（1 分钟），符合实时页"看最近走势"的需求。
     */
    private val stepXPx = 6f * resources.displayMetrics.density

    /** 绘图区右边缘（最新点固定贴在这里） */
    private val chartRight: Float
        get() = width - rightPadding

    /** 单序列模式：只画一条线（如功率/压差），并显示最大值/最小值 */
    fun setSingleSeries(values: FloatArray) {
        socData = values
        voltageData = FloatArray(0)
        currentData = FloatArray(0)
        invalidate()
    }

    /** 序列内的最大值与最小值（空序列返回 null） */
    fun range(): Pair<Float, Float>? {
        if (socData.isEmpty()) return null
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in socData) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        return mn to mx
    }

    /**
     * 隐藏曲线（只留水印数值）。
     *
     * 用户需求：单击卡片任意位置隐藏/显示曲线。
     * 用 [android.view.View.INVISIBLE] 而非 GONE —— GONE 会让整个 110dp
     * 卡片高度塌陷、页面剧烈跳动；INVISIBLE 保留占位，布局稳定。
     */
    fun setCurveVisible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun isCurveVisible(): Boolean = visibility == View.VISIBLE

    fun setData(soc: List<Float>, voltage: List<Float>, current: List<Float>) {
        socData = soc.toFloatArray()
        voltageData = voltage.toFloatArray()
        currentData = current.toFloatArray()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val chartWidth = width - leftPadding - rightPadding
        val chartHeight = height - topPadding - bottomPadding
        if (chartWidth <= 0 || chartHeight <= 0) return

        drawGrid(canvas, chartWidth, chartHeight)

        drawSeries(canvas, socData, socColor, chartWidth, chartHeight)
        drawSeries(canvas, voltageData, voltageColor, chartWidth, chartHeight)
        drawSeries(canvas, currentData, currentColor, chartWidth, chartHeight)
    }

    /** 背景网格：横向 4 等分，方便读数 */
    private fun drawGrid(canvas: Canvas, chartWidth: Float, chartHeight: Float) {
        val lines = 4
        for (i in 0..lines) {
            val y = topPadding + chartHeight / lines * i
            canvas.drawLine(leftPadding, y, leftPadding + chartWidth, y, axisPaint)
        }
    }

    /**
     * 绘制一条曲线。数据各自归一化到 [0,1]，超出部分裁剪。
     */
    private fun drawSeries(
        canvas: Canvas,
        data: FloatArray,
        color: Int,
        chartWidth: Float,
        chartHeight: Float
    ) {
        if (data.size < 2) return

        // 电流有正负：以 0 为中心，范围取 max(|min|,|max|) 保证正负对称
        val maxAbs = data.maxOf { kotlin.math.abs(it) }
        val minVal = data.min()
        val maxVal = data.max()

        val hasNegative = minVal < 0f
        val upper = if (hasNegative) maxAbs else (maxVal.takeIf { it > 0f } ?: 1f)
        val lower = if (hasNegative) -maxAbs else 0f
        val range = (upper - lower).takeIf { it > 0f } ?: 1f

        linePaint.color = color
        val path = Path()

        // ⭐⭐ **固定像素间距 + 从左侧裁剪**（用户要求："曲线永远只占一个屏幕，
        // 不要将曲线往左推导致丢失，骑行久了要压缩曲线 X 轴"）。
        //
        // 旧实现 `stepX = chartWidth / (data.size - 1)` 有两个问题：
        //   1. 点数每加一个，所有点的 x 都会变 → 整条曲线左右抖动
        //   2. 超出屏幕宽度的点被画到画布外 = 数据"丢失"
        //
        // 现在固定 stepXPx：点数少时曲线只占左侧一段（从左往右生长），
        // 超过可视容量时**只裁掉最左侧**，最新数据始终贴在右边缘，
        // 相当于"镜头跟随"，历史数据以固定密度向左滚出画面。
        val stepX = stepXPx
        // 容量 = 最多能放下多少个点（至少 2，否则画不出线）
        val capacity = (chartWidth / stepX).toInt().coerceAtLeast(2)
        // ⭐ 只保留**最新**的 capacity 个点
        val startIndex = (data.size - capacity).coerceAtLeast(0)
        val visibleCount = data.size - startIndex

        // ⭐⭐ **锚点必须随点数切换**（这是实测踩到的坑）：
        //  - 点数**不足**容量（新数据刚开始）→ 锚定**左边缘**，从左往右生长。
        //    否则唯一点会贴在右边缘，看起来"曲线丢了大半"。
        //  - 点数**超过**容量 → 锚定**右边缘**，旧点等距向左滚出。
        val anchorRight = visibleCount >= capacity
        val endX = if (anchorRight) chartRight else leftPadding + stepX * (visibleCount - 1)

        for (i in 0 until visibleCount) {
            val index = startIndex + i
            val value = data[index]
            val x = endX - stepX * (visibleCount - 1 - i)
            val ratio = ((value - lower) / range).coerceIn(0f, 1f)
            val y = topPadding + chartHeight * (1f - ratio)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        // 末端点标记当前值 —— 锚在曲线的实际末端
        val lastValue = data.last()
        val lastRatio = ((lastValue - lower) / range).coerceIn(0f, 1f)
        val lastY = topPadding + chartHeight * (1f - lastRatio)
        linePaint.style = Paint.Style.FILL
        canvas.drawCircle(endX, lastY, 6f, linePaint)
        linePaint.style = Paint.Style.STROKE
    }
}
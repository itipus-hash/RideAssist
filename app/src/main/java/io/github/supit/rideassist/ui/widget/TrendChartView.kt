package io.github.supit.rideassist.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.github.supit.rideassist.R

/**
 * 简易折线图：SOC、总电压、总电流三条曲线叠加。
 *
 * 不引入三方图表库，纯 Canvas 实现，避免 APK 体积膨胀。
 * 曲线各自独立缩放（各自 max 归一化），共用横轴时间。
 *
 * ## 两种绘制模式
 * - **实时模式**（[setSingleSeries]）：固定 6dp 步长，新点从右进、旧点匀速左移，
 *   最新点贴右边缘；超出容量的点只裁掉绘制，不丢数据（数据在 Tracker 里）。
 * - **历史模式**（[setHistorySeries]）：全量数据铺满整块画布，X 轴按索引线性映射，
 *   **不滚动**。用于「双击切到历史曲线」看整段行程趋势。
 *
 * ⭐ 历史模式用**沿画布 Y 轴的 LinearGradient** 给描边和填充上色：
 * 曲线高处自然橙红、低处自然青绿。颜色与数据值无关，
 * 是 shader 坐标固定在画布上带来的效果 —— 不需要逐点算色，
 * 也不会因为采样点密度变化而出现色带断裂。
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 历史渐变的色标位置，必须与 colors.xml 里四个颜色的顺序对齐 */
        private val GRAD_STOPS = floatArrayOf(0f, 0.45f, 0.72f, 1f)

        /** 曲线描边宽度（dp）：实时与历史共用，保证两种模式观感一致 */
        private const val CURVE_STROKE_DP = 2.5f

        /** 历史曲线填充透明度：日间 / 夜间 */
        private const val FILL_ALPHA_DAY = 0.22f
        private const val FILL_ALPHA_NIGHT = 0.18f
    }

    private var socData: FloatArray = FloatArray(0)
    private var voltageData: FloatArray = FloatArray(0)
    private var currentData: FloatArray = FloatArray(0)

    /** 历史模式的全量数据（已做过 min/max 包络压缩） */
    private var historyData: FloatArray = FloatArray(0)
    private var historyMode = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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

    /** 历史渐变资源色（夜间由 values-night/colors.xml 覆盖） */
    private var gradTop = ContextCompat.getColor(context, R.color.chart_grad_top)
    private var gradMid1 = ContextCompat.getColor(context, R.color.chart_grad_mid1)
    private var gradMid2 = ContextCompat.getColor(context, R.color.chart_grad_mid2)
    private var gradBottom = ContextCompat.getColor(context, R.color.chart_grad_bottom)

    /** 渐变 shader 缓存：key = top|bottom|night，尺寸或主题变化才重建 */
    private var strokeShader: LinearGradient? = null
    private var fillShader: LinearGradient? = null
    private var shaderKey: String = ""

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
     */
    private val stepXPx = 6f * resources.displayMetrics.density

    /** 绘图区右边缘（最新点固定贴在这里） */
    private val chartRight: Float
        get() = width - rightPadding

    /** 单序列模式（实时）：只画一条线，固定步长滚动 */
    fun setSingleSeries(values: FloatArray) {
        historyMode = false
        historyData = FloatArray(0)
        socData = values
        voltageData = FloatArray(0)
        currentData = FloatArray(0)
        invalidate()
    }

    /**
     * 历史模式：把**全量**数据铺满整块画布，并按 Y 向渐变上色。
     *
     * ⚠️ 与 [setSingleSeries] 互斥 —— 调用这个之后，实时采样必须停止喂数据，
     * 否则会被立刻覆盖回滚动模式。
     */
    fun setHistorySeries(values: FloatArray) {
        historyMode = true
        historyData = values
        socData = FloatArray(0)
        voltageData = FloatArray(0)
        currentData = FloatArray(0)
        invalidate()
    }

    /** 序列内的最大值与最小值（空序列返回 null） */
    fun range(): Pair<Float, Float>? {
        val data = if (historyMode) historyData else socData
        if (data.isEmpty()) return null
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in data) {
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
        historyMode = false
        historyData = FloatArray(0)
        socData = soc.toFloatArray()
        voltageData = voltage.toFloatArray()
        currentData = current.toFloatArray()
        invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        // 夜间开关若没有重建 Activity（声明了 configChanges），
        // 资源色不会自动刷新 —— 这里主动重取并作废 shader 缓存
        gradTop = ContextCompat.getColor(context, R.color.chart_grad_top)
        gradMid1 = ContextCompat.getColor(context, R.color.chart_grad_mid1)
        gradMid2 = ContextCompat.getColor(context, R.color.chart_grad_mid2)
        gradBottom = ContextCompat.getColor(context, R.color.chart_grad_bottom)
        axisPaint.color = ContextCompat.getColor(context, R.color.chart_grid)
        shaderKey = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val chartWidth = width - leftPadding - rightPadding
        val chartHeight = height - topPadding - bottomPadding
        if (chartWidth <= 0 || chartHeight <= 0) return

        drawGrid(canvas, chartWidth, chartHeight)

        if (historyMode) {
            drawHistorySeries(canvas, chartWidth, chartHeight)
            return
        }

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

        // ⭐ 实时曲线也用与历史模式**同一套 Y 向渐变**（用户要求「实时曲线也改成一样的彩色」）：
        // 高处暖色、低处冷色。shader 坐标固定在画布上，所以颜色只跟画到哪个高度有关，
        // 与数据值、采样密度都无关 —— 不会出现某一帧突然整条变色。
        // ⚠️ `color` 参数只在没有 shader 时兜底（例如将来要画纯色辅助线）。
        val shaderTop = topPadding
        val shaderBottom = topPadding + chartHeight
        ensureShaders(shaderTop, shaderBottom)
        linePaint.color = color
        linePaint.shader = strokeShader
        // 与历史模式同一粗细：以前这里用 4px（约 1.3dp），比历史线细一半
        linePaint.strokeWidth = CURVE_STROKE_DP * resources.displayMetrics.density
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
        val firstX = endX - stepX * (visibleCount - 1)

        for (i in 0 until visibleCount) {
            val index = startIndex + i
            val value = data[index]
            val x = endX - stepX * (visibleCount - 1 - i)
            val ratio = ((value - lower) / range).coerceIn(0f, 1f)
            val y = topPadding + chartHeight * (1f - ratio)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // ① 填充：与历史模式同样的闭合方式（沿曲线 → 垂直落到底线 → 回起点）
        val fillPath = Path(path)
        fillPath.lineTo(endX, shaderBottom)
        fillPath.lineTo(firstX, shaderBottom)
        fillPath.close()
        fillPaint.shader = fillShader
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null

        // ② 描边
        canvas.drawPath(path, linePaint)

        // 末端点标记当前值 —— 锚在曲线的实际末端（FILL + shader 会自动取该高度颜色）
        val lastValue = data.last()
        val lastRatio = ((lastValue - lower) / range).coerceIn(0f, 1f)
        val lastY = topPadding + chartHeight * (1f - lastRatio)
        linePaint.style = Paint.Style.FILL
        canvas.drawCircle(endX, lastY, 6f, linePaint)
        linePaint.style = Paint.Style.STROKE
        linePaint.shader = null
    }

    /**
     * 历史模式：全量数据铺满画布 + Y 向渐变描边与填充。
     *
     * X 轴按**索引**线性映射（不是时间轴，采样间隔不均匀也不会有空洞），
     * Y 轴把数据的 min..max 拉伸到整块绘图区 —— 于是渐变上端恰好是最大值、
     * 下端恰好是最小值，颜色与数值严格对应。
     */
    private fun drawHistorySeries(canvas: Canvas, chartWidth: Float, chartHeight: Float) {
        val data = historyData
        if (data.size < 2) return

        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in data) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val top = topPadding
        val bottom = topPadding + chartHeight
        val stepX = chartWidth / (data.size - 1).toFloat()

        // ⭐ Y 映射的判据在 HistoryCurve（纯函数 + 单测）：
        // ratio=1 是最大值 → 画布顶部 → shader 0f 位置 → 暖色（橙红）
        // ratio=0 是最小值 → 画布底部 → shader 1f 位置 → 冷色（青绿）
        val line = Path()
        for (i in data.indices) {
            val x = leftPadding + stepX * i
            val ratio = io.github.supit.rideassist.util.HistoryCurve
                .verticalRatio(data[i], mn, mx)
            val y = bottom - chartHeight * ratio
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }

        ensureShaders(top, bottom)

        // ① 填充：沿曲线走一遍，再落到底边闭合
        val fill = Path(line)
        fill.lineTo(leftPadding + chartWidth, bottom)
        fill.lineTo(leftPadding, bottom)
        fill.close()
        fillPaint.shader = fillShader
        canvas.drawPath(fill, fillPaint)

        // ② 描边（shader 沿画布 Y 固定 → 高处橙红、低处青绿）
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = CURVE_STROKE_DP * resources.displayMetrics.density
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.strokeJoin = Paint.Join.ROUND
        linePaint.shader = strokeShader
        canvas.drawPath(line, linePaint)

        // ③ 末端点
        val lastRatio = io.github.supit.rideassist.util.HistoryCurve
            .verticalRatio(data.last(), mn, mx)
        val lastY = bottom - chartHeight * lastRatio
        linePaint.style = Paint.Style.FILL
        canvas.drawCircle(
            leftPadding + chartWidth, lastY,
            5f * resources.displayMetrics.density, linePaint
        )

        // 恢复默认，下一条曲线（或切回实时模式）不受影响
        linePaint.style = Paint.Style.STROKE
        linePaint.shader = null
        linePaint.strokeWidth = 4f
        fillPaint.shader = null
    }

    private fun ensureShaders(top: Float, bottom: Float) {
        val night = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val key = "$top|$bottom|$night"
        if (key == shaderKey && strokeShader != null && fillShader != null) return

        val colors = intArrayOf(gradTop, gradMid1, gradMid2, gradBottom)
        strokeShader = LinearGradient(
            0f, top, 0f, bottom, colors, GRAD_STOPS, Shader.TileMode.CLAMP
        )
        val alpha = if (night) FILL_ALPHA_NIGHT else FILL_ALPHA_DAY
        val fillColors = IntArray(colors.size) {
            ColorUtils.setAlphaComponent(colors[it], (alpha * 255).toInt())
        }
        fillShader = LinearGradient(
            0f, top, 0f, bottom, fillColors, GRAD_STOPS, Shader.TileMode.CLAMP
        )
        shaderKey = key
    }
}

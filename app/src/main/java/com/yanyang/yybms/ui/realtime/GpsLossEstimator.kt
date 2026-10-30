package com.yanyang.yybms.ui.realtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * **GPS 信号丢失时的速度推算器**。
 *
 * ## 为什么需要
 * 车辆行驶中 GPS 常因隧道、高架、树荫失锁。若直接显示 0 或"--"，
 * 会让曲线突然掉到 0 再跳回，用户以为车停了。
 *
 * ## 推算原理（惯性推算 / dead reckoning）
 * 用**加速度计**测量速度变化量：
 * ```
 * v(t) = v(t₀) + ∫a dt
 * ```
 * 每秒调用一次 [update]，用「当前合加速度在**车头方向**上的投影」作为 dv：
 * - 车头方向由首次有效 GPS 定位的**方位角**（bearing）确定，锁定后不变
 * - 投影公式：`dv = (ax·sinθ + ay·cosθ) · dt`（θ 为车头方位角）
 *
 * ## 已知局限（重要）
 * 惯性推算**误差累积很快**，主要来源：
 * - 加速度计零偏（bias），静止时也不为 0
 * - 姿态变化（转弯、上下坡）引入的投影误差
 * - 长时间积分必然发散
 *
 * 因此本类做了三件事：
 * 1. 丢失 60 秒后**停止推算**，改用「保持丢失前速度」（更诚实）
 * 2. 检测到明显的减速（推算速度 < 1km/h）就归零——大概率是真的停了
 * 3. 推算值**只用于显示，不写回曲线**，避免污染采样数据
 *
 * @param context 用于获取 SensorManager
 */
class GpsLossEstimator(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 是否已注册传感器 */
    private var registered = false

    // ===== 推算状态 =====

    /** 丢失瞬间的速度（km/h），推算的起点 */
    private var baseSpeed = 0f

    /** 推算起始时刻 */
    private var startAtMs = 0L

    /** 车头方位角（弧度，0=正北，顺时针），由 GPS bearing 锁定 */
    private var headingRad = 0f

    /** 是否已锁定车头方向 */
    private var headingLocked = false

    /** 上一次 [update] 的时刻 */
    private var lastUpdateMs = 0L

    /** 最近一次合加速度（m/s²，含重力），固定长度 3 */
    private val lastAccel = FloatArray(3)

    /**
     * 推算出的速度（km/h）。
     * 调用 [update] 或 [reset] 前是「保持值」。
     */
    var estimatedSpeed: Float = 0f
        private set

    /**
     * 当前是否正在用陀螺仪推算（而非保持丢失前速度）。
     */
    val isEstimating: Boolean
        get() = estimatedSpeed > 0f && !hasStopped

    /** 是否已判定车辆停止（推算速度趋零） */
    private var hasStopped = false

    /** 丢失持续的时长（毫秒），由外部的丢失检测维护 */
    var lostDurationMs: Long = 0L
        private set

    /** 开始注册传感器。返回是否成功。 */
    fun start(): Boolean {
        if (registered) return true
        val sm = sensorManager ?: return false
        val sensor = accelSensor ?: return false
        registered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        return registered
    }

    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
    }

    /**
     * GPS **失锁**时调用：开始一次推算。
     *
     * @param lastValidSpeed 丢失前最后一个有效速度（km/h）
     * @param headingDegrees 车头方位角（度，来自 GPS bearing）；无则传 -1
     * @param lostStartMs 失锁时刻（[SystemClock.elapsedRealtime]）
     */
    fun onGpsLost(lastValidSpeed: Float, headingDegrees: Float, lostStartMs: Long) {
        baseSpeed = lastValidSpeed
        startSpeed = lastValidSpeed
        estimatedSpeed = lastValidSpeed
        startAtMs = lostStartMs
        lastUpdateMs = lostStartMs
        lostDurationMs = 0L
        hasStopped = false
        if (headingDegrees >= 0f) {
            headingRad = Math.toRadians(headingDegrees.toDouble()).toFloat()
            headingLocked = true
        }
        start()
    }

    /** GPS **恢复**时调用：结束推算，回到真实数据。 */
    fun onGpsRecovered() {
        stop()
        estimatedSpeed = 0f
        baseSpeed = 0f
        hasStopped = false
        headingLocked = false
        lastUpdateMs = 0L
        startAtMs = 0L
        lostDurationMs = 0L
    }

    /**
     * 周期性推进推算（建议 1Hz，与 GPS 采样同频）。
     *
     * @param nowMs 当前时刻
     */
    fun update(nowMs: Long) {
        if (lastUpdateMs == 0L) {
            lastUpdateMs = nowMs
            return
        }
        val dtSec = (nowMs - lastUpdateMs) / 1000f
        lastUpdateMs = nowMs
        lostDurationMs = nowMs - startAtMs

        // ⭐ 超过 60 秒：误差已大到没意义，改为「保持丢失前速度」
        if (lostDurationMs >= MAX_ESTIMATE_MS) {
            estimatedSpeed = if (hasStopped) 0f else baseSpeed
            return
        }
        if (hasStopped || dtSec <= 0f || dtSec > MAX_DT_SEC || !headingLocked) {
            // ⚠️ 没有车头方向就无法做投影积分，只能保持丢失前速度
            estimatedSpeed = if (hasStopped) 0f else baseSpeed
            return
        }

        // 沿车头方向的加速度投影（去重力：传感器值含 g，取水平分量）
        val ax = lastAccel[0].toDouble()
        val ay = lastAccel[1].toDouble()
        val sinH = Math.sin(headingRad.toDouble())
        val cosH = Math.cos(headingRad.toDouble())
        val dvMps = (ax * sinH + ay * cosH).toFloat() * dtSec

        baseSpeed += dvMps * 3.6f      // m/s → km/h

        // 明显减速 → 判定停止（大概率真停了，不是丢失）
        if (baseSpeed < STOP_THRESHOLD_KMH) {
            baseSpeed = 0f
            hasStopped = true
        }
        // 上限保护：推算不可能超过丢失前太多
        if (baseSpeed > baseSpeedCeiling()) baseSpeed = baseSpeedCeiling()
        estimatedSpeed = baseSpeed
    }

    /** 推算速度上限（丢失前速度 + 30km/h），防止积分发散成荒谬值 */
    private fun baseSpeedCeiling(): Float = (startSpeed + 30f).coerceAtLeast(35f)

    /** 记录起始速度，供上限计算用 */
    private var startSpeed = 0f

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        //⚠️ 用copyOfRange 而非 event.values.clone()：
        // 后者在部分设备上返回的是长度不定的数组，会让 lastAccel 索引越界。
        val v = event.values
        val n = minOf(v.size, lastAccel.size)
        System.arraycopy(v, 0, lastAccel, 0, n)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /**
         * 推算最长时长：**60 秒**。
         *
         * 超过就改为「保持丢失前速度」——因为惯性推算的误差在 1 分钟后
         * 可能比原值大得多，显示一个假的数字比显示"保持"更糟。
         */
        const val MAX_ESTIMATE_MS = 60_000L

        /** 单次积分允许的最大 dt（秒），超过视为断档不积分 */
        const val MAX_DT_SEC = 3f

        /** 推算速度低于此值判定为「已停止」（km/h） */
        const val STOP_THRESHOLD_KMH = 1.0f
    }
}

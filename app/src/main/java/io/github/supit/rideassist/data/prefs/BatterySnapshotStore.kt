package io.github.supit.rideassist.data.prefs

import io.github.supit.rideassist.core.protocol.model.BatterySnapshot
import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData

/**
 * 电池实况快照的本地持久化。
 *
 * 写入**节流**：实时帧 1~3Hz，每帧写盘太浪费；30 秒落一次足够 ——
 * 用户看到的是"断开前几十秒内的数据"，对展示用途完全够。
 */
object BatterySnapshotStore {

    /** 落盘节流间隔 */
    private const val MIN_SAVE_INTERVAL_MS = 30_000L

    fun load(cache: DeviceCachePrefs): BatterySnapshot? =
        BatterySnapshot.decode(cache.batterySnapshotRaw)

    /**
     * 有数据就更新快照（节流写盘）。
     *
     * @return true = 本次真的写了盘
     */
    fun saveThrottled(
        cache: DeviceCachePrefs,
        d: BmsRealtimeData,
        nowMs: Long,
    ): Boolean {
        if (!d.hasDeviceData) return false
        val last = load(cache)?.savedAtMs ?: 0L
        if (nowMs - last < MIN_SAVE_INTERVAL_MS) return false
        cache.batterySnapshotRaw = BatterySnapshot.of(d, nowMs).encode()
        return true
    }
}

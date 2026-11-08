package io.github.supit.rideassist.data.prefs

import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData
import io.github.supit.rideassist.core.protocol.model.DeviceProfile

/**
 * 保护板静态身份信息（型号 / 版本 / SN / 电池信息）的本地持久化。
 *
 * 除了「没连板子也能看设备信息」，它还负责**换板子检测**：
 * 阈值缓存是纯数值、看不出属于哪块板子，换板子后旧阈值会被误用。
 * 这里比较型号，不一致就把阈值缓存一起作废，等下一条 0xA0 帧重新读。
 */
object DeviceProfileStore {

    fun load(cache: DeviceCachePrefs): DeviceProfile? = DeviceProfile.decode(cache.deviceProfileRaw)

    fun save(cache: DeviceCachePrefs, profile: DeviceProfile) {
        cache.deviceProfileRaw = profile.encode()
    }

    /**
     * 用当前实时数据同步缓存。
     *
     * @return true = **检测到换了保护板**，已作废旧阈值缓存
     */
    fun syncOrInvalidate(cache: DeviceCachePrefs, d: BmsRealtimeData, nowMs: Long): Boolean {
        val fresh = DeviceProfile.of(d, nowMs)
        // 还没读到型号（设备信息帧未到）→ 不动缓存，避免把好缓存冲成空
        if (fresh.productName.isBlank()) return false

        val cached = load(cache)
        if (DeviceProfile.isDifferentBoard(cached, fresh)) {
            // ⭐ 换板子：旧板子的阈值对新板子毫无意义，一起作废
            //    （新 profile 是残缺的也没关系，设备信息帧到齐后下次连接会补全）
            cache.protectParamsRaw = ""
            save(cache, fresh)
            return true
        }
        // ⭐ 字段级合并：新值缺失（0/-1/空）的字段用旧缓存补上，
        //    避免设备信息帧分帧到达时把完整缓存抹残
        val merged = fresh.mergedWith(cached)
        if (cached == null || cached.differsFrom(merged)) save(cache, merged)
        return false
    }
}

package io.github.supit.rideassist.data.prefs

import io.github.supit.rideassist.core.protocol.model.ProtectThresholds

/**
 * 保护板阈值的本地持久化（SharedPreferences 薄封装）。
 *
 * 逻辑都在 [ProtectThresholds]（纯 JVM、可单测）：编码、解码、差异比较、注入。
 * 这里只负责「读一次字符串 / 写一次字符串」，不做业务判断 ——
 * 避免出现"两套判据"。
 */
object ProtectParamsStore {

    /** 读出本地缓存的阈值；没有或格式不合法时返回 null */
    fun load(prefs: AppPrefs): ProtectThresholds? =
        ProtectThresholds.decode(prefs.protectParamsRaw)

    /**
     * 与本地缓存比较，**只有阈值真的变化了才写盘**。
     *
     * 用户明确要求：「如果后续读取到保护板有变化，就覆盖阈值，但是不要反复读取」。
     * 所以这里既控制写入频率（同值不写），也顺带保证不会因为每 5 分钟
     * 校准一次就把 `savedAtMs` 刷成新时间（否则诊断面板上的"缓存于何时"永远是刚刚）。
     *
     * @return true = 确实写入了新阈值
     */
    fun saveIfChanged(prefs: AppPrefs, thresholds: ProtectThresholds): Boolean {
        val cached = load(prefs)
        if (cached != null && !thresholds.differsFrom(cached)) return false
        prefs.protectParamsRaw = thresholds.encode()
        return true
    }
}

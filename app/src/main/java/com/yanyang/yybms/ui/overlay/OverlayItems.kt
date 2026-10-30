package com.yanyang.yybms.ui.overlay

import com.yanyang.yybms.R
import com.yanyang.yybms.data.prefs.AppPrefs

/**
 * ⭐ 悬浮项目注册表 —— 单一事实来源：
 * 悬浮显示页的行、悬浮框的渲染、排序，全部由此驱动。新增项目只需在此加一条。
 */
object OverlayItems {

    data class Def(
        val key: String,
        val labelRes: Int,
        /** ⭐ 悬浮窗显示名（与设置页开关名分离，用户要求两套名称） */
        val displayLabelRes: Int,
        val getShow: (AppPrefs) -> Boolean,
        val setShow: (AppPrefs, Boolean) -> Unit,
        val getSize: (AppPrefs) -> Int,
        val setSize: (AppPrefs, Int) -> Unit,
    )

    val ALL = listOf(
        Def("tpmsf", R.string.overlay_item_tpms_front,
            R.string.overlay_display_tpms_front,
            { it.overlayShowTpmsFront }, { p, v -> p.overlayShowTpmsFront = v },
            { it.overlaySizeTpmsFront }, { p, v -> p.overlaySizeTpmsFront = v }),
        Def("tpmsr", R.string.overlay_item_tpms_rear,
            R.string.overlay_display_tpms_rear,
            { it.overlayShowTpmsRear }, { p, v -> p.overlayShowTpmsRear = v },
            { it.overlaySizeTpmsRear }, { p, v -> p.overlaySizeTpmsRear = v }),
        Def("battery", R.string.overlay_item_battery,
            R.string.overlay_display_battery,
            { it.overlayShowBattery }, { p, v -> p.overlayShowBattery = v },
            { it.overlaySizeBattery }, { p, v -> p.overlaySizeBattery = v }),
        Def("volts", R.string.overlay_item_volts,
            R.string.overlay_display_volts,
            { it.overlayShowVolts }, { p, v -> p.overlayShowVolts = v },
            { it.overlaySizeVolts }, { p, v -> p.overlaySizeVolts = v }),
        Def("ampslive", R.string.overlay_item_amps_live,
            R.string.overlay_display_amps_live,
            { it.overlayShowAmpsLive }, { p, v -> p.overlayShowAmpsLive = v },
            { it.overlaySizeAmpsLive }, { p, v -> p.overlaySizeAmpsLive = v }),
        Def("powerlive", R.string.overlay_item_power_live,
            R.string.overlay_display_power_live,
            { it.overlayShowPowerLive }, { p, v -> p.overlayShowPowerLive = v },
            { it.overlaySizePowerLive }, { p, v -> p.overlaySizePowerLive = v }),
        Def("maxspeed", R.string.overlay_item_max_speed,
            R.string.overlay_display_max_speed,
            { it.overlayShowMaxSpeed }, { p, v -> p.overlayShowMaxSpeed = v },
            { it.overlaySizeMaxSpeed }, { p, v -> p.overlaySizeMaxSpeed = v }),
        Def("amps", R.string.overlay_item_amps,
            R.string.overlay_display_amps,
            { it.overlayShowAmps }, { p, v -> p.overlayShowAmps = v },
            { it.overlaySizeAmps }, { p, v -> p.overlaySizeAmps = v }),
        Def("power", R.string.overlay_item_power,
            R.string.overlay_display_power,
            { it.overlayShowPower }, { p, v -> p.overlayShowPower = v },
            { it.overlaySizePower }, { p, v -> p.overlaySizePower = v }),
        Def("mostemp", R.string.overlay_item_mos_temp,
            R.string.overlay_display_mostemp,
            { it.overlayShowMosTemp }, { p, v -> p.overlayShowMosTemp = v },
            { it.overlaySizeMosTemp }, { p, v -> p.overlaySizeMosTemp = v }),
        Def("difflive", R.string.overlay_item_diff_live,
            R.string.overlay_display_diff_live,
            { it.overlayShowDiffLive }, { p, v -> p.overlayShowDiffLive = v },
            { it.overlaySizeDiffLive }, { p, v -> p.overlaySizeDiffLive = v }),
        Def("diffmax", R.string.overlay_item_diff_max,
            R.string.overlay_display_diff_max,
            { it.overlayShowDiffMax }, { p, v -> p.overlayShowDiffMax = v },
            { it.overlaySizeDiffMax }, { p, v -> p.overlaySizeDiffMax = v }),
    )

    /** 按 prefs 里的排序串返回有序项目（未入列的按注册顺序补在尾部） */
    fun ordered(prefs: AppPrefs): List<Def> {
        val keys = prefs.overlayOrder.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val result = keys.mapNotNull { key -> ALL.find { it.key == key } }.toMutableList()
        ALL.forEach { def -> if (result.none { it.key == def.key }) result.add(def) }
        return result
    }

    fun saveOrder(prefs: AppPrefs, keys: List<String>) {
        prefs.overlayOrder = keys.joinToString(",")
    }
}

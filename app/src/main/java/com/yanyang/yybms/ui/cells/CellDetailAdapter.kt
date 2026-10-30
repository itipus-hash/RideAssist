package com.yanyang.yybms.ui.cells

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.yanyang.yybms.R
import com.yanyang.yybms.databinding.ItemCellDetailBinding

/**
 * 单体电芯明细列表：序号、电压、与最低串的差值、柱状条。
 * 超过压差阈值的串标红。
 */
class CellDetailAdapter : RecyclerView.Adapter<CellDetailAdapter.VH>() {

    private var voltages: List<Float> = emptyList()
    private var minVoltage: Float = 0f

    /** 与监控页一致 */
    private val diffThresholdMv = 80f

    fun submit(newList: List<Float>) {
        voltages = newList
        minVoltage = newList.minOrNull() ?: 0f
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemCellDetailBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCellDetailBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = voltages.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = voltages[position]
        val diffMv = (v - minVoltage) * 1000f
        val abnormal = diffMv > diffThresholdMv
        val colorRes = if (abnormal) R.color.abnormal else R.color.text_primary

        with(holder.binding) {
            tvIndex.text = (position + 1).toString()
            tvVoltage.text = "%.3f".format(v)
            tvDiff.text = if (position == 0 || diffMv < 0.5f) {
                "基准"
            } else {
                "+%.0fmV".format(diffMv)
            }
            tvVoltage.setTextColor(ContextCompat.getColor(root.context, colorRes))
            tvDiff.setTextColor(ContextCompat.getColor(root.context, colorRes))

            // 柱状条：以该串电压相对最小值的富余度作为长度
            val span = (voltages.maxOrNull() ?: minVoltage) - minVoltage
            val ratio = if (span <= 0f) 1f else ((v - minVoltage) / span).coerceIn(0.08f, 1f)
            bar.setProgress((ratio * 100).toInt())
            bar.setIndicatorColor(ContextCompat.getColor(root.context, colorRes))
        }
    }
}
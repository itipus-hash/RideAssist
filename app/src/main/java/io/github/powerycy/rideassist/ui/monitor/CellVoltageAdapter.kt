package io.github.powerycy.rideassist.ui.monitor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.powerycy.rideassist.R
import io.github.powerycy.rideassist.databinding.ItemCellBinding

/**
 * 单体电芯电压横向列表。自适应 4~24 串。
 * 压差超标时整体标红。
 */
class CellVoltageAdapter : RecyclerView.Adapter<CellVoltageAdapter.VH>() {

    private var voltages: List<Float> = emptyList()
    private var diffAbnormal: Boolean = false

    fun submit(newList: List<Float>) {
        voltages = newList
        notifyDataSetChanged()
    }

    fun setDiffAbnormal(abnormal: Boolean) {
        if (diffAbnormal != abnormal) {
            diffAbnormal = abnormal
            notifyDataSetChanged()
        }
    }

    inner class VH(val binding: ItemCellBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCellBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = voltages.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = voltages[position]
        holder.binding.tvIndex.text = (position + 1).toString()
        holder.binding.tvValue.text = "%.3f".format(v)
        holder.binding.tvValue.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (diffAbnormal) R.color.abnormal else R.color.text_primary
            )
        )
    }
}
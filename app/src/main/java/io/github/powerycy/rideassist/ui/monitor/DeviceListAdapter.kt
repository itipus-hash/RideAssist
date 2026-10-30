package io.github.powerycy.rideassist.ui.monitor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.powerycy.rideassist.core.ble.ScanDevice
import io.github.powerycy.rideassist.databinding.ItemDeviceBinding

/**
 * 扫描设备列表。彦阳设备优先排在前面。
 */
class DeviceListAdapter(
    private val onClick: (ScanDevice) -> Unit
) : ListAdapter<ScanDevice, DeviceListAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScanDevice>() {
            override fun areItemsTheSame(a: ScanDevice, b: ScanDevice) =
                a.macAddress == b.macAddress

            override fun areContentsTheSame(a: ScanDevice, b: ScanDevice) = a == b
        }
    }

    inner class VH(val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = getItem(position)
        with(holder.binding) {
            tvName.text = device.displayName
            tvMac.text = device.macAddress
            tvRssi.text = device.rssiText
            // 彦阳设备打标签，方便识别
            tvTag.visibility = if (device.isYanYang) android.view.View.VISIBLE
            else android.view.View.GONE
            root.setOnClickListener { onClick(device) }
        }
    }
}
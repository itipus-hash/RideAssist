package io.github.supit.rideassist.ui.cells

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.supit.rideassist.R
import io.github.supit.rideassist.YybmsAppHolder
import io.github.supit.rideassist.core.protocol.model.BmsRealtimeData
import io.github.supit.rideassist.databinding.FragmentCellDetailBinding
import kotlinx.coroutines.launch

/**
 * 单体压差详情页。
 *
 * 展示每一串电芯电压、极值与压差判定，数据随 BLE 轮询实时刷新（1 秒）。
 * 页面**常亮显示**，方便现场长时间观察各串一致性。
 */
class CellDetailFragment : Fragment() {

    private var _binding: FragmentCellDetailBinding? = null
    private val binding get() = _binding!!

    // 用 val 字段初始化而非 lateinit，避免"声明了忘实例化"导致运行期崩溃
    private val adapter = CellDetailAdapter()

    companion object {
        /** 压差告警阈值 (mV)，与监控页保持一致 */
        private const val DIFF_THRESHOLD_MV = 80f

        fun newInstance(): CellDetailFragment = CellDetailFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCellDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 常亮：现场需长时间观察各串一致性，屏幕不能自动熄灭
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.rvCellDetail.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCellDetail.adapter = adapter

        observeRealtime()
    }

    /** 订阅应用级仓库，实现实时刷新 */
    private fun observeRealtime() {
        val repo = YybmsAppHolder.from(requireContext()).monitorRepository
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.realtime.collect { data ->
                    if (data.cellVoltages.isNotEmpty()) {
                        renderSummary(data)
                        adapter.submit(data.cellVoltages)
                    }
                }
            }
        }
    }

    private fun renderSummary(data: BmsRealtimeData) {
        binding.tvSummaryCount.text = getString(R.string.format_cell_count, data.cellCount)
        binding.tvSummaryMax.text = getString(R.string.format_voltage_mv, data.maxCellVoltage)
        binding.tvSummaryMin.text = getString(R.string.format_voltage_mv, data.minCellVoltage)
        binding.tvSummaryDiff.text = getString(R.string.format_diff, data.cellDiff)

        val colorRes = if (data.cellDiff > DIFF_THRESHOLD_MV) R.color.abnormal else R.color.state_connected
        val color = ContextCompat.getColor(requireContext(), colorRes)
        binding.tvSummaryDiff.setTextColor(color)
        binding.tvSummaryVerdict.setText(
            if (colorRes == R.color.abnormal) R.string.verdict_abnormal else R.string.verdict_normal
        )
        binding.tvSummaryVerdict.setTextColor(color)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 离开页面必须清除常亮，否则持续耗电
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding = null
    }
}
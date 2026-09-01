package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.towerscope.ar.network.NetworkSession
import com.towerscope.ar.network.WifiMonitor
import com.towerscope.ar.ui.NetworkHubPreviews
import com.towerscope.ar.ui.WfmSegmentTabs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NetworkHubFragment : Fragment(R.layout.activity_network_hub) {

    private lateinit var connectionPreview: NetworkHubPreviews.PreviewViews
    private lateinit var wifiPreview: NetworkHubPreviews.PreviewViews
    private lateinit var localPreview: NetworkHubPreviews.PreviewViews
    private lateinit var internetPreview: NetworkHubPreviews.PreviewViews

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connectionPreview = NetworkHubPreviews.views(view, R.id.hubConnectionPreview)
        wifiPreview = NetworkHubPreviews.views(view, R.id.hubWifiPreview)
        localPreview = NetworkHubPreviews.views(view, R.id.hubLocalPreview)
        internetPreview = NetworkHubPreviews.views(view, R.id.hubInternetPreview)

        view.findViewById<View>(R.id.hubTabConnection).findViewById<TextView>(R.id.wfmTabLabel)
            .setText(R.string.hub_section_connection)
        view.findViewById<View>(R.id.hubTabWifi).findViewById<TextView>(R.id.wfmTabLabel)
            .setText(R.string.hub_tab_wifi_short)
        view.findViewById<View>(R.id.hubTabLocal).findViewById<TextView>(R.id.wfmTabLabel)
            .setText(R.string.hub_tab_local_short)
        view.findViewById<View>(R.id.hubTabInternet).findViewById<TextView>(R.id.wfmTabLabel)
            .setText(R.string.hub_section_internet)

        WfmSegmentTabs.bindNetworkHub(view)

        refreshResume(view.findViewById(R.id.networkHubResumeLabel))

        bindTile(view, R.id.hubConnectionRow, R.drawable.ic_my_location, R.color.accent_teal,
            R.string.home_job_connection, R.string.home_job_connection_sub) {
            startActivity(Intent(requireContext(), ConnectionSnapshotActivity::class.java))
        }
        bindTile(view, R.id.hubWifiRow, R.drawable.ic_wifi_signal, R.color.accent_teal,
            R.string.home_job_wifi, R.string.home_job_wifi_sub) {
            startActivity(Intent(requireContext(), WifiMonitorActivity::class.java))
        }
        bindTile(view, R.id.hubWalkRow, R.drawable.ic_person, R.color.accent_teal,
            R.string.home_job_signal_walk, R.string.home_job_signal_walk_sub) {
            startActivity(Intent(requireContext(), SignalWalkActivity::class.java))
        }
        bindTile(view, R.id.hubLanRow, R.drawable.ic_subnet_scan, R.color.accent_teal,
            R.string.home_job_lan_scan, R.string.home_job_lan_scan_sub) {
            startActivity(Intent(requireContext(), LanScannerActivity::class.java))
        }
        bindTile(view, R.id.hubSpeedRow, R.drawable.ic_speed_test, R.color.accent_yellow,
            R.string.home_job_speed, R.string.home_job_speed_sub) {
            startActivity(Intent(requireContext(), SpeedTestActivity::class.java))
        }
        bindTile(view, R.id.hubPingRow, R.drawable.ic_ping_graph, R.color.accent_yellow,
            R.string.home_job_ping, R.string.home_job_ping_sub) {
            startActivity(Intent(requireContext(), PingMonitorActivity::class.java))
        }
        bindTile(view, R.id.hubTraceRow, R.drawable.ic_traceroute, R.color.accent_yellow,
            R.string.home_job_traceroute, R.string.home_job_traceroute_sub) {
            startActivity(Intent(requireContext(), TraceRouteActivity::class.java))
        }
        bindTile(view, R.id.hubDiagnoseRow, R.drawable.ic_network_diagnose, R.color.accent_yellow,
            R.string.home_job_diagnose, R.string.home_job_diagnose_sub) {
            startActivity(Intent(requireContext(), NetworkDiagnoseActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val wifiMonitor = WifiMonitor(requireContext())
                while (isActive) {
                    val ctx = context ?: break
                    NetworkHubPreviews.refresh(
                        root = view,
                        context = ctx,
                        wifiMonitor = wifiMonitor,
                        connection = connectionPreview,
                        wifi = wifiPreview,
                        local = localPreview,
                        internet = internetPreview
                    )
                    refreshResume(view.findViewById(R.id.networkHubResumeLabel))
                    delay(4_000L)
                }
            }
        }
    }

    private fun refreshResume(label: TextView?) {
        val ctx = context ?: return
        val resume = listOfNotNull(
            NetworkSession.speedSummary(ctx)?.let { "Speed: $it" },
            NetworkSession.pingSummary(ctx)?.let { "Ping: $it" }
        ).joinToString("\n")
        label?.apply {
            isVisible = resume.isNotBlank()
            text = resume
        }
    }

    private fun bindTile(
        root: View,
        rowId: Int,
        icon: Int,
        iconTint: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
        val row = root.findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.hubToolIcon).apply {
            setImageResource(icon)
            setColorFilter(ContextCompat.getColor(requireContext(), iconTint))
        }
        row.findViewById<TextView>(R.id.hubToolTitle).setText(title)
        row.findViewById<TextView>(R.id.hubToolSubtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }
}

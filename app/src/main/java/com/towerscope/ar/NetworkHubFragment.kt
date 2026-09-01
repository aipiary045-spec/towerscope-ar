package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.towerscope.ar.network.ConnectionSnapshotCollector
import com.towerscope.ar.network.NetworkSession
import com.towerscope.ar.ui.NetworkTopologyBinder
import com.towerscope.ar.ui.WfmSegmentTabs
import kotlinx.coroutines.launch

class NetworkHubFragment : Fragment(R.layout.activity_network_hub) {

    private var connectionStatus: TextView? = null
    private var connectionDetail: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        connectionStatus = view.findViewById(R.id.hubConnectionLiveStatus)
        connectionDetail = view.findViewById(R.id.hubConnectionLiveDetail)

        view.findViewById<View>(R.id.hubTabConnection).findViewById<TextView>(R.id.wfmTabLabel)
            .setText(R.string.hub_section_connection)
        view.findViewById<View>(R.id.hubTabWifi).findViewById<TextView>(R.id.wfmTabLabel)
            .setText(R.string.hub_tab_wifi_short)
        view.findViewById<View>(R.id.hubTabLocal).findViewById<TextView>(R.id.wfmTabLabel)
            .setText(R.string.hub_tab_local_short)
        view.findViewById<View>(R.id.hubTabInternet).findViewById<TextView>(R.id.wfmTabLabel)
            .setText(R.string.hub_section_internet)

        WfmSegmentTabs.bindNetworkHub(view)

        val resumeLabel = view.findViewById<TextView>(R.id.networkHubResumeLabel)
        refreshResume(resumeLabel)
        refreshTopology(view)

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
    }

    override fun onResume() {
        super.onResume()
        view?.let { root ->
            refreshResume(root.findViewById(R.id.networkHubResumeLabel))
            refreshTopology(root)
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

    private fun refreshTopology(root: View) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = ConnectionSnapshotCollector.collect(ctx, fetchPublicIp = false)
            NetworkTopologyBinder.bind(root, snapshot)
            connectionStatus?.text = when {
                snapshot.isValidated -> "Connected · ${snapshot.linkType}"
                snapshot.isConnected -> "Limited · ${snapshot.linkType}"
                else -> "Not connected"
            }
            connectionDetail?.text = buildString {
                snapshot.localIpv4?.let { append("Device $it") }
                snapshot.gatewayIpv4?.let {
                    if (isNotEmpty()) append("\n")
                    append("Gateway $it")
                }
                snapshot.wifiSsid?.let {
                    if (isNotEmpty()) append("\n")
                    append("Wi‑Fi $it")
                }
                snapshot.wifiRssiDbm?.let { append(" · $it dBm") }
                if (isEmpty()) append("Open Connection snapshot for full details")
            }
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

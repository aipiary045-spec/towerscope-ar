package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.towerscope.ar.network.NetworkSession

class NetworkHubFragment : Fragment(R.layout.activity_network_hub) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val resumeLabel = view.findViewById<TextView>(R.id.networkHubResumeLabel)
        val ctx = requireContext()
        val resume = listOfNotNull(
            NetworkSession.speedSummary(ctx)?.let { "Speed: $it" },
            NetworkSession.pingSummary(ctx)?.let { "Ping: $it" }
        ).joinToString("\n")
        resumeLabel?.apply {
            isVisible = resume.isNotBlank()
            text = resume
        }

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

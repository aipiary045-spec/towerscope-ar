package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.towerscope.ar.ui.BottomNav
import com.towerscope.ar.ui.BottomNavTab
import com.towerscope.ar.ui.SystemBars

class NetworkHubActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_network_hub)
        SystemBars.apply(
            root = findViewById(R.id.networkHubRoot),
            alsoBottom = findViewById(R.id.networkBottomNav)
        )
        BottomNav.bind(this, BottomNavTab.NETWORK)

        bindTile(
            rowId = R.id.hubWifiRow,
            icon = R.drawable.ic_wifi_signal,
            iconTint = R.color.accent_teal,
            title = R.string.home_job_wifi,
            subtitle = R.string.home_job_wifi_sub
        ) { startActivity(Intent(this, WifiMonitorActivity::class.java)) }

        bindTile(
            rowId = R.id.hubSpeedRow,
            icon = R.drawable.ic_speed_test,
            iconTint = R.color.accent_yellow,
            title = R.string.home_job_speed,
            subtitle = R.string.home_job_speed_sub
        ) { startActivity(Intent(this, SpeedTestActivity::class.java)) }

        bindTile(
            rowId = R.id.hubPingRow,
            icon = R.drawable.ic_ping_graph,
            iconTint = R.color.accent_yellow,
            title = R.string.home_job_ping,
            subtitle = R.string.home_job_ping_sub
        ) { startActivity(Intent(this, PingMonitorActivity::class.java)) }

        bindTile(
            rowId = R.id.hubSubnetRow,
            icon = R.drawable.ic_subnet_scan,
            iconTint = R.color.accent_teal,
            title = R.string.home_job_subnet,
            subtitle = R.string.home_job_subnet_sub
        ) { startActivity(Intent(this, SubnetScannerActivity::class.java)) }

        bindTile(
            rowId = R.id.hubDnsRow,
            icon = R.drawable.ic_dns_lookup,
            iconTint = R.color.accent_teal,
            title = R.string.home_job_dns,
            subtitle = R.string.home_job_dns_sub
        ) { startActivity(Intent(this, DnsLookupActivity::class.java)) }

        bindTile(
            rowId = R.id.hubTraceRow,
            icon = R.drawable.ic_traceroute,
            iconTint = R.color.accent_yellow,
            title = R.string.home_job_traceroute,
            subtitle = R.string.home_job_traceroute_sub
        ) { startActivity(Intent(this, TraceRouteActivity::class.java)) }

        bindTile(
            rowId = R.id.hubBandwidthRow,
            icon = R.drawable.ic_bandwidth,
            iconTint = R.color.accent_yellow,
            title = R.string.home_job_bandwidth,
            subtitle = R.string.home_job_bandwidth_sub
        ) { startActivity(Intent(this, BandwidthMonitorActivity::class.java)) }

        bindTile(
            rowId = R.id.hubDiagnoseRow,
            icon = R.drawable.ic_network_diagnose,
            iconTint = R.color.accent_teal,
            title = R.string.home_job_diagnose,
            subtitle = R.string.home_job_diagnose_sub
        ) { startActivity(Intent(this, NetworkDiagnoseActivity::class.java)) }
    }

    private fun bindTile(rowId: Int, icon: Int, iconTint: Int, title: Int, subtitle: Int, onClick: () -> Unit) {
        val row = findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.hubToolIcon).apply {
            setImageResource(icon)
            setColorFilter(ContextCompat.getColor(this@NetworkHubActivity, iconTint))
        }
        row.findViewById<TextView>(R.id.hubToolTitle).setText(title)
        row.findViewById<TextView>(R.id.hubToolSubtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }
}

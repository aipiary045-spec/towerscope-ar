package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
            index = "01",
            icon = R.drawable.ic_wifi_signal,
            title = R.string.home_job_wifi,
            subtitle = R.string.home_job_wifi_sub
        ) { startActivity(Intent(this, WifiMonitorActivity::class.java)) }

        bindTile(
            rowId = R.id.hubChannelRow,
            index = "02",
            icon = R.drawable.ic_layers,
            title = R.string.home_job_channel_plan,
            subtitle = R.string.home_job_channel_plan_sub
        ) { startActivity(Intent(this, ChannelPlannerActivity::class.java)) }

        bindTile(
            rowId = R.id.hubSpeedRow,
            index = "03",
            icon = R.drawable.ic_speed_test,
            title = R.string.home_job_speed,
            subtitle = R.string.home_job_speed_sub
        ) { startActivity(Intent(this, SpeedTestActivity::class.java)) }

        bindTile(
            rowId = R.id.hubPingRow,
            index = "04",
            icon = R.drawable.ic_ping_graph,
            title = R.string.home_job_ping,
            subtitle = R.string.home_job_ping_sub
        ) { startActivity(Intent(this, PingMonitorActivity::class.java)) }

        bindTile(
            rowId = R.id.hubSubnetRow,
            index = "05",
            icon = R.drawable.ic_subnet_scan,
            title = R.string.home_job_subnet,
            subtitle = R.string.home_job_subnet_sub
        ) { startActivity(Intent(this, SubnetScannerActivity::class.java)) }

        bindTile(
            rowId = R.id.hubDnsRow,
            index = "06",
            icon = R.drawable.ic_dns_lookup,
            title = R.string.home_job_dns,
            subtitle = R.string.home_job_dns_sub
        ) { startActivity(Intent(this, DnsLookupActivity::class.java)) }

        bindTile(
            rowId = R.id.hubTraceRow,
            index = "07",
            icon = R.drawable.ic_traceroute,
            title = R.string.home_job_traceroute,
            subtitle = R.string.home_job_traceroute_sub
        ) { startActivity(Intent(this, TraceRouteActivity::class.java)) }

        bindTile(
            rowId = R.id.hubBandwidthRow,
            index = "08",
            icon = R.drawable.ic_bandwidth,
            title = R.string.home_job_bandwidth,
            subtitle = R.string.home_job_bandwidth_sub
        ) { startActivity(Intent(this, BandwidthMonitorActivity::class.java)) }

        bindTile(
            rowId = R.id.hubDiagnoseRow,
            index = "09",
            icon = R.drawable.ic_network_diagnose,
            title = R.string.home_job_diagnose,
            subtitle = R.string.home_job_diagnose_sub
        ) { startActivity(Intent(this, NetworkDiagnoseActivity::class.java)) }
    }

    private fun bindTile(
        rowId: Int,
        index: String,
        icon: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.hubToolIndex).text = index
        row.findViewById<ImageView>(R.id.hubToolIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.hubToolTitle).setText(title)
        row.findViewById<TextView>(R.id.hubToolSubtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }
}

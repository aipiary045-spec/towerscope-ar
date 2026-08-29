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

        bindTile(R.id.hubConnectionRow, R.drawable.ic_my_location, R.color.accent_teal,
            R.string.home_job_connection, R.string.home_job_connection_sub) {
            startActivity(Intent(this, ConnectionSnapshotActivity::class.java))
        }
        bindTile(R.id.hubWifiRow, R.drawable.ic_wifi_signal, R.color.accent_teal,
            R.string.home_job_wifi, R.string.home_job_wifi_sub) {
            startActivity(Intent(this, WifiMonitorActivity::class.java))
        }
        bindTile(R.id.hubChannelRow, R.drawable.ic_layers, R.color.accent_yellow,
            R.string.home_job_channel_plan, R.string.home_job_channel_plan_sub) {
            startActivity(Intent(this, ChannelPlannerActivity::class.java))
        }
        bindTile(R.id.hubWalkRow, R.drawable.ic_person, R.color.accent_teal,
            R.string.home_job_signal_walk, R.string.home_job_signal_walk_sub) {
            startActivity(Intent(this, SignalWalkActivity::class.java))
        }

        bindTile(R.id.hubSubnetRow, R.drawable.ic_subnet_scan, R.color.accent_teal,
            R.string.home_job_subnet, R.string.home_job_subnet_sub) {
            startActivity(Intent(this, SubnetScannerActivity::class.java))
        }
        bindTile(R.id.hubWanLinkRow, R.drawable.ic_speed_test, R.color.accent_yellow,
            R.string.home_job_wan_link, R.string.home_job_wan_link_sub) {
            startActivity(Intent(this, WanLinkActivity::class.java))
        }
        bindTile(R.id.hubPortRow, R.drawable.ic_dns_lookup, R.color.accent_yellow,
            R.string.home_job_port_scan, R.string.home_job_port_scan_sub) {
            startActivity(Intent(this, PortScannerActivity::class.java))
        }

        bindTile(R.id.hubSpeedRow, R.drawable.ic_speed_test, R.color.accent_yellow,
            R.string.home_job_speed, R.string.home_job_speed_sub) {
            startActivity(Intent(this, SpeedTestActivity::class.java))
        }
        bindTile(R.id.hubBufferbloatRow, R.drawable.ic_bandwidth, R.color.accent_yellow,
            R.string.home_job_bufferbloat, R.string.home_job_bufferbloat_sub) {
            startActivity(Intent(this, BufferbloatActivity::class.java))
        }
        bindTile(R.id.hubThroughputRow, R.drawable.ic_bandwidth, R.color.accent_teal,
            R.string.home_job_throughput, R.string.home_job_throughput_sub) {
            startActivity(Intent(this, ThroughputTestActivity::class.java))
        }
        bindTile(R.id.hubPingRow, R.drawable.ic_ping_graph, R.color.accent_yellow,
            R.string.home_job_ping, R.string.home_job_ping_sub) {
            startActivity(Intent(this, PingMonitorActivity::class.java))
        }
        bindTile(R.id.hubDnsRow, R.drawable.ic_dns_lookup, R.color.accent_teal,
            R.string.home_job_dns, R.string.home_job_dns_sub) {
            startActivity(Intent(this, DnsLookupActivity::class.java))
        }
        bindTile(R.id.hubTraceRow, R.drawable.ic_traceroute, R.color.accent_yellow,
            R.string.home_job_traceroute, R.string.home_job_traceroute_sub) {
            startActivity(Intent(this, TraceRouteActivity::class.java))
        }
        bindTile(R.id.hubDiagnoseRow, R.drawable.ic_network_diagnose, R.color.accent_yellow,
            R.string.home_job_diagnose, R.string.home_job_diagnose_sub) {
            startActivity(Intent(this, NetworkDiagnoseActivity::class.java))
        }
    }

    private fun bindTile(
        rowId: Int,
        icon: Int,
        iconTint: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
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

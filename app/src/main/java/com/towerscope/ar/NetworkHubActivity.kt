package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.ui.SystemBars

class NetworkHubActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_network_hub)
        SystemBars.apply(findViewById(R.id.networkHubRoot))

        bindRow(
            rowId = R.id.hubWifiRow,
            icon = R.drawable.ic_wifi_signal,
            title = R.string.home_job_wifi,
            subtitle = R.string.home_job_wifi_sub
        ) { startActivity(Intent(this, WifiMonitorActivity::class.java)) }

        bindRow(
            rowId = R.id.hubSpeedRow,
            icon = R.drawable.ic_speed_test,
            title = R.string.home_job_speed,
            subtitle = R.string.home_job_speed_sub
        ) { startActivity(Intent(this, SpeedTestActivity::class.java)) }

        bindRow(
            rowId = R.id.hubPingRow,
            icon = R.drawable.ic_ping_graph,
            title = R.string.home_job_ping,
            subtitle = R.string.home_job_ping_sub
        ) { startActivity(Intent(this, PingMonitorActivity::class.java)) }

        bindRow(
            rowId = R.id.hubSubnetRow,
            icon = R.drawable.ic_subnet_scan,
            title = R.string.home_job_subnet,
            subtitle = R.string.home_job_subnet_sub
        ) { startActivity(Intent(this, SubnetScannerActivity::class.java)) }

        bindRow(
            rowId = R.id.hubDiagnoseRow,
            icon = R.drawable.ic_network_diagnose,
            title = R.string.home_job_diagnose,
            subtitle = R.string.home_job_diagnose_sub
        ) { startActivity(Intent(this, NetworkDiagnoseActivity::class.java)) }

        findViewById<MaterialButton>(R.id.networkHubBackButton).setOnClickListener { finish() }
    }

    private fun bindRow(rowId: Int, icon: Int, title: Int, subtitle: Int, onClick: () -> Unit) {
        val row = findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.hubToolIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.hubToolTitle).setText(title)
        row.findViewById<TextView>(R.id.hubToolSubtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }
}

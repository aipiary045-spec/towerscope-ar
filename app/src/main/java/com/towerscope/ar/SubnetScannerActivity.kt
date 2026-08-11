package com.towerscope.ar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.SubnetHost
import com.towerscope.ar.network.SubnetScanner
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SubnetScannerActivity : AppCompatActivity() {

    private lateinit var infoLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var hostList: LinearLayout
    private lateinit var scanButton: MaterialButton
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_subnet_scanner)
        SystemBars.apply(findViewById(R.id.subnetRoot))

        infoLabel = findViewById(R.id.subnetInfoLabel)
        statusLabel = findViewById(R.id.subnetStatus)
        hostList = findViewById(R.id.subnetHostList)
        scanButton = findViewById(R.id.subnetScanButton)

        refreshSubnetInfo()
        scanButton.setOnClickListener { startScan() }
        findViewById<MaterialButton>(R.id.subnetBackButton).setOnClickListener {
            scanJob?.cancel()
            finish()
        }
    }

    override fun onStop() {
        scanJob?.cancel()
        super.onStop()
    }

    private fun refreshSubnetInfo() {
        val subnet = SubnetScanner.localSubnet(this)
        if (subnet == null) {
            infoLabel.text = "No active IPv4 interface"
            statusLabel.text = "Connect to Wi‑Fi, then scan"
            scanButton.isEnabled = false
        } else {
            infoLabel.text = String.format(
                Locale.US,
                "%s/%d  ·  %s",
                subnet.localIp,
                subnet.prefixLength,
                subnet.networkBase
            )
            statusLabel.text = "Up to ${subnet.hostCount} hosts · tap IP to open"
            scanButton.isEnabled = true
        }
    }

    private fun startScan() {
        val subnet = SubnetScanner.localSubnet(this) ?: run {
            refreshSubnetInfo()
            return
        }
        scanJob?.cancel()
        hostList.removeAllViews()
        scanButton.isEnabled = false
        statusLabel.text = "Scanning…"
        scanJob = lifecycleScope.launch {
            val found = SubnetScanner.scan(subnet) { scanned, total, host ->
                withContext(Dispatchers.Main.immediate) {
                    statusLabel.text = "Scanning $scanned / $total"
                    if (host != null) appendHost(host)
                }
            }
            statusLabel.text = "Done · ${found.size} hosts · tap a URL to open"
            scanButton.isEnabled = true
        }
    }

    private fun appendHost(host: SubnetHost) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_subnet_host_row, hostList, false)
        row.findViewById<TextView>(R.id.subnetRowIp).text = host.ip
        row.findViewById<TextView>(R.id.subnetRowUrl).text = host.httpUrl
        val meta = buildString {
            append("MAC  ")
            append(host.macAddress ?: "—")
            append("  ·  ")
            append(host.hostname ?: "host")
            host.openPort?.let { append("  ·  port ").append(it) }
        }
        row.findViewById<TextView>(R.id.subnetRowMeta).text = meta
        row.setOnClickListener { openHostUrl(host) }
        hostList.addView(row)
    }

    private fun openHostUrl(host: SubnetHost) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(host.httpUrl))
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "No browser available for ${host.httpUrl}", Toast.LENGTH_SHORT)
                .show()
        }
    }
}

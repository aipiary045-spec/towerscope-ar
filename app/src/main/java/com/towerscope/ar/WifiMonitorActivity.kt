package com.towerscope.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.WifiMonitor
import com.towerscope.ar.network.WifiScanAp
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Field Wi‑Fi RSSI monitor: connected link + nearby AP scan.
 */
class WifiMonitorActivity : AppCompatActivity() {

    private lateinit var monitor: WifiMonitor
    private lateinit var ssidLabel: TextView
    private lateinit var rssiLabel: TextView
    private lateinit var qualityLabel: TextView
    private lateinit var metaLabel: TextView
    private lateinit var scanStatus: TextView
    private lateinit var scanList: LinearLayout
    private lateinit var scanButton: MaterialButton
    private var scanJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val nearbyOk = result[Manifest.permission.NEARBY_WIFI_DEVICES] == true
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (nearbyOk || fineOk) {
            startScan()
        } else {
            scanStatus.text = "Wi‑Fi permission needed to scan nearby APs"
        }
        refreshLink()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_wifi_monitor)
        SystemBars.apply(findViewById(R.id.wifiRoot))
        monitor = WifiMonitor(this)

        ssidLabel = findViewById(R.id.wifiSsid)
        rssiLabel = findViewById(R.id.wifiRssi)
        qualityLabel = findViewById(R.id.wifiQuality)
        metaLabel = findViewById(R.id.wifiMeta)
        scanStatus = findViewById(R.id.wifiScanStatus)
        scanList = findViewById(R.id.wifiScanList)
        scanButton = findViewById(R.id.wifiScanButton)

        scanButton.setOnClickListener { ensureScanPermissionAndStart() }
        findViewById<MaterialButton>(R.id.wifiBackButton).setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshLink()
                    delay(1_000L)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshLink()
        if (hasScanPermission()) {
            startScan()
        }
    }

    override fun onStop() {
        scanJob?.cancel()
        scanJob = null
        super.onStop()
    }

    private fun refreshLink() {
        val link = monitor.currentLink()
        ssidLabel.text = when {
            !link.connected -> "Not connected"
            link.ssid != null -> link.ssid
            else -> "Connected (SSID hidden)"
        }
        val rssi = link.rssiDbm
        rssiLabel.text = if (rssi != null) {
            String.format(Locale.US, "%d dBm", rssi)
        } else {
            "—"
        }
        rssiLabel.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    rssi == null -> R.color.text_muted
                    rssi >= -60 -> R.color.accent_teal
                    rssi >= -75 -> R.color.accent_yellow
                    else -> R.color.chip_poor
                }
            )
        )
        qualityLabel.text = monitor.signalQualityLabel(rssi)
        qualityLabel.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    rssi == null -> R.color.text_muted
                    rssi >= -60 -> R.color.status_clear
                    rssi >= -75 -> R.color.accent_yellow
                    else -> R.color.chip_poor
                }
            )
        )
        val speed = link.linkSpeedMbps?.let { "$it Mbps" } ?: "—"
        val band = link.frequencyMhz?.let { WifiMonitor.formatBand(it) } ?: "—"
        metaLabel.text = "Link  $speed  ·  Band  $band"
    }

    private fun ensureScanPermissionAndStart() {
        if (hasScanPermission()) {
            startScan()
            return
        }
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }

    private fun hasScanPermission(): Boolean {
        val nearby = ContextCompat.checkSelfPermission(
            this, Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return nearby || fine
    }

    private fun startScan() {
        scanJob?.cancel()
        scanStatus.text = "Scanning…"
        scanButton.isEnabled = false
        val started = monitor.startScan()
        if (!started) {
            scanStatus.text = "Scan throttled — showing last results"
            renderScanRows(monitor.latestScanResults())
            scanButton.isEnabled = true
        }
        scanJob = lifecycleScope.launch {
            monitor.scanUpdates().collect { results ->
                renderScanRows(results)
                scanStatus.text = if (results.isEmpty()) {
                    "No APs found — try again outdoors / near CPE"
                } else {
                    "${results.size} APs · strongest first"
                }
                scanButton.isEnabled = true
            }
        }
    }

    private fun renderScanRows(results: List<WifiScanAp>) {
        scanList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        results.take(40).forEach { ap ->
            val row = inflater.inflate(R.layout.item_wifi_scan_row, scanList, false)
            row.findViewById<TextView>(R.id.wifiRowSsid).text = ap.ssid
            row.findViewById<TextView>(R.id.wifiRowMeta).text = String.format(
                Locale.US,
                "ch %d · %s · %s",
                ap.channel,
                WifiMonitor.formatBand(ap.frequencyMhz),
                ap.bssid
            )
            row.findViewById<TextView>(R.id.wifiRowRssi).text =
                String.format(Locale.US, "%d", ap.rssiDbm)
            scanList.addView(row)
        }
    }
}

package com.towerscope.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.InterferenceLevel
import com.towerscope.ar.network.WifiMonitor
import com.towerscope.ar.network.WifiScanAp
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.ToolScaffold
import com.towerscope.ar.ui.WifiScanAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Field Wi‑Fi RSSI monitor: live channel, overlapping APs, interference hints.
 */
class WifiMonitorActivity : AppCompatActivity() {

    private lateinit var monitor: WifiMonitor
    private lateinit var ssidLabel: TextView
    private lateinit var rssiLabel: TextView
    private lateinit var qualityLabel: TextView
    private lateinit var metaLabel: TextView
    private lateinit var channelLiveLabel: TextView
    private lateinit var overlapSummary: TextView
    private lateinit var interferenceLevel: TextView
    private lateinit var interferenceHints: TextView
    private lateinit var scanStatus: TextView
    private lateinit var scanAdapter: WifiScanAdapter
    private lateinit var scanButton: MaterialButton
    private var scanJob: Job? = null
    private var latestScan: List<WifiScanAp> = emptyList()

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
        ToolScaffold.bind(
            activity = this,
            titleRes = R.string.home_job_wifi,
            subtitleRes = R.string.home_job_wifi_sub
        )
        monitor = WifiMonitor(this)

        ssidLabel = findViewById(R.id.wifiSsid)
        rssiLabel = findViewById(R.id.wifiRssi)
        qualityLabel = findViewById(R.id.wifiQuality)
        metaLabel = findViewById(R.id.wifiMeta)
        channelLiveLabel = findViewById(R.id.wifiChannelLive)
        overlapSummary = findViewById(R.id.wifiOverlapSummary)
        interferenceLevel = findViewById(R.id.wifiInterferenceLevel)
        interferenceHints = findViewById(R.id.wifiInterferenceHints)
        scanStatus = findViewById(R.id.wifiScanStatus)
        scanButton = findViewById(R.id.wifiScanButton)

        scanAdapter = WifiScanAdapter()
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.wifiScanList).apply {
            layoutManager = LinearLayoutManager(this@WifiMonitorActivity)
            adapter = scanAdapter
            itemAnimator = null
        }

        scanButton.setOnClickListener { ensureScanPermissionAndStart() }

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
        val band = link.band ?: "—"
        metaLabel.text = "Link  $speed  ·  Band  $band"
        channelLiveLabel.text = when {
            link.channel != null && link.frequencyMhz != null ->
                String.format(
                    Locale.US,
                    "Channel  %d  ·  %d MHz  ·  %s",
                    link.channel,
                    link.frequencyMhz,
                    link.band ?: ""
                )
            else -> "Channel  —"
        }

        val annotated = monitor.annotateScan(link, latestScan)
        val analysis = monitor.analyzeChannels(link, annotated)
        overlapSummary.text = analysis.overlapSummary
        interferenceLevel.text =
            "RF risk  ${WifiMonitor.interferenceLabel(analysis.interferenceLevel)}"
        interferenceLevel.setTextColor(
            ContextCompat.getColor(
                this,
                when (analysis.interferenceLevel) {
                    InterferenceLevel.LOW -> R.color.status_clear
                    InterferenceLevel.MODERATE -> R.color.accent_yellow
                    InterferenceLevel.HIGH -> R.color.chip_poor
                    InterferenceLevel.UNKNOWN -> R.color.text_muted
                }
            )
        )
        interferenceHints.text = analysis.interferenceHints.joinToString("\n")
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
            val link = monitor.currentLink()
            latestScan = monitor.annotateScan(link, monitor.latestScanResults())
            renderScanRows(latestScan)
            refreshLink()
            scanButton.isEnabled = true
        }
        scanJob = lifecycleScope.launch {
            monitor.scanUpdates().collect { results ->
                val link = monitor.currentLink()
                latestScan = monitor.annotateScan(link, results)
                renderScanRows(latestScan)
                refreshLink()
                val overlap = latestScan.count { it.overlapWithActive }
                scanStatus.text = if (latestScan.isEmpty()) {
                    "No APs found — try again outdoors / near CPE"
                } else {
                    "${latestScan.size} APs · $overlap overlapping your channel"
                }
                scanButton.isEnabled = true
            }
        }
    }

    private fun renderScanRows(results: List<WifiScanAp>) {
        scanAdapter.submitList(WifiScanAdapter.sortForDisplay(results))
    }
}

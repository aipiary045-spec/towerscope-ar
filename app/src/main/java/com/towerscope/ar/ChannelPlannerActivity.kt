package com.towerscope.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.ChannelPlanner
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.network.WifiMonitor
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChannelPlannerActivity : AppCompatActivity() {

    private lateinit var monitor: WifiMonitor
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private var scanJob: Job? = null
    private var lastReport = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result[Manifest.permission.NEARBY_WIFI_DEVICES] == true ||
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (ok) scan() else statusView.text = "Wi‑Fi scan permission needed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_channel_planner)
        SystemBars.apply(findViewById(R.id.channelPlannerRoot))
        monitor = WifiMonitor(this)

        statusView = findViewById(R.id.channelPlannerStatus)
        resultView = findViewById(R.id.channelPlannerResult)

        findViewById<MaterialButton>(R.id.channelPlannerScanButton).setOnClickListener { ensureScan() }
        findViewById<MaterialButton>(R.id.toolShareButton).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.toolBackButton).setOnClickListener {
            scanJob?.cancel()
            finish()
        }
    }

    override fun onStop() {
        scanJob?.cancel()
        super.onStop()
    }

    private fun ensureScan() {
        val nearby = ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (nearby == PackageManager.PERMISSION_GRANTED || fine == PackageManager.PERMISSION_GRANTED) {
            scan()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    private fun scan() {
        scanJob?.cancel()
        statusView.text = "Scanning…"
        scanJob = lifecycleScope.launch {
            monitor.startScan()
            kotlinx.coroutines.delay(2_500)
            val results = monitor.latestScanResults()
            val report = ChannelPlanner.analyze(results)
            lastReport = ChannelPlanner.formatReport(report)
            resultView.text = lastReport
            val best = report.best5 ?: report.best24 ?: report.best6
            statusView.text = best?.let { "Best: ch ${it.channel} (${it.band})" } ?: "No APs found — try again"
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) TestResultExport.shareText(this, "Channel planner", lastReport)
    }
}

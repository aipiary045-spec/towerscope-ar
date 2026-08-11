package com.towerscope.ar

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.BandwidthMonitor
import com.towerscope.ar.network.BandwidthSample
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BandwidthMonitorActivity : AppCompatActivity() {

    private lateinit var statusLabel: TextView
    private lateinit var overallView: TextView
    private lateinit var wifiView: TextView
    private lateinit var mobileView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleButton: MaterialButton
    private var job: Job? = null
    private val logLines = ArrayDeque<String>(60)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_bandwidth)
        SystemBars.apply(findViewById(R.id.bandwidthRoot))

        statusLabel = findViewById(R.id.bandwidthStatus)
        overallView = findViewById(R.id.bandwidthOverall)
        wifiView = findViewById(R.id.bandwidthWifi)
        mobileView = findViewById(R.id.bandwidthMobile)
        logView = findViewById(R.id.bandwidthLogView)
        toggleButton = findViewById(R.id.bandwidthToggleButton)

        toggleButton.setOnClickListener {
            if (job?.isActive == true) stopMonitor() else startMonitor()
        }
        findViewById<MaterialButton>(R.id.bandwidthBackButton).setOnClickListener {
            stopMonitor()
            finish()
        }
    }

    override fun onStop() {
        stopMonitor()
        super.onStop()
    }

    private fun startMonitor() {
        logLines.clear()
        logView.text = "—"
        toggleButton.text = "Stop"
        statusLabel.text = "Sampling…"
        job = lifecycleScope.launch {
            BandwidthMonitor.stream(1_000L)
                .catch { e ->
                    statusLabel.text = "Error: ${e.message ?: "failed"}"
                    stopMonitor()
                }
                .collect { sample -> render(sample) }
        }
    }

    private fun stopMonitor() {
        job?.cancel()
        job = null
        toggleButton.text = "Start monitor"
        if (statusLabel.text.toString().startsWith("Live") ||
            statusLabel.text.toString().startsWith("Sampling")
        ) {
            statusLabel.text = "Stopped"
        }
    }

    private fun render(sample: BandwidthSample) {
        statusLabel.text = "Live · ${timeFmt.format(Date(sample.timestampMs))}"
        overallView.text = pair(sample.rxMbps, sample.txMbps)
        wifiView.text = pair(sample.wifiRxMbps, sample.wifiTxMbps)
        mobileView.text = pair(sample.mobileRxMbps, sample.mobileTxMbps)

        val line = String.format(
            Locale.US,
            "%s  all ↓%s ↑%s  wifi ↓%s ↑%s",
            timeFmt.format(Date(sample.timestampMs)),
            BandwidthMonitor.formatMbps(sample.rxMbps),
            BandwidthMonitor.formatMbps(sample.txMbps),
            BandwidthMonitor.formatMbps(sample.wifiRxMbps),
            BandwidthMonitor.formatMbps(sample.wifiTxMbps)
        )
        logLines.addLast(line)
        while (logLines.size > 50) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }

    private fun pair(rx: Double, tx: Double): String =
        "↓ ${BandwidthMonitor.formatMbps(rx)}    ↑ ${BandwidthMonitor.formatMbps(tx)}"
}

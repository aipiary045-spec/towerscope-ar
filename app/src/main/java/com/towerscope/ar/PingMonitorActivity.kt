package com.towerscope.ar

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import android.widget.EditText
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.PingMonitor
import com.towerscope.ar.ui.LatencyGraphView
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Locale

class PingMonitorActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var lastLabel: TextView
    private lateinit var statsLabel: TextView
    private lateinit var rangeLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var graph: LatencyGraphView
    private lateinit var toggleButton: MaterialButton
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_ping_monitor)
        SystemBars.apply(findViewById(R.id.pingRoot))

        hostInput = findViewById(R.id.pingHostInput)
        lastLabel = findViewById(R.id.pingLastLabel)
        statsLabel = findViewById(R.id.pingStatsLabel)
        rangeLabel = findViewById(R.id.pingRangeLabel)
        statusLabel = findViewById(R.id.pingStatus)
        graph = findViewById(R.id.pingGraph)
        toggleButton = findViewById(R.id.pingToggleButton)

        toggleButton.setOnClickListener {
            if (job?.isActive == true) stopPing() else startPing()
        }
        findViewById<MaterialButton>(R.id.pingBackButton).setOnClickListener {
            stopPing()
            finish()
        }
    }

    override fun onStop() {
        stopPing()
        super.onStop()
    }

    private fun startPing() {
        val host = hostInput.text?.toString().orEmpty()
        graph.clear()
        hostInput.isEnabled = false
        toggleButton.text = "Stop ping"
        statusLabel.text = "Pinging $host…"
        job = lifecycleScope.launch {
            PingMonitor.stream(host)
                .catch { e ->
                    statusLabel.text = "Ping error: ${e.message ?: "failed"}"
                    stopPing()
                }
                .collect { sample ->
                    lastLabel.text = sample.latencyMs?.let {
                        String.format(Locale.US, "%.0f ms", it)
                    } ?: "timeout"
                    lastLabel.setTextColor(
                        getColor(
                            if (sample.success) R.color.accent_teal else R.color.chip_poor
                        )
                    )
                    statsLabel.text = String.format(
                        Locale.US,
                        "Sent %d · Recv %d · Loss %.0f%%",
                        sample.sent,
                        sample.received,
                        sample.lossPercent
                    )
                    rangeLabel.text = String.format(
                        Locale.US,
                        "min %s · avg %s · max %s",
                        formatMs(sample.minMs),
                        formatMs(sample.avgMs),
                        formatMs(sample.maxMs)
                    )
                    graph.addSample(sample.latencyMs)
                    statusLabel.text = "Live · ${sample.host}:443"
                }
        }
    }

    private fun stopPing() {
        job?.cancel()
        job = null
        hostInput.isEnabled = true
        toggleButton.text = "Start ping"
        if (statusLabel.text.toString().startsWith("Live")) {
            statusLabel.text = "Stopped"
        }
    }

    private fun formatMs(value: Double?): String =
        if (value == null || !value.isFinite()) "—" else String.format(Locale.US, "%.0f", value)
}

package com.towerscope.ar

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.MultiPingSnapshot
import com.towerscope.ar.network.PingHostStats
import com.towerscope.ar.network.PingMonitor
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Locale

class PingMonitorActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var hostStatsList: LinearLayout
    private lateinit var logView: TextView
    private lateinit var toggleButton: MaterialButton
    private var job: Job? = null
    private val logLines = ArrayDeque<String>(120)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_ping_monitor)
        SystemBars.apply(findViewById(R.id.pingRoot))

        hostInput = findViewById(R.id.pingHostInput)
        statusLabel = findViewById(R.id.pingStatus)
        hostStatsList = findViewById(R.id.pingHostStatsList)
        logView = findViewById(R.id.pingLogView)
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
        val hosts = PingMonitor.parseHostList(hostInput.text?.toString().orEmpty())
        logLines.clear()
        logView.text = "—"
        hostInput.isEnabled = false
        toggleButton.text = "Stop ping"
        statusLabel.text = "Pinging ${hosts.size} host${if (hosts.size == 1) "" else "s"}…"
        job = lifecycleScope.launch {
            PingMonitor.streamMany(hosts)
                .catch { e ->
                    statusLabel.text = "Ping error: ${e.message ?: "failed"}"
                    stopPing()
                }
                .collect { snapshot -> renderSnapshot(snapshot) }
        }
    }

    private fun stopPing() {
        job?.cancel()
        job = null
        hostInput.isEnabled = true
        toggleButton.text = "Start ping"
        if (statusLabel.text.toString().startsWith("Live") ||
            statusLabel.text.toString().startsWith("Pinging")
        ) {
            statusLabel.text = "Stopped"
        }
    }

    private fun renderSnapshot(snapshot: MultiPingSnapshot) {
        snapshot.logLine?.let { line ->
            logLines.addFirst(line.message)
            while (logLines.size > 80) logLines.removeLast()
            logView.text = logLines.joinToString("\n")
        }
        renderHostRows(snapshot.hosts)
        val alive = snapshot.hosts.count { it.received > 0 }
        statusLabel.text = "Live · ${snapshot.hosts.size} targets · $alive responding"
    }

    private fun renderHostRows(hosts: List<PingHostStats>) {
        hostStatsList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        hosts.forEach { host ->
            val row = inflater.inflate(R.layout.item_ping_host_row, hostStatsList, false)
            row.findViewById<TextView>(R.id.pingRowHost).text = host.displayTarget
            val last = row.findViewById<TextView>(R.id.pingRowLast)
            last.text = host.lastMs?.let { String.format(Locale.US, "%.0f ms", it) } ?: "timeout"
            last.setTextColor(
                getColor(if (host.lastSuccess) R.color.accent_teal else R.color.chip_poor)
            )
            row.findViewById<TextView>(R.id.pingRowStats).text = String.format(
                Locale.US,
                "%s · Sent %d · Recv %d · Loss %.0f%% · avg %s",
                host.method,
                host.sent,
                host.received,
                host.lossPercent,
                formatMs(host.avgMs)
            )
            hostStatsList.addView(row)
        }
    }

    private fun formatMs(value: Double?): String =
        if (value == null || !value.isFinite()) "—" else String.format(Locale.US, "%.0f", value)
}

package com.towerscope.ar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.MultiPingSnapshot
import com.towerscope.ar.network.NetworkSession
import com.towerscope.ar.network.PingHistory
import com.towerscope.ar.network.PingHostStats
import com.towerscope.ar.network.PingMonitor
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.ui.PingHostStatsAdapter
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.ToolScaffold
import com.towerscope.ar.ui.ToolTopology
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Locale

class PingMonitorActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var durationGroup: RadioGroup
    private lateinit var statusLabel: TextView
    private lateinit var recentLabel: TextView
    private lateinit var recentScroll: HorizontalScrollView
    private lateinit var recentRow: LinearLayout
    private lateinit var hostStatsAdapter: PingHostStatsAdapter
    private lateinit var logView: TextView
    private lateinit var toggleButton: MaterialButton
    private var job: Job? = null
    private var timeoutJob: Job? = null
    private val logLines = ArrayDeque<String>(120)
    private var lastSnapshot: MultiPingSnapshot? = null
    private var startedAtMs: Long = 0L
    private var lastReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_ping_monitor)
        SystemBars.apply(findViewById(R.id.pingRoot))
        ToolTopology.bindWhenResumed(this, findViewById(R.id.pingRoot))
        ToolScaffold.bind(
            activity = this,
            titleRes = R.string.home_job_ping,
            subtitleRes = R.string.home_job_ping_sub,
            onShare = { share() }
        )

        hostInput = findViewById(R.id.pingHostInput)
        durationGroup = findViewById(R.id.pingDurationGroup)
        statusLabel = findViewById(R.id.pingStatus)
        recentLabel = findViewById(R.id.pingRecentLabel)
        recentScroll = findViewById(R.id.pingRecentScroll)
        recentRow = findViewById(R.id.pingRecentRow)
        logView = findViewById(R.id.pingLogView)
        toggleButton = findViewById(R.id.pingToggleButton)

        hostStatsAdapter = PingHostStatsAdapter()
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pingHostStatsList).apply {
            layoutManager = LinearLayoutManager(this@PingMonitorActivity)
            adapter = hostStatsAdapter
            itemAnimator = null
        }

        hostInput.setText(
            PingHistory.lastInput(this) ?: "1.1.1.1, 8.8.8.8"
        )
        renderRecentChips()

        toggleButton.setOnClickListener {
            if (job?.isActive == true) stopPing(showSummary = true) else startPing()
        }
    }

    override fun onStop() {
        stopPing(showSummary = false)
        super.onStop()
    }

    private fun durationSec(): Int? = when (durationGroup.checkedRadioButtonId) {
        R.id.pingDuration1m -> 60
        R.id.pingDuration2m -> 120
        R.id.pingDuration5m -> 300
        else -> null
    }

    private fun startPing() {
        val raw = hostInput.text?.toString().orEmpty()
        val hosts = PingMonitor.parseHostList(raw)
        PingHistory.remember(this, raw, hosts)
        renderRecentChips()

        logLines.clear()
        logView.text = "—"
        lastReport = ""
        hostInput.isEnabled = false
        setRecentEnabled(false)
        durationGroup.isEnabled = false
        toggleButton.text = getString(R.string.tool_stop)
        startedAtMs = System.currentTimeMillis()

        val timed = durationSec()
        statusLabel.text = if (timed != null) {
            "Stability test · ${timed}s · ${hosts.size} host${if (hosts.size == 1) "" else "s"}"
        } else {
            "Live ping · ${hosts.size} host${if (hosts.size == 1) "" else "s"}"
        }

        job = lifecycleScope.launch {
            PingMonitor.streamMany(hosts)
                .catch { e ->
                    statusLabel.text = "Ping error: ${e.message ?: "failed"}"
                    stopPing(showSummary = true)
                }
                .collect { snapshot ->
                    lastSnapshot = snapshot
                    renderSnapshot(snapshot)
                }
        }

        timed?.let { sec ->
            timeoutJob = lifecycleScope.launch {
                delay(sec * 1000L)
                if (job?.isActive == true) {
                    stopPing(showSummary = true)
                }
            }
        }
    }

    private fun stopPing(showSummary: Boolean) {
        job?.cancel()
        job = null
        timeoutJob?.cancel()
        timeoutJob = null
        hostInput.isEnabled = true
        setRecentEnabled(true)
        durationGroup.isEnabled = true
        toggleButton.text = getString(R.string.ping_start)

        if (showSummary && lastSnapshot != null) {
            val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000.0
            lastReport = formatReport(lastSnapshot!!.hosts, elapsed)
            NetworkSession.recordPing(
                this,
                hostInput.text?.toString().orEmpty(),
                lastReport.lines().drop(2).take(2).joinToString(" · ")
            )
            statusLabel.text = "Finished · ${String.format(Locale.US, "%.0fs", elapsed)}"
            logLines.addFirst("——— Summary ———")
            logLines.addFirst(lastReport)
            logView.text = logLines.joinToString("\n")
        } else if (statusLabel.text.toString().startsWith("Live") ||
            statusLabel.text.toString().startsWith("Stability") ||
            statusLabel.text.toString().startsWith("Pinging")
        ) {
            statusLabel.text = "Stopped"
        }
    }

    private fun share() {
        val body = lastReport.ifBlank {
            lastSnapshot?.hosts?.let { formatReport(it, (System.currentTimeMillis() - startedAtMs) / 1000.0) }
        }
        if (!body.isNullOrBlank()) {
            TestResultExport.shareText(this, "Ping & loss", body)
        }
    }

    private fun formatReport(hosts: List<PingHostStats>, elapsedSec: Double): String = buildString {
        appendLine("Ping & loss")
        appendLine(String.format(Locale.US, "Duration: %.0f s", elapsedSec))
        hosts.forEach { host ->
            appendLine()
            appendLine(host.displayTarget)
            appendLine(
                String.format(
                    Locale.US,
                    "Sent %d · Recv %d · Loss %.1f%%",
                    host.sent,
                    host.received,
                    host.lossPercent
                )
            )
            appendLine(
                String.format(
                    Locale.US,
                    "Latency avg %s ms · min %s · max %s (%s)",
                    formatMs(host.avgMs),
                    formatMs(host.minMs),
                    formatMs(host.maxMs),
                    host.method
                )
            )
        }
    }.trim()

    private fun renderRecentChips() {
        val recent = PingHistory.recentHosts(this)
        recentRow.removeAllViews()
        if (recent.isEmpty()) {
            recentLabel.visibility = View.GONE
            recentScroll.visibility = View.GONE
            return
        }
        recentLabel.visibility = View.VISIBLE
        recentScroll.visibility = View.VISIBLE
        val selectedColor = ContextCompat.getColor(this, R.color.accent_teal)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        recent.forEach { host ->
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = host
                isAllCaps = false
                textSize = 12f
                minimumHeight = 0
                minHeight = 0
                setTextColor(muted)
                strokeWidth = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
                setOnClickListener {
                    if (job?.isActive == true) return@setOnClickListener
                    hostInput.setText(host)
                    hostInput.setSelection(host.length)
                    setTextColor(selectedColor)
                }
            }
            recentRow.addView(button)
        }
    }

    private fun setRecentEnabled(enabled: Boolean) {
        for (i in 0 until recentRow.childCount) {
            recentRow.getChildAt(i).isEnabled = enabled
        }
    }

    private fun renderSnapshot(snapshot: MultiPingSnapshot) {
        snapshot.logLine?.let { line ->
            logLines.addFirst(line.message)
            while (logLines.size > 80) logLines.removeLast()
            logView.text = logLines.joinToString("\n")
        }
        hostStatsAdapter.submitList(snapshot.hosts)
        val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000.0
        val alive = snapshot.hosts.count { it.received > 0 }
        statusLabel.text = if (durationSec() != null) {
            String.format(
                Locale.US,
                "Stability · %.0fs · %d/%d responding",
                elapsed,
                alive,
                snapshot.hosts.size
            )
        } else {
            "Live · ${snapshot.hosts.size} targets · $alive responding"
        }
    }

    private fun formatMs(value: Double?): String =
        if (value == null || !value.isFinite()) "—" else String.format(Locale.US, "%.0f", value)
}

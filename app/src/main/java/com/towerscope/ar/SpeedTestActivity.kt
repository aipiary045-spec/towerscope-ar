package com.towerscope.ar

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.SpeedPhase
import com.towerscope.ar.network.SpeedProgress
import com.towerscope.ar.network.SpeedTestClient
import com.towerscope.ar.ui.LatencyGraphView
import com.towerscope.ar.ui.SpeedGaugeView
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-server download / upload / latency / jitter field check.
 */
class SpeedTestActivity : AppCompatActivity() {

    private lateinit var downloadLabel: TextView
    private lateinit var uploadLabel: TextView
    private lateinit var latencyLabel: TextView
    private lateinit var jitterLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var serverDetail: TextView
    private lateinit var serverRow: LinearLayout
    private lateinit var gauge: SpeedGaugeView
    private lateinit var graph: LatencyGraphView
    private lateinit var runButton: MaterialButton
    private var runningJob: Job? = null
    private var bestDownloadMbps: Double = Double.NaN
    private var selectedServerId: String = SpeedTestClient.AUTO_SERVER_ID
    private val serverButtons = linkedMapOf<String, MaterialButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_speed_test)
        SystemBars.apply(findViewById(R.id.speedRoot))

        downloadLabel = findViewById(R.id.speedDownload)
        uploadLabel = findViewById(R.id.speedUpload)
        latencyLabel = findViewById(R.id.speedLatency)
        jitterLabel = findViewById(R.id.speedJitter)
        statusLabel = findViewById(R.id.speedStatus)
        serverDetail = findViewById(R.id.speedServerDetail)
        serverRow = findViewById(R.id.speedServerRow)
        gauge = findViewById(R.id.speedGauge)
        graph = findViewById(R.id.speedGraph)
        runButton = findViewById(R.id.speedRunButton)

        buildServerPicker()
        runButton.setOnClickListener { runTest() }
        findViewById<MaterialButton>(R.id.speedBackButton).setOnClickListener {
            runningJob?.cancel()
            finish()
        }
    }

    override fun onDestroy() {
        runningJob?.cancel()
        super.onDestroy()
    }

    private fun buildServerPicker() {
        serverRow.removeAllViews()
        serverButtons.clear()
        addServerChip(SpeedTestClient.AUTO_SERVER_ID, "Auto", "Picks lowest latency (prefers up+down)")
        SpeedTestClient.servers.forEach { server ->
            addServerChip(server.id, server.label, server.detail)
        }
        selectServer(SpeedTestClient.AUTO_SERVER_ID)
    }

    private fun addServerChip(id: String, label: String, detail: String) {
        val button = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minimumHeight = 0
            minHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener {
                if (runningJob?.isActive == true) return@setOnClickListener
                selectServer(id)
            }
            tag = detail
        }
        serverButtons[id] = button
        serverRow.addView(button)
    }

    private fun selectServer(id: String) {
        selectedServerId = id
        val selectedColor = ContextCompat.getColor(this, R.color.accent_teal)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        serverButtons.forEach { (chipId, button) ->
            val selected = chipId == id
            button.strokeWidth = if (selected) 3 else 1
            button.setTextColor(if (selected) selectedColor else muted)
            if (selected) {
                serverDetail.text = button.tag as? String ?: ""
            }
        }
        if (runningJob?.isActive != true) {
            statusLabel.text = "Ready · ${serverButtons[id]?.text ?: id}"
        }
    }

    private fun runTest() {
        if (runningJob?.isActive == true) {
            runningJob?.cancel()
            runButton.text = "Run speed test"
            statusLabel.text = "Stopped"
            setServerPickerEnabled(true)
            return
        }
        runButton.text = "Stop"
        downloadLabel.text = "…"
        uploadLabel.text = "…"
        latencyLabel.text = "…"
        jitterLabel.text = "…"
        statusLabel.text = "Starting…"
        gauge.reset()
        graph.clear()
        bestDownloadMbps = Double.NaN
        setServerPickerEnabled(false)

        val serverId = selectedServerId
        runningJob = lifecycleScope.launch {
            try {
                val result = SpeedTestClient.run(serverId) { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        applyProgress(progress)
                    }
                }
                latencyLabel.text = SpeedTestClient.formatLatency(result.latencyMs)
                jitterLabel.text = SpeedTestClient.formatJitter(result.jitterMs)
                downloadLabel.text = SpeedTestClient.formatMbps(result.downloadMbps)
                uploadLabel.text = if (result.uploadMbps.isFinite()) {
                    SpeedTestClient.formatMbps(result.uploadMbps)
                } else {
                    "n/a"
                }
                val show = when {
                    result.downloadMbps.isFinite() -> result.downloadMbps
                    result.uploadMbps.isFinite() -> result.uploadMbps
                    else -> 0.0
                }
                gauge.setPhase("DONE", ContextCompat.getColor(this@SpeedTestActivity, R.color.status_clear))
                gauge.setSpeed(show)
                val parts = buildList {
                    if (result.latencyMs.isFinite()) add("latency")
                    if (result.jitterMs.isFinite()) add("jitter")
                    if (result.downloadMbps.isFinite()) add("down")
                    if (result.uploadMbps.isFinite()) add("up")
                }
                statusLabel.text = if (parts.isEmpty()) {
                    "No usable results — check mobile data / Wi‑Fi"
                } else {
                    "Done · ${result.serverLabel} · ${parts.joinToString(" · ")}"
                }
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                if (detail.contains("Job", ignoreCase = true) ||
                    detail.contains("cancel", ignoreCase = true)
                ) {
                    statusLabel.text = "Stopped"
                } else {
                    statusLabel.text = "Speed test failed: $detail"
                }
                if (downloadLabel.text == "…") downloadLabel.text = "—"
                if (uploadLabel.text == "…") uploadLabel.text = "—"
                if (latencyLabel.text == "…") latencyLabel.text = "—"
                if (jitterLabel.text == "…") jitterLabel.text = "—"
            } finally {
                runButton.text = "Run speed test"
                runningJob = null
                setServerPickerEnabled(true)
            }
        }
    }

    private fun setServerPickerEnabled(enabled: Boolean) {
        serverButtons.values.forEach { it.isEnabled = enabled }
    }

    private fun applyProgress(progress: SpeedProgress) {
        statusLabel.text = progress.message
        progress.serverLabel?.let { label ->
            if (selectedServerId == SpeedTestClient.AUTO_SERVER_ID) {
                serverDetail.text = "Auto → $label"
            }
        }
        when (progress.phase) {
            SpeedPhase.PICK -> {
                gauge.setPhase(
                    "PICK",
                    ContextCompat.getColor(this, R.color.text_primary)
                )
                progress.latencyMs?.let {
                    latencyLabel.text = SpeedTestClient.formatLatency(it)
                }
            }
            SpeedPhase.LATENCY -> {
                gauge.setPhase(
                    "PING",
                    ContextCompat.getColor(this, R.color.text_primary)
                )
                progress.latencyMs?.let {
                    latencyLabel.text = SpeedTestClient.formatLatency(it)
                    gauge.setSpeed(0.0)
                }
            }
            SpeedPhase.DOWNLOAD -> {
                gauge.setPhase(
                    "DOWN",
                    ContextCompat.getColor(this, R.color.accent_teal)
                )
                progress.jitterMs?.let {
                    jitterLabel.text = SpeedTestClient.formatJitter(it)
                }
                progress.latencyMs?.let {
                    latencyLabel.text = SpeedTestClient.formatLatency(it)
                }
                val live = progress.liveMbps ?: progress.phaseMbps
                if (live != null && live.isFinite()) {
                    gauge.setSpeed(live)
                    graph.addSample(live)
                }
                progress.phaseMbps?.takeIf { it.isFinite() }?.let {
                    bestDownloadMbps = it
                    downloadLabel.text = SpeedTestClient.formatMbps(it)
                }
            }
            SpeedPhase.UPLOAD -> {
                gauge.setPhase(
                    "UP",
                    ContextCompat.getColor(this, R.color.accent_yellow)
                )
                if (bestDownloadMbps.isFinite()) {
                    downloadLabel.text = SpeedTestClient.formatMbps(bestDownloadMbps)
                }
                val live = progress.liveMbps
                if (live != null && live.isFinite()) {
                    gauge.setSpeed(live)
                    graph.addSample(live)
                }
                val uploadBest = progress.phaseMbps?.takeIf { it.isFinite() }
                uploadLabel.text = SpeedTestClient.formatMbps(uploadBest ?: live ?: Double.NaN)
            }
            SpeedPhase.DONE -> {
                gauge.setPhase(
                    "DONE",
                    ContextCompat.getColor(this, R.color.status_clear)
                )
                progress.jitterMs?.let {
                    jitterLabel.text = SpeedTestClient.formatJitter(it)
                }
            }
        }
    }
}

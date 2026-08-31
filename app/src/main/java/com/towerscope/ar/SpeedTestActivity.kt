package com.towerscope.ar

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.towerscope.ar.network.BufferbloatRating
import com.towerscope.ar.network.BufferbloatTest
import com.towerscope.ar.network.NetworkSession
import com.towerscope.ar.network.SpeedPhase
import com.towerscope.ar.network.SpeedProgress
import com.towerscope.ar.network.SpeedTestClient
import com.towerscope.ar.network.SpeedTestResult
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.ui.LatencyGraphView
import com.towerscope.ar.ui.SpeedGaugeView
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Multi-server download / upload / latency / jitter, with optional bufferbloat check.
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
    private lateinit var includeBufferbloat: MaterialCheckBox
    private lateinit var bufferbloatSection: LinearLayout
    private lateinit var bufferbloatStatus: TextView
    private lateinit var bufferbloatResult: TextView
    private lateinit var runButton: MaterialButton
    private var runningJob: Job? = null
    private var bestDownloadMbps: Double = Double.NaN
    private var selectedServerId: String = SpeedTestClient.AUTO_SERVER_ID
    private val serverButtons = linkedMapOf<String, MaterialButton>()
    private var lastReport = ""
    private var speedResult: SpeedTestResult? = null

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
        includeBufferbloat = findViewById(R.id.speedIncludeBufferbloat)
        bufferbloatSection = findViewById(R.id.speedBufferbloatSection)
        bufferbloatResult = findViewById(R.id.speedBufferbloatResult)
        bufferbloatStatus = findViewById(R.id.speedBufferbloatStatus)
        runButton = findViewById(R.id.speedRunButton)

        buildServerPicker()
        includeBufferbloat.setOnCheckedChangeListener { _, checked ->
            bufferbloatSection.isVisible = checked
        }
        bufferbloatSection.isVisible = includeBufferbloat.isChecked

        runButton.setOnClickListener { runTest() }
        findViewById<MaterialButton>(R.id.toolShareButton).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.toolBackButton).setOnClickListener {
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
            runButton.text = getString(R.string.speed_run_test)
            statusLabel.text = "Stopped"
            setControlsEnabled(true)
            return
        }
        runButton.text = getString(R.string.tool_stop)
        downloadLabel.text = "…"
        uploadLabel.text = "…"
        latencyLabel.text = "…"
        jitterLabel.text = "…"
        statusLabel.text = "Starting…"
        gauge.reset()
        graph.clear()
        bestDownloadMbps = Double.NaN
        lastReport = ""
        speedResult = null
        resetBufferbloatUi()
        setControlsEnabled(false)

        val serverId = selectedServerId
        val withBufferbloat = includeBufferbloat.isChecked
        runningJob = lifecycleScope.launch {
            try {
                val result = SpeedTestClient.run(serverId) { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        applyProgress(progress)
                    }
                }
                speedResult = result
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
                } else if (withBufferbloat) {
                    "Speed done · ${result.serverLabel} · checking bufferbloat…"
                } else {
                    "Done · ${result.serverLabel} · ${parts.joinToString(" · ")}"
                }
                lastReport = formatSpeedReport(result)
                if (result.downloadMbps.isFinite() || result.uploadMbps.isFinite()) {
                    NetworkSession.recordSpeedTest(
                        this@SpeedTestActivity,
                        result.downloadMbps,
                        result.uploadMbps,
                        result.latencyMs
                    )
                }

                if (withBufferbloat) {
                    runBufferbloatCheck()
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
                runButton.text = getString(R.string.speed_run_test)
                runningJob = null
                setControlsEnabled(true)
            }
        }
    }

    private suspend fun runBufferbloatCheck() {
        val result = BufferbloatTest.runOnce("1.1.1.1") { progress ->
            withContext(Dispatchers.Main) {
                bufferbloatStatus.text = progress.phase
                progress.spikeMs?.let { spike ->
                    bufferbloatResult.text = buildString {
                        progress.idleAvgMs?.let {
                            append(String.format(Locale.US, "Idle avg %.0f ms", it))
                        }
                        progress.loadedAvgMs?.let {
                            if (isNotEmpty()) append("  ·  ")
                            append(String.format(Locale.US, "loaded avg %.0f ms", it))
                        }
                        append(String.format(Locale.US, "  ·  +%.0f ms", spike))
                    }
                }
            }
        }
        val color = when (result.rating) {
            BufferbloatRating.GOOD -> R.color.status_clear
            BufferbloatRating.FAIR -> R.color.accent_yellow
            BufferbloatRating.POOR -> R.color.status_blocked
        }
        bufferbloatStatus.setTextColor(getColor(color))
        bufferbloatStatus.text = BufferbloatTest.ratingLabel(result.rating)
        bufferbloatResult.text = BufferbloatTest.formatResult(result)
        lastReport = buildString {
            append(lastReport)
            append("\n\n")
            append(BufferbloatTest.formatResult(result))
        }.trim()
        speedResult?.let { speed ->
            val parts = buildList {
                if (speed.latencyMs.isFinite()) add("latency")
                if (speed.jitterMs.isFinite()) add("jitter")
                if (speed.downloadMbps.isFinite()) add("down")
                if (speed.uploadMbps.isFinite()) add("up")
                add("bufferbloat")
            }
            statusLabel.text = "Done · ${speed.serverLabel} · ${parts.joinToString(" · ")}"
        }
    }

    private fun resetBufferbloatUi() {
        bufferbloatStatus.setTextColor(getColor(R.color.text_muted))
        bufferbloatStatus.text = getString(R.string.speed_bufferbloat_waiting)
        bufferbloatResult.text = getString(R.string.speed_bufferbloat_hint)
    }

    private fun formatSpeedReport(result: SpeedTestResult): String = buildString {
        appendLine("Speed test · ${result.serverLabel}")
        if (result.latencyMs.isFinite()) {
            appendLine("Latency: ${SpeedTestClient.formatLatency(result.latencyMs)}")
        }
        if (result.jitterMs.isFinite()) {
            appendLine("Jitter: ${SpeedTestClient.formatJitter(result.jitterMs)}")
        }
        if (result.downloadMbps.isFinite()) {
            appendLine("Download: ${SpeedTestClient.formatMbps(result.downloadMbps)}")
        }
        if (result.uploadMbps.isFinite()) {
            appendLine("Upload: ${SpeedTestClient.formatMbps(result.uploadMbps)}")
        }
    }.trim()

    private fun share() {
        if (lastReport.isNotBlank()) {
            TestResultExport.shareText(this, "Speed test", lastReport)
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        serverButtons.values.forEach { it.isEnabled = enabled }
        includeBufferbloat.isEnabled = enabled
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

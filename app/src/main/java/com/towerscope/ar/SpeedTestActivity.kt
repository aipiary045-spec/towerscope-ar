package com.towerscope.ar

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.SpeedTestClient
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Field download / upload / latency check via Cloudflare speed endpoints.
 */
class SpeedTestActivity : AppCompatActivity() {

    private lateinit var downloadLabel: TextView
    private lateinit var uploadLabel: TextView
    private lateinit var latencyLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var runButton: MaterialButton
    private var runningJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_speed_test)
        SystemBars.apply(findViewById(R.id.speedRoot))

        downloadLabel = findViewById(R.id.speedDownload)
        uploadLabel = findViewById(R.id.speedUpload)
        latencyLabel = findViewById(R.id.speedLatency)
        statusLabel = findViewById(R.id.speedStatus)
        runButton = findViewById(R.id.speedRunButton)

        runButton.setOnClickListener { runTest() }
        findViewById<MaterialButton>(R.id.speedBackButton).setOnClickListener { finish() }
    }

    override fun onDestroy() {
        runningJob?.cancel()
        super.onDestroy()
    }

    private fun runTest() {
        if (runningJob?.isActive == true) return
        runButton.isEnabled = false
        downloadLabel.text = "…"
        uploadLabel.text = "…"
        latencyLabel.text = "…"
        statusLabel.text = "Starting…"
        runningJob = lifecycleScope.launch {
            try {
                val result = SpeedTestClient.run { message ->
                    withContext(Dispatchers.Main.immediate) {
                        statusLabel.text = message
                    }
                }
                latencyLabel.text = SpeedTestClient.formatLatency(result.latencyMs)
                downloadLabel.text = SpeedTestClient.formatMbps(result.downloadMbps)
                uploadLabel.text = SpeedTestClient.formatMbps(result.uploadMbps)
                val parts = buildList {
                    if (result.latencyMs.isFinite()) add("latency")
                    if (result.downloadMbps.isFinite()) add("down")
                    if (result.uploadMbps.isFinite()) add("up")
                }
                statusLabel.text = if (parts.isEmpty()) {
                    "No usable results — check mobile data / Wi‑Fi"
                } else {
                    "Done · Cloudflare (${parts.joinToString(" · ")})"
                }
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                statusLabel.text = "Speed test failed: $detail"
                if (downloadLabel.text == "…") downloadLabel.text = "—"
                if (uploadLabel.text == "…") uploadLabel.text = "—"
                if (latencyLabel.text == "…") latencyLabel.text = "—"
            } finally {
                runButton.isEnabled = true
            }
        }
    }
}

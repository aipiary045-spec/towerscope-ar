package com.towerscope.ar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.DiagnoseLayer
import com.towerscope.ar.network.DiagnoseLayerResult
import com.towerscope.ar.network.DiagnoseReport
import com.towerscope.ar.network.DiagnoseStatus
import com.towerscope.ar.network.NetworkDiagnose
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Layered path diagnose: link → DNS → TCP → TLS → HTTP.
 * Answers "where did it break?" without leaving Network Hub patterns.
 */
class NetworkDiagnoseActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var runButton: MaterialButton
    private lateinit var verdictLabel: TextView
    private lateinit var fixHintLabel: TextView
    private lateinit var layersContainer: LinearLayout
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_network_diagnose)
        SystemBars.apply(findViewById(R.id.diagnoseRoot))

        hostInput = findViewById(R.id.diagnoseHostInput)
        runButton = findViewById(R.id.diagnoseRunButton)
        verdictLabel = findViewById(R.id.diagnoseVerdict)
        fixHintLabel = findViewById(R.id.diagnoseFixHint)
        layersContainer = findViewById(R.id.diagnoseLayersContainer)

        runButton.setOnClickListener { startDiagnose() }
        hostInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startDiagnose()
                true
            } else {
                false
            }
        }
        findViewById<MaterialButton>(R.id.diagnoseBackButton).setOnClickListener {
            job?.cancel()
            finish()
        }

        renderIdleLayers()
    }

    override fun onStop() {
        job?.cancel()
        job = null
        super.onStop()
    }

    private fun startDiagnose() {
        job?.cancel()
        val host = hostInput.text?.toString().orEmpty()
        hostInput.isEnabled = false
        runButton.isEnabled = false
        runButton.text = "Checking…"
        verdictLabel.text = "Checking path…"
        verdictLabel.setTextColor(getColor(R.color.accent_teal))
        fixHintLabel.isVisible = false

        job = lifecycleScope.launch {
            NetworkDiagnose.run(this@NetworkDiagnoseActivity, host)
                .catch { e ->
                    verdictLabel.text = "Path Doctor error"
                    verdictLabel.setTextColor(getColor(R.color.chip_poor))
                    fixHintLabel.text = e.message ?: "Unexpected failure"
                    fixHintLabel.isVisible = true
                    finishRunUi()
                }
                .collect { report -> renderReport(report) }
            finishRunUi()
        }
    }

    private fun finishRunUi() {
        hostInput.isEnabled = true
        runButton.isEnabled = true
        runButton.text = "Run Path Doctor"
        job = null
    }

    private fun renderReport(report: DiagnoseReport) {
        verdictLabel.text = report.summary
        verdictLabel.setTextColor(
            getColor(
                when {
                    report.brokeAt != null -> R.color.accent_yellow
                    report.layers.any { it.status == DiagnoseStatus.RUNNING } -> R.color.accent_teal
                    else -> R.color.status_clear
                }
            )
        )
        val hint = report.fixHint
        fixHintLabel.isVisible = !hint.isNullOrBlank()
        fixHintLabel.text = hint.orEmpty()
        renderLayers(report.layers)
    }

    private fun renderIdleLayers() {
        val idle = listOf(
            DiagnoseLayerResult(
                layer = DiagnoseLayer.LINK,
                status = DiagnoseStatus.PENDING,
                title = "Local link",
                detail = "Waiting"
            ),
            DiagnoseLayerResult(
                layer = DiagnoseLayer.DNS,
                status = DiagnoseStatus.PENDING,
                title = "DNS",
                detail = "Waiting"
            ),
            DiagnoseLayerResult(
                layer = DiagnoseLayer.TCP,
                status = DiagnoseStatus.PENDING,
                title = "TCP",
                detail = "Waiting"
            ),
            DiagnoseLayerResult(
                layer = DiagnoseLayer.TLS,
                status = DiagnoseStatus.PENDING,
                title = "TLS",
                detail = "Waiting"
            ),
            DiagnoseLayerResult(
                layer = DiagnoseLayer.HTTP,
                status = DiagnoseStatus.PENDING,
                title = "HTTP",
                detail = "Waiting"
            )
        )
        renderLayers(idle)
    }

    private fun renderLayers(layers: List<DiagnoseLayerResult>) {
        layersContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        layers.forEachIndexed { index, layer ->
            val row = inflater.inflate(R.layout.item_diagnose_layer_row, layersContainer, false)
            if (index > 0) {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (10 * resources.displayMetrics.density).toInt()
                row.layoutParams = lp
            }
            bindLayerRow(row, layer)
            layersContainer.addView(row)
        }
    }

    private fun bindLayerRow(row: android.view.View, layer: DiagnoseLayerResult) {
        val statusView = row.findViewById<TextView>(R.id.diagnoseLayerStatus)
        val titleView = row.findViewById<TextView>(R.id.diagnoseLayerTitle)
        val detailView = row.findViewById<TextView>(R.id.diagnoseLayerDetail)
        val latencyView = row.findViewById<TextView>(R.id.diagnoseLayerLatency)

        titleView.text = layer.title
        detailView.text = layer.detail
        latencyView.text = layer.latencyMs?.let {
            String.format(Locale.US, "%.0f ms", it)
        }.orEmpty()

        when (layer.status) {
            DiagnoseStatus.PENDING -> {
                statusView.text = "·"
                statusView.setTextColor(getColor(R.color.text_muted))
            }
            DiagnoseStatus.RUNNING -> {
                statusView.text = "…"
                statusView.setTextColor(getColor(R.color.accent_teal))
            }
            DiagnoseStatus.PASS -> {
                statusView.text = "✓"
                statusView.setTextColor(getColor(R.color.status_clear))
            }
            DiagnoseStatus.FAIL -> {
                statusView.text = "✕"
                statusView.setTextColor(getColor(R.color.chip_poor))
            }
            DiagnoseStatus.SKIPPED -> {
                statusView.text = "–"
                statusView.setTextColor(getColor(R.color.text_dim))
            }
        }
    }
}

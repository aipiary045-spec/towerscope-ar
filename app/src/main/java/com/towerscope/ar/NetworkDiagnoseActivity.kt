package com.towerscope.ar

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.DiagnoseLayer
import com.towerscope.ar.network.DiagnoseLayerResult
import com.towerscope.ar.network.DiagnoseReport
import com.towerscope.ar.network.DiagnoseStatus
import com.towerscope.ar.network.NetworkDiagnose
import com.towerscope.ar.ui.DiagnoseLayerAdapter
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.ToolScaffold
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Layered path diagnose: link → DNS → TCP → TLS → HTTP.
 * Answers "where did it break?" without leaving Network Hub patterns.
 */
class NetworkDiagnoseActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var runButton: MaterialButton
    private lateinit var verdictLabel: TextView
    private lateinit var fixHintLabel: TextView
    private lateinit var layersList: RecyclerView
    private lateinit var layersAdapter: DiagnoseLayerAdapter
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
        layersList = findViewById(R.id.diagnoseLayersContainer)
        layersAdapter = DiagnoseLayerAdapter()
        layersList.apply {
            layoutManager = LinearLayoutManager(this@NetworkDiagnoseActivity)
            adapter = layersAdapter
            itemAnimator = null
        }
        ToolScaffold.bind(
            activity = this,
            titleRes = R.string.home_job_diagnose,
            subtitleRes = R.string.home_job_diagnose_sub
        )

        runButton.setOnClickListener { startDiagnose() }
        hostInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startDiagnose()
                true
            } else {
                false
            }
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
        layersAdapter.submitList(layers)
    }
}

package com.towerscope.ar

import android.os.Bundle
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.TraceRoute
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.ToolScaffold
import com.towerscope.ar.ui.ToolTopology
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class TraceRouteActivity : AppCompatActivity() {

    private lateinit var targetInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var hopView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var toggleButton: MaterialButton
    private var job: Job? = null
    private val lines = ArrayDeque<String>(40)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_traceroute)
        SystemBars.apply(findViewById(R.id.traceRoot))
        ToolTopology.bindWhenResumed(this, findViewById(R.id.traceRoot))

        ToolScaffold.bind(
            activity = this,
            titleRes = R.string.home_job_traceroute,
            subtitleRes = R.string.home_job_traceroute_sub
        )

        targetInput = findViewById(R.id.traceTargetInput)
        statusLabel = findViewById(R.id.traceStatus)
        hopView = findViewById(R.id.traceHopView)
        scroll = findViewById(R.id.traceScroll)
        toggleButton = findViewById(R.id.traceToggleButton)

        toggleButton.setOnClickListener {
            if (job?.isActive == true) stopTrace() else startTrace()
        }
    }

    override fun onStop() {
        stopTrace()
        super.onStop()
    }

    private fun startTrace() {
        val target = targetInput.text?.toString().orEmpty()
        lines.clear()
        hopView.text = "—"
        targetInput.isEnabled = false
        toggleButton.text = "Stop"
        statusLabel.text = "Tracing ${TraceRoute.parseTarget(target)}…"
        job = lifecycleScope.launch {
            TraceRoute.run(target)
                .catch { e ->
                    statusLabel.text = "Trace error: ${e.message ?: "failed"}"
                    stopTrace()
                }
                .collect { hop ->
                    lines.addLast(TraceRoute.formatHop(hop))
                    hopView.text = lines.joinToString("\n")
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    statusLabel.text = "Hop ${hop.ttl} · ${hop.status}"
                    if (hop.status == "reached") {
                        statusLabel.text = "Reached · ${hop.ip ?: hop.host ?: "ok"}"
                    }
                }
            if (job?.isActive == true) {
                statusLabel.text = "Done · ${lines.size} hops"
                stopTrace(keepStatus = true)
            }
        }
    }

    private fun stopTrace(keepStatus: Boolean = false) {
        job?.cancel()
        job = null
        targetInput.isEnabled = true
        toggleButton.text = "Start traceroute"
        if (!keepStatus) {
            val current = statusLabel.text.toString()
            if (current.startsWith("Tracing") || current.startsWith("Hop")) {
                statusLabel.text = "Stopped"
            }
        }
    }
}

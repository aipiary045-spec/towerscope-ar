package com.towerscope.ar

import android.os.Bundle
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.StabilityPing
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class StabilityTestActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var durationBar: SeekBar
    private lateinit var durationLabel: TextView
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var toggleButton: MaterialButton
    private var job: Job? = null
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_stability_test)
        SystemBars.apply(findViewById(R.id.stabilityRoot))

        hostInput = findViewById(R.id.stabilityHostInput)
        durationBar = findViewById(R.id.stabilityDurationBar)
        durationLabel = findViewById(R.id.stabilityDurationLabel)
        statusView = findViewById(R.id.stabilityStatus)
        resultView = findViewById(R.id.stabilityResult)
        toggleButton = findViewById(R.id.stabilityToggleButton)

        hostInput.setText("1.1.1.1")
        durationBar.max = 4
        durationBar.progress = 1
        updateDurationLabel(1)

        durationBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateDurationLabel(progress.coerceAtLeast(1))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        toggleButton.setOnClickListener { toggle() }
        findViewById<MaterialButton>(R.id.toolShareButton).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.toolBackButton).setOnClickListener {
            job?.cancel()
            finish()
        }
    }

    override fun onStop() {
        job?.cancel()
        super.onStop()
    }

    private fun durationSec(): Int = when (durationBar.progress.coerceAtLeast(1)) {
        1 -> 60
        2 -> 120
        3 -> 180
        else -> 300
    }

    private fun updateDurationLabel(progress: Int) {
        durationLabel.text = "${durationSec()} seconds"
    }

    private fun toggle() {
        if (job?.isActive == true) {
            job?.cancel()
            return
        }
        val host = hostInput.text?.toString().orEmpty()
        hostInput.isEnabled = false
        durationBar.isEnabled = false
        toggleButton.text = getString(R.string.tool_stop)
        statusView.text = "Running…"
        job = lifecycleScope.launch {
            var last = com.towerscope.ar.network.StabilityProgress(
                0, 0, 0.0, null, null, null, null, 0.0, host, true
            )
            StabilityPing.run(host, durationSec()).collect { progress ->
                last = progress
                statusView.text = String.format(
                    Locale.US,
                    "%.0fs · loss %.1f%% · avg %.0f ms",
                    progress.elapsedSec,
                    progress.lossPercent,
                    progress.avgMs ?: 0.0
                )
                if (!progress.running) {
                    val result = StabilityPing.toResult(progress)
                    lastReport = StabilityPing.formatResult(result)
                    resultView.text = lastReport
                    hostInput.isEnabled = true
                    durationBar.isEnabled = true
                    toggleButton.text = getString(R.string.tool_run)
                }
            }
            if (last.running) {
                val result = StabilityPing.toResult(last.copy(running = false))
                lastReport = StabilityPing.formatResult(result)
                resultView.text = lastReport
                hostInput.isEnabled = true
                durationBar.isEnabled = true
                toggleButton.text = getString(R.string.tool_run)
            }
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) TestResultExport.shareText(this, "Stability test", lastReport)
    }
}

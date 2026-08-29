package com.towerscope.ar

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.BufferbloatRating
import com.towerscope.ar.network.BufferbloatTest
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BufferbloatActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var runButton: MaterialButton
    private var job: Job? = null
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_bufferbloat)
        SystemBars.apply(findViewById(R.id.bufferbloatRoot))

        hostInput = findViewById(R.id.bufferbloatHostInput)
        statusView = findViewById(R.id.bufferbloatStatus)
        resultView = findViewById(R.id.bufferbloatResult)
        runButton = findViewById(R.id.bufferbloatRunButton)
        hostInput.setText("1.1.1.1")

        runButton.setOnClickListener { runTest() }
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

    private fun runTest() {
        job?.cancel()
        val host = hostInput.text?.toString().orEmpty().ifBlank { "1.1.1.1" }
        runButton.isEnabled = false
        hostInput.isEnabled = false
        job = lifecycleScope.launch {
            val result = BufferbloatTest.runOnce(host) { progress ->
                withContext(Dispatchers.Main) {
                    statusView.text = progress.phase
                }
            }
            lastReport = BufferbloatTest.formatResult(result)
            resultView.text = lastReport
            val color = when (result.rating) {
                BufferbloatRating.GOOD -> R.color.status_clear
                BufferbloatRating.FAIR -> R.color.accent_yellow
                BufferbloatRating.POOR -> R.color.status_blocked
            }
            statusView.setTextColor(getColor(color))
            statusView.text = BufferbloatTest.ratingLabel(result.rating)
            runButton.isEnabled = true
            hostInput.isEnabled = true
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) TestResultExport.shareText(this, "Bufferbloat test", lastReport)
    }
}

package com.towerscope.ar

import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.TcpThroughputTest
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.network.ThroughputDirection
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ThroughputTestActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var directionGroup: RadioGroup
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var runButton: MaterialButton
    private var job: Job? = null
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_throughput_test)
        SystemBars.apply(findViewById(R.id.throughputRoot))

        hostInput = findViewById(R.id.throughputHostInput)
        portInput = findViewById(R.id.throughputPortInput)
        directionGroup = findViewById(R.id.throughputDirectionGroup)
        statusView = findViewById(R.id.throughputStatus)
        resultView = findViewById(R.id.throughputResult)
        runButton = findViewById(R.id.throughputRunButton)

        portInput.setText("5201")

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
        val host = hostInput.text?.toString().orEmpty()
        val port = portInput.text?.toString()?.toIntOrNull() ?: 5201
        val direction = if (directionGroup.checkedRadioButtonId == R.id.throughputUpload) {
            ThroughputDirection.UPLOAD
        } else {
            ThroughputDirection.DOWNLOAD
        }
        runButton.isEnabled = false
        hostInput.isEnabled = false
        portInput.isEnabled = false
        statusView.text = "Running…"
        job = lifecycleScope.launch {
            val result = TcpThroughputTest.run(host, port, direction) { progress ->
                withContext(Dispatchers.Main) {
                    statusView.text = TcpThroughputTest.formatMbps(progress.liveMbps)
                }
            }
            lastReport = buildString {
                appendLine("TCP throughput test")
                appendLine("${result.direction.name.lowercase()} · ${result.host}:${result.port}")
                if (result.error != null) {
                    appendLine("Error: ${result.error}")
                } else {
                    appendLine("Throughput: ${TcpThroughputTest.formatMbps(result.throughputMbps)}")
                    appendLine(String.format(Locale.US, "%d bytes in %d ms", result.bytesTransferred, result.durationMs))
                }
            }
            resultView.text = lastReport
            statusView.text = result.error ?: TcpThroughputTest.formatMbps(result.throughputMbps)
            runButton.isEnabled = true
            hostInput.isEnabled = true
            portInput.isEnabled = true
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) TestResultExport.shareText(this, "TCP throughput", lastReport)
    }
}

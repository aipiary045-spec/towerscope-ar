package com.towerscope.ar

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.ConnectionSnapshotCollector
import com.towerscope.ar.network.LanSpeedTest
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class LanSpeedTestActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var resultView: TextView
    private lateinit var statusView: TextView
    private lateinit var runButton: MaterialButton
    private var job: Job? = null
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_lan_speed_test)
        SystemBars.apply(findViewById(R.id.lanSpeedRoot))

        hostInput = findViewById(R.id.lanSpeedHostInput)
        resultView = findViewById(R.id.lanSpeedResult)
        statusView = findViewById(R.id.lanSpeedStatus)
        runButton = findViewById(R.id.lanSpeedRunButton)

        lifecycleScope.launch {
            val snap = ConnectionSnapshotCollector.collect(this@LanSpeedTestActivity, fetchPublicIp = false)
            snap.gatewayIpv4?.let { hostInput.setText(it) }
        }

        runButton.setOnClickListener { toggleRun() }
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

    private fun toggleRun() {
        if (job?.isActive == true) {
            job?.cancel()
            return
        }
        val host = hostInput.text?.toString().orEmpty()
        runButton.isEnabled = false
        hostInput.isEnabled = false
        statusView.text = "Testing LAN throughput…"
        job = lifecycleScope.launch {
            val result = LanSpeedTest.run(host)
            lastReport = buildString {
                appendLine("LAN speed test")
                appendLine("Target: ${result.targetHost}:${result.targetPort} (${result.method})")
                if (result.error != null) {
                    appendLine("Error: ${result.error}")
                } else {
                    appendLine("Throughput: ${LanSpeedTest.formatMbps(result.throughputMbps)}")
                    appendLine("Transferred: ${result.bytesTransferred} bytes in ${result.durationMs} ms")
                    result.connectMs?.let {
                        appendLine(String.format(Locale.US, "Connect: %.0f ms", it))
                    }
                }
            }
            resultView.text = lastReport
            statusView.text = result.error ?: LanSpeedTest.formatMbps(result.throughputMbps)
            runButton.isEnabled = true
            hostInput.isEnabled = true
            runButton.text = getString(R.string.tool_run)
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) TestResultExport.shareText(this, "LAN speed test", lastReport)
    }
}

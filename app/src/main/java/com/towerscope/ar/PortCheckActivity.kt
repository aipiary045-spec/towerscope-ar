package com.towerscope.ar

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.PortChecker
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.launch

class PortCheckActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_port_check)
        SystemBars.apply(findViewById(R.id.portCheckRoot))

        hostInput = findViewById(R.id.portCheckHostInput)
        portInput = findViewById(R.id.portCheckPortInput)
        statusView = findViewById(R.id.portCheckStatus)
        resultView = findViewById(R.id.portCheckResult)
        portInput.setText("443")

        findViewById<MaterialButton>(R.id.portCheckRunButton).setOnClickListener { check() }
        findViewById<MaterialButton>(R.id.toolShareButton).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.toolBackButton).setOnClickListener { finish() }
    }

    private fun check() {
        val host = hostInput.text?.toString().orEmpty()
        val port = portInput.text?.toString()?.toIntOrNull() ?: 443
        statusView.text = "Checking…"
        lifecycleScope.launch {
            val result = PortChecker.check(host, port)
            lastReport = PortChecker.format(result)
            resultView.text = lastReport
            statusView.text = if (result.open) "Open" else "Closed"
            statusView.setTextColor(
                getColor(if (result.open) R.color.status_clear else R.color.status_blocked)
            )
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) TestResultExport.shareText(this, "Port check", lastReport)
    }
}

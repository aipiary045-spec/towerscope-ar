package com.towerscope.ar

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.network.WakeOnLan
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.launch

class WakeOnLanActivity : AppCompatActivity() {

    private lateinit var macInput: EditText
    private lateinit var broadcastInput: EditText
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_wake_on_lan)
        SystemBars.apply(findViewById(R.id.wolRoot))

        macInput = findViewById(R.id.wolMacInput)
        broadcastInput = findViewById(R.id.wolBroadcastInput)
        statusView = findViewById(R.id.wolStatus)
        resultView = findViewById(R.id.wolResult)
        broadcastInput.setText("255.255.255.255")

        findViewById<MaterialButton>(R.id.wolSendButton).setOnClickListener { send() }
        findViewById<MaterialButton>(R.id.toolShareButton).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.toolBackButton).setOnClickListener { finish() }
    }

    private fun send() {
        val mac = macInput.text?.toString().orEmpty()
        val broadcast = broadcastInput.text?.toString().orEmpty().ifBlank { "255.255.255.255" }
        statusView.text = "Sending…"
        lifecycleScope.launch {
            val result = WakeOnLan.send(mac, broadcast)
            lastReport = buildString {
                appendLine("Wake on LAN")
                appendLine("MAC: ${result.macAddress}")
                appendLine("Broadcast: ${result.broadcastIp}:${result.port}")
                appendLine(if (result.success) "Packet sent" else "Failed: ${result.error}")
            }
            resultView.text = lastReport
            statusView.text = if (result.success) "Sent" else "Failed"
            statusView.setTextColor(
                getColor(if (result.success) R.color.status_clear else R.color.status_blocked)
            )
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) TestResultExport.shareText(this, "Wake on LAN", lastReport)
    }
}

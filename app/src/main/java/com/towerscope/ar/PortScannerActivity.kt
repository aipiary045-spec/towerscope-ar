package com.towerscope.ar

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.PortScanPreset
import com.towerscope.ar.network.PortScanner
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class PortScannerActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var extraPortsInput: EditText
    private lateinit var presetGroup: RadioGroup
    private lateinit var statusView: TextView
    private lateinit var openList: LinearLayout
    private lateinit var runButton: MaterialButton
    private var scanJob: Job? = null
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_port_scanner)
        SystemBars.apply(findViewById(R.id.portScannerRoot))

        hostInput = findViewById(R.id.portScannerHostInput)
        extraPortsInput = findViewById(R.id.portScannerExtraInput)
        presetGroup = findViewById(R.id.portScannerPresetGroup)
        statusView = findViewById(R.id.portScannerStatus)
        openList = findViewById(R.id.portScannerOpenList)
        runButton = findViewById(R.id.portScannerRunButton)

        runButton.setOnClickListener { toggleScan() }
        findViewById<MaterialButton>(R.id.toolShareButton).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.toolBackButton).setOnClickListener {
            scanJob?.cancel()
            finish()
        }
    }

    override fun onStop() {
        scanJob?.cancel()
        super.onStop()
    }

    private fun selectedPreset(): PortScanPreset = when (presetGroup.checkedRadioButtonId) {
        R.id.portScannerPresetWeb -> PortScanPreset.WEB
        R.id.portScannerPresetRouter -> PortScanPreset.ROUTER
        R.id.portScannerPresetExtended -> PortScanPreset.EXTENDED
        else -> PortScanPreset.COMMON
    }

    private fun toggleScan() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            return
        }
        val host = hostInput.text?.toString().orEmpty()
        val ports = PortScanner.portsFor(selectedPreset(), extraPortsInput.text?.toString().orEmpty())
        if (host.isBlank()) {
            statusView.text = "Enter a host"
            return
        }

        openList.removeAllViews()
        lastReport = ""
        hostInput.isEnabled = false
        extraPortsInput.isEnabled = false
        presetGroup.isEnabled = false
        runButton.text = getString(R.string.tool_stop)
        statusView.text = "Scanning 0 / ${ports.size}…"

        scanJob = lifecycleScope.launch {
            var openCount = 0
            val result = PortScanner.scan(host, ports) { scanned, total, hit ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (hit != null) {
                        openCount++
                        addOpenRow(hit.port, hit.service, hit.connectMs)
                    }
                    statusView.text = String.format(
                        Locale.US,
                        "Scanning %d / %d · %d open",
                        scanned,
                        total,
                        openCount
                    )
                }
            }
            lastReport = PortScanner.format(result)
            statusView.text = if (result.openPorts.isEmpty()) {
                "Done · no open ports (${result.portsScanned} scanned)"
            } else {
                "Done · ${result.openPorts.size} open on ${result.host}"
            }
            hostInput.isEnabled = true
            extraPortsInput.isEnabled = true
            presetGroup.isEnabled = true
            runButton.text = getString(R.string.tool_run)
        }
    }

    private fun addOpenRow(port: Int, service: String?, connectMs: Double) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_port_scan_row, openList, false)
        val label = service?.let { "$port ($it)" } ?: port.toString()
        row.findViewById<TextView>(R.id.portScanRowPort).text = label
        row.findViewById<TextView>(R.id.portScanRowMs).text =
            String.format(Locale.US, "%.0f ms", connectMs)
        openList.addView(row, 0)
        findViewById<TextView>(R.id.portScannerOpenHeader).isVisible = true
    }

    private fun share() {
        if (lastReport.isNotBlank()) {
            TestResultExport.shareText(this, "Port scan", lastReport)
        }
    }
}

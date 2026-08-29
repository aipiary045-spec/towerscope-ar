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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val hostOverride = hostInput.text?.toString().orEmpty()
        val ports = PortScanner.portsFor(selectedPreset(), extraPortsInput.text?.toString().orEmpty())
        val singleHost = hostOverride.isNotBlank()

        openList.removeAllViews()
        lastReport = ""
        hostInput.isEnabled = false
        extraPortsInput.isEnabled = false
        presetGroup.isEnabled = false
        runButton.text = getString(R.string.tool_stop)
        findViewById<TextView>(R.id.portScannerOpenHeader).isVisible = false

        scanJob = lifecycleScope.launch {
            val targets = if (singleHost) {
                listOf(hostOverride.trim())
            } else {
                statusView.text = getString(R.string.port_scan_discovering)
                PortScanner.resolveTargets(this@PortScannerActivity, hostOverride) { scanned, total ->
                    withContext(Dispatchers.Main) {
                        statusView.text = getString(
                            R.string.port_scan_discovering_progress,
                            scanned,
                            total
                        )
                    }
                }
            }

            if (targets.isEmpty()) {
                statusView.text = getString(R.string.port_scan_no_devices)
                finishScan()
                return@launch
            }

            var openCount = 0
            var lastHeaderHost: String? = null
            val networkResult = PortScanner.scanMany(targets, ports) {
                    hostIndex, hostTotal, host, scanned, portTotal, hit ->
                withContext(Dispatchers.Main) {
                    if (lastHeaderHost != host) {
                        lastHeaderHost = host
                        addHostHeader(host)
                    }
                    if (hit != null) {
                        openCount++
                        addOpenRow(hit.port, hit.service, hit.connectMs)
                    }
                    statusView.text = getString(
                        R.string.port_scan_scanning_progress,
                        host,
                        hostIndex,
                        hostTotal,
                        scanned,
                        portTotal,
                        openCount
                    )
                }
            }

            lastReport = if (singleHost && networkResult.results.size == 1) {
                PortScanner.format(networkResult.results.first())
            } else {
                PortScanner.formatNetwork(networkResult)
            }

            val withOpen = networkResult.results.count { it.openPorts.isNotEmpty() }
            statusView.text = when {
                networkResult.error != null -> networkResult.error
                openCount == 0 -> getString(
                    R.string.port_scan_done_none,
                    networkResult.targets.size,
                    ports.size
                )
                singleHost -> getString(
                    R.string.port_scan_done_single,
                    openCount,
                    networkResult.targets.first()
                )
                else -> getString(
                    R.string.port_scan_done_network,
                    openCount,
                    withOpen,
                    networkResult.targets.size
                )
            }
            finishScan()
        }
    }

    private fun finishScan() {
        hostInput.isEnabled = true
        extraPortsInput.isEnabled = true
        presetGroup.isEnabled = true
        runButton.text = getString(R.string.tool_run)
    }

    private fun addHostHeader(host: String) {
        val header = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (openList.childCount == 0) 0 else resources.getDimensionPixelSize(R.dimen.item_gap)
            }
            text = host
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 13f
        }
        openList.addView(header)
        findViewById<TextView>(R.id.portScannerOpenHeader).isVisible = true
    }

    private fun addOpenRow(port: Int, service: String?, connectMs: Double) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_port_scan_row, openList, false)
        val label = service?.let { "$port ($it)" } ?: port.toString()
        row.findViewById<TextView>(R.id.portScanRowPort).text = label
        row.findViewById<TextView>(R.id.portScanRowMs).text =
            String.format(Locale.US, "%.0f ms", connectMs)
        openList.addView(row)
    }

    private fun share() {
        if (lastReport.isNotBlank()) {
            TestResultExport.shareText(this, "Port scan", lastReport)
        }
    }
}

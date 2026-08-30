package com.towerscope.ar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.ConnectionSnapshotCollector
import com.towerscope.ar.network.PortScanPreset
import com.towerscope.ar.network.PortScanner
import com.towerscope.ar.network.SubnetHost
import com.towerscope.ar.network.SubnetScanner
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LanScannerActivity : AppCompatActivity() {

    private lateinit var infoLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var modeHint: TextView
    private lateinit var modeDiscoverButton: MaterialButton
    private lateinit var modePortButton: MaterialButton
    private lateinit var hostInput: EditText
    private lateinit var portOptions: View
    private lateinit var presetSpinner: Spinner
    private lateinit var extraPortsInput: EditText
    private lateinit var resultsHeader: TextView
    private lateinit var resultsList: LinearLayout
    private lateinit var scanButton: MaterialButton
    private var scanJob: Job? = null
    private var lastReport = ""
    private var portMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_lan_scanner)
        SystemBars.apply(findViewById(R.id.lanScannerRoot))

        infoLabel = findViewById(R.id.lanInfoLabel)
        statusLabel = findViewById(R.id.lanStatus)
        modeHint = findViewById(R.id.lanModeHint)
        modeDiscoverButton = findViewById(R.id.lanModeDiscover)
        modePortButton = findViewById(R.id.lanModePort)
        hostInput = findViewById(R.id.lanHostInput)
        portOptions = findViewById(R.id.lanPortOptions)
        presetSpinner = findViewById(R.id.lanPresetSpinner)
        extraPortsInput = findViewById(R.id.lanExtraPortsInput)
        resultsHeader = findViewById(R.id.lanResultsHeader)
        resultsList = findViewById(R.id.lanResultsList)
        scanButton = findViewById(R.id.lanScanButton)

        presetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.port_scan_presets)
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        portMode = intent.getStringExtra(EXTRA_MODE) == MODE_PORT
        setPortMode(portMode)

        modeDiscoverButton.setOnClickListener {
            if (scanJob?.isActive == true) return@setOnClickListener
            setPortMode(false)
        }
        modePortButton.setOnClickListener {
            if (scanJob?.isActive == true) return@setOnClickListener
            setPortMode(true)
        }

        refreshSubnetInfo()
        scanButton.setOnClickListener { toggleScan() }
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

    private fun setPortMode(enabled: Boolean) {
        portMode = enabled
        val selectedColor = ContextCompat.getColor(this, R.color.accent_teal)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        modeDiscoverButton.strokeWidth = if (enabled) 1 else 3
        modePortButton.strokeWidth = if (enabled) 3 else 1
        modeDiscoverButton.setTextColor(if (enabled) muted else selectedColor)
        modePortButton.setTextColor(if (enabled) selectedColor else muted)
        portOptions.isVisible = enabled
        scanButton.text = getString(
            if (enabled) R.string.lan_scan_start_ports else R.string.lan_scan_start
        )
        updateModeUi()
    }

    private fun updateModeUi() {
        resultsHeader.isVisible = false
        if (!scanJob?.isActive.orDefault()) {
            modeHint.text = if (portMode) {
                getString(R.string.lan_scan_port_ready)
            } else {
                getString(R.string.lan_scan_discover_ready)
            }
        }
    }

    private fun Boolean?.orDefault(): Boolean = this == true

    private fun refreshSubnetInfo() {
        val subnet = SubnetScanner.localSubnet(this)
        val ipv6 = SubnetScanner.localIpv6Addresses()
        if (subnet == null) {
            infoLabel.text = "No active IPv4 interface"
            statusLabel.text = "Connect to Wi‑Fi, then scan"
            scanButton.isEnabled = false
        } else {
            infoLabel.text = buildString {
                append(
                    String.format(
                        Locale.US,
                        "%s/%d  ·  %s",
                        subnet.localIp,
                        subnet.prefixLength,
                        subnet.networkBase
                    )
                )
                if (ipv6.isNotEmpty()) {
                    append("\nIPv6  ")
                    append(ipv6.joinToString(", "))
                }
            }
            if (!scanJob?.isActive.orDefault()) {
                updateModeUi()
            }
            scanButton.isEnabled = true
        }
    }

    private fun selectedPreset(): PortScanPreset = when (presetSpinner.selectedItemPosition) {
        1 -> PortScanPreset.WEB
        2 -> PortScanPreset.ROUTER
        3 -> PortScanPreset.EXTENDED
        else -> PortScanPreset.COMMON
    }

    private fun toggleScan() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            finishScan()
            return
        }
        if (portMode) {
            startPortScan()
        } else {
            startDiscoverScan()
        }
    }

    private fun portScanHost(ip: String) {
        if (scanJob?.isActive == true) return
        setPortMode(true)
        hostInput.setText(ip)
        startPortScan()
    }

    private fun startDiscoverScan() {
        val subnet = SubnetScanner.localSubnet(this) ?: run {
            refreshSubnetInfo()
            return
        }
        prepareScan()
        scanJob = lifecycleScope.launch {
            val found = SubnetScanner.scan(subnet) { scanned, total, host ->
                withContext(Dispatchers.Main.immediate) {
                    statusLabel.text = getString(R.string.lan_scan_discover_progress, scanned, total)
                    if (host != null) appendDiscoverHost(host)
                }
            }
            statusLabel.text = getString(R.string.lan_scan_discover_done, found.size)
            finishScan()
        }
    }

    private fun startPortScan() {
        val hostOverride = hostInput.text?.toString().orEmpty()
        val ports = PortScanner.portsFor(selectedPreset(), extraPortsInput.text?.toString().orEmpty())
        val singleHost = hostOverride.isNotBlank()

        prepareScan()
        resultsHeader.isVisible = true

        scanJob = lifecycleScope.launch {
            val targets = if (singleHost) {
                listOf(hostOverride.trim())
            } else {
                val subnet = SubnetScanner.localSubnet(this@LanScannerActivity)
                if (subnet == null) {
                    statusLabel.text = getString(R.string.port_scan_no_devices)
                    finishScan()
                    return@launch
                }
                statusLabel.text = getString(R.string.port_scan_discovering)
                val discovered = SubnetScanner.scan(subnet) { scanned, total, _ ->
                    withContext(Dispatchers.Main) {
                        statusLabel.text = getString(
                            R.string.port_scan_discovering_progress,
                            scanned,
                            total
                        )
                    }
                }
                val snapshot = ConnectionSnapshotCollector.collect(
                    this@LanScannerActivity,
                    fetchPublicIp = false
                )
                PortScanner.resolveTargets(
                    overrideHost = null,
                    gatewayIpv4 = snapshot.gatewayIpv4,
                    subnet = subnet,
                    discoveredHosts = discovered.map { it.ip }
                )
            }

            if (targets.isEmpty()) {
                statusLabel.text = getString(R.string.port_scan_no_devices)
                finishScan()
                return@launch
            }

            resultsList.removeAllViews()
            var openCount = 0
            var lastHeaderHost: String? = null
            val networkResult = PortScanner.scanMany(targets, ports) {
                    hostIndex, hostTotal, host, scanned, portTotal, hit ->
                withContext(Dispatchers.Main) {
                    if (lastHeaderHost != host) {
                        lastHeaderHost = host
                        addPortHostHeader(host)
                    }
                    if (hit != null) {
                        openCount++
                        addOpenPortRow(hit.port, hit.service, hit.connectMs)
                    }
                    statusLabel.text = getString(
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
            statusLabel.text = when {
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

    private fun prepareScan() {
        scanJob?.cancel()
        resultsList.removeAllViews()
        lastReport = ""
        hostInput.isEnabled = false
        modeDiscoverButton.isEnabled = false
        modePortButton.isEnabled = false
        presetSpinner.isEnabled = false
        extraPortsInput.isEnabled = false
        scanButton.text = getString(R.string.tool_stop)
        resultsHeader.isVisible = portMode
    }

    private fun finishScan() {
        hostInput.isEnabled = true
        modeDiscoverButton.isEnabled = true
        modePortButton.isEnabled = true
        presetSpinner.isEnabled = true
        extraPortsInput.isEnabled = true
        scanButton.text = getString(
            if (portMode) R.string.lan_scan_start_ports else R.string.lan_scan_start
        )
        updateModeUi()
    }

    private fun appendDiscoverHost(host: SubnetHost) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_subnet_host_row, resultsList, false)
        row.findViewById<TextView>(R.id.subnetRowIp).text = host.ip
        row.findViewById<TextView>(R.id.subnetRowUrl).text = host.httpUrl
        val meta = buildString {
            append(host.deviceType ?: "Host")
            append("  ·  MAC  ")
            append(host.macAddress ?: "—")
            append("\n")
            append(host.hostname ?: "no reverse DNS")
            if (host.openPorts.isNotEmpty()) {
                append("  ·  ports ")
                append(host.openPorts.joinToString(","))
            } else {
                host.openPort?.let { append("  ·  port ").append(it) }
            }
            if (host.ipv6Addresses.isNotEmpty()) {
                append("\nIPv6  ")
                append(host.ipv6Addresses.joinToString(", "))
            }
        }
        row.findViewById<TextView>(R.id.subnetRowMeta).text = meta
        row.findViewById<MaterialButton>(R.id.subnetRowOpenButton).setOnClickListener {
            openHostUrl(host)
        }
        row.findViewById<MaterialButton>(R.id.subnetRowPortButton).setOnClickListener {
            portScanHost(host.ip)
        }
        resultsList.addView(row)
    }

    private fun addPortHostHeader(host: String) {
        val header = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (resultsList.childCount == 0) {
                    0
                } else {
                    resources.getDimensionPixelSize(R.dimen.item_gap)
                }
            }
            text = host
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 13f
        }
        resultsList.addView(header)
        resultsHeader.isVisible = true
    }

    private fun addOpenPortRow(port: Int, service: String?, connectMs: Double) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_port_scan_row, resultsList, false)
        val label = service?.let { "$port ($it)" } ?: port.toString()
        row.findViewById<TextView>(R.id.portScanRowPort).text = label
        row.findViewById<TextView>(R.id.portScanRowMs).text =
            String.format(Locale.US, "%.0f ms", connectMs)
        resultsList.addView(row)
    }

    private fun openHostUrl(host: SubnetHost) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(host.httpUrl))
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "No browser available for ${host.httpUrl}", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) {
            TestResultExport.shareText(this, "LAN port scan", lastReport)
        }
    }

    companion object {
        const val EXTRA_MODE = "lan_scan_mode"
        const val MODE_DISCOVER = "discover"
        const val MODE_PORT = "port"
    }
}

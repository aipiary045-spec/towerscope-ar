package com.towerscope.ar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.ConnectionSnapshotCollector
import com.towerscope.ar.network.NetworkPortScanResult
import com.towerscope.ar.network.PortScanPreset
import com.towerscope.ar.network.PortScanner
import com.towerscope.ar.network.SubnetHost
import com.towerscope.ar.network.SubnetScanner
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.ui.LanScanResultAdapter
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.ToolScaffold
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
    private lateinit var resultsList: RecyclerView
    private lateinit var resultsAdapter: LanScanResultAdapter
    private lateinit var contentScroll: android.widget.ScrollView
    private lateinit var scanButton: MaterialButton
    private val resultItems = mutableListOf<LanScanResultAdapter.Item>()
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
        contentScroll = findViewById(R.id.lanContentScroll)
        scanButton = findViewById(R.id.lanScanButton)
        resultsAdapter = LanScanResultAdapter(
            onOpenHost = { openHostUrl(it) },
            onPortScanHost = { portScanHost(it) },
            onOpenUrl = { openUrl(it) }
        )
        resultsList.apply {
            layoutManager = LinearLayoutManager(this@LanScannerActivity)
            adapter = resultsAdapter
            itemAnimator = null
        }
        ToolScaffold.bind(
            activity = this,
            titleRes = R.string.home_job_lan_scan,
            subtitleRes = R.string.home_job_lan_scan_sub,
            onShare = { share() }
        )

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
        if (!hasPortResults()) {
            resultsHeader.isVisible = false
        }
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
        val target = ip.trim()
        if (target.isBlank()) return
        setPortMode(true)
        hostInput.setText(target)
        startPortScan(targetHost = target)
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

    private fun startPortScan(targetHost: String? = null) {
        val hostOverride = targetHost?.trim().orEmpty()
            .ifBlank { hostInput.text?.toString().orEmpty().trim() }
        val ports = PortScanner.portsFor(selectedPreset(), extraPortsInput.text?.toString().orEmpty())
        val singleHost = hostOverride.isNotBlank()

        prepareScan()
        resultsHeader.isVisible = true

        scanJob = lifecycleScope.launch {
            val targets = if (singleHost) {
                listOf(hostOverride)
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

            clearResults()
            var openCount = 0
            val networkResult = PortScanner.scanMany(targets, ports) {
                    hostIndex, hostTotal, host, scanned, portTotal, hit ->
                withContext(Dispatchers.Main.immediate) {
                    if (hit != null) openCount++
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

            renderPortResults(networkResult)
            contentScroll.post { contentScroll.fullScroll(android.view.View.FOCUS_DOWN) }

            lastReport = if (singleHost && networkResult.results.size == 1) {
                PortScanner.format(networkResult.results.first())
            } else {
                PortScanner.formatNetwork(networkResult)
            }

            val withOpen = networkResult.results.count { it.openPorts.isNotEmpty() }
            openCount = networkResult.results.sumOf { it.openPorts.size }
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
                    hostOverride
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

    private fun clearResults() {
        resultItems.clear()
        resultsAdapter.submitList(emptyList())
    }

    private fun prepareScan() {
        scanJob?.cancel()
        clearResults()
        lastReport = ""
        hostInput.isEnabled = false
        modeDiscoverButton.isEnabled = false
        modePortButton.isEnabled = false
        presetSpinner.isEnabled = false
        extraPortsInput.isEnabled = false
        if (portMode) portOptions.isVisible = false
        scanButton.text = getString(R.string.tool_stop)
        resultsHeader.isVisible = portMode
    }

    private fun finishScan() {
        hostInput.isEnabled = true
        modeDiscoverButton.isEnabled = true
        modePortButton.isEnabled = true
        presetSpinner.isEnabled = true
        extraPortsInput.isEnabled = true
        if (portMode) portOptions.isVisible = true
        scanButton.text = getString(
            if (portMode) R.string.lan_scan_start_ports else R.string.lan_scan_start
        )
        updateModeUi()
    }

    private fun hasPortResults(): Boolean = resultItems.isNotEmpty() && portMode

    private fun renderPortResults(result: NetworkPortScanResult) {
        resultItems.clear()
        val withOpen = result.results.filter { it.openPorts.isNotEmpty() }
        resultsHeader.isVisible = true
        resultsHeader.text = if (withOpen.isEmpty()) {
            "RESULTS"
        } else {
            getString(R.string.lan_scan_port_results_header)
        }
        if (withOpen.isEmpty()) {
            resultItems.add(
                LanScanResultAdapter.Item.Note(
                    getString(R.string.lan_scan_no_open_ports, result.targets.size)
                )
            )
        } else {
            withOpen.forEach { hostResult ->
                resultItems.add(LanScanResultAdapter.Item.PortHeader(hostResult.host))
                hostResult.openPorts.forEach { hit ->
                    resultItems.add(
                        LanScanResultAdapter.Item.OpenPort(
                            host = hostResult.host,
                            port = hit.port,
                            service = hit.service,
                            connectMs = hit.connectMs,
                            url = serviceUrl(hostResult.host, hit.port)
                        )
                    )
                }
            }
        }
        resultsAdapter.submitList(resultItems.toList())
    }

    private fun appendDiscoverHost(host: SubnetHost) {
        resultsHeader.isVisible = true
        resultsHeader.text = "RESULTS"
        resultItems.add(LanScanResultAdapter.Item.Host(host))
        resultsAdapter.submitList(resultItems.toList())
    }

    private fun serviceUrl(host: String, port: Int): String {
        val scheme = when (port) {
            443, 8443 -> "https"
            else -> "http"
        }
        return if (port == 80 || port == 443) {
            "$scheme://$host/"
        } else {
            "$scheme://$host:$port/"
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "No browser available for $url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openHostUrl(host: SubnetHost) {
        openUrl(host.httpUrl)
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

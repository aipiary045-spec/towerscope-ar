package com.towerscope.ar

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.ConnectionSnapshotCollector
import com.towerscope.ar.network.LinkSpeedReporter
import com.towerscope.ar.network.PhoneLinkInfo
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.network.WifiMonitor
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WanLinkActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var communityInput: EditText
    private lateinit var advancedSection: LinearLayout
    private lateinit var advancedToggle: MaterialButton
    private lateinit var resultView: TextView
    private lateinit var statusView: TextView
    private lateinit var runButton: MaterialButton
    private lateinit var wifiMonitor: WifiMonitor
    private var job: Job? = null
    private var lastReport = ""
    private var gatewayHost: String? = null
    private var advancedOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_wan_link)
        SystemBars.apply(findViewById(R.id.wanLinkRoot))
        wifiMonitor = WifiMonitor(this)

        hostInput = findViewById(R.id.wanLinkHostInput)
        communityInput = findViewById(R.id.wanLinkCommunityInput)
        advancedSection = findViewById(R.id.wanLinkAdvancedSection)
        advancedToggle = findViewById(R.id.wanLinkAdvancedToggle)
        resultView = findViewById(R.id.wanLinkResult)
        statusView = findViewById(R.id.wanLinkStatus)
        runButton = findViewById(R.id.wanLinkRunButton)

        advancedToggle.setOnClickListener {
            advancedOpen = !advancedOpen
            advancedSection.visibility = if (advancedOpen) View.VISIBLE else View.GONE
            advancedToggle.text = getString(
                if (advancedOpen) R.string.wan_link_advanced_hide else R.string.wan_link_advanced_toggle
            )
        }

        lifecycleScope.launch {
            val snap = ConnectionSnapshotCollector.collect(this@WanLinkActivity, fetchPublicIp = false)
            gatewayHost = snap.gatewayIpv4
            snap.gatewayIpv4?.let { hostInput.setText(it) }
            runQuery(trySnmp = false)
        }

        runButton.setOnClickListener { runQuery(trySnmp = advancedOpen) }
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

    private fun currentPhoneLink(): PhoneLinkInfo {
        val link = wifiMonitor.currentLink()
        return PhoneLinkInfo(
            ssid = link.ssid,
            linkMbps = link.linkSpeedMbps,
            connected = link.connected
        )
    }

    private fun runQuery(trySnmp: Boolean) {
        if (job?.isActive == true) {
            job?.cancel()
            return
        }
        val host = if (trySnmp) hostInput.text?.toString().orEmpty() else gatewayHost
        val community = communityInput.text?.toString().orEmpty()
        runButton.isEnabled = false
        statusView.text = getString(R.string.wan_link_querying)
        job = lifecycleScope.launch {
            val phone = currentPhoneLink()
            val report = LinkSpeedReporter.collect(
                gatewayHost = host ?: gatewayHost,
                phoneLink = phone,
                snmpCommunity = community,
                trySnmp = trySnmp
            )
            lastReport = LinkSpeedReporter.format(report)
            resultView.text = lastReport
            statusView.text = LinkSpeedReporter.statusLine(report)
            runButton.isEnabled = true
            runButton.text = getString(R.string.tool_run)
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) {
            TestResultExport.shareText(this, "Link speed", lastReport)
        }
    }
}

package com.towerscope.ar

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.ConnectionSnapshotCollector
import com.towerscope.ar.network.PhoneLinkInfo
import com.towerscope.ar.network.RouterWanLink
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.network.WifiMonitor
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WanLinkActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var communityInput: EditText
    private lateinit var resultView: TextView
    private lateinit var statusView: TextView
    private lateinit var runButton: MaterialButton
    private lateinit var wifiMonitor: WifiMonitor
    private var job: Job? = null
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_wan_link)
        SystemBars.apply(findViewById(R.id.wanLinkRoot))
        wifiMonitor = WifiMonitor(this)

        hostInput = findViewById(R.id.wanLinkHostInput)
        communityInput = findViewById(R.id.wanLinkCommunityInput)
        resultView = findViewById(R.id.wanLinkResult)
        statusView = findViewById(R.id.wanLinkStatus)
        runButton = findViewById(R.id.wanLinkRunButton)

        lifecycleScope.launch {
            val snap = ConnectionSnapshotCollector.collect(this@WanLinkActivity, fetchPublicIp = false)
            snap.gatewayIpv4?.let { hostInput.setText(it) }
            showPhoneLink()
            runQuery()
        }

        runButton.setOnClickListener { runQuery() }
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

    private fun showPhoneLink() {
        val phone = currentPhoneLink()
        val label = RouterWanLink.formatPhoneLink(phone)
        resultView.text = label
        statusView.text = if (phone.connected && phone.linkMbps != null) {
            label
        } else {
            getString(R.string.wan_link_querying)
        }
    }

    private fun runQuery() {
        if (job?.isActive == true) {
            job?.cancel()
            return
        }
        val host = hostInput.text?.toString().orEmpty()
        val community = communityInput.text?.toString().orEmpty()
        runButton.isEnabled = false
        hostInput.isEnabled = false
        communityInput.isEnabled = false
        statusView.text = getString(R.string.wan_link_querying)
        job = lifecycleScope.launch {
            val phone = currentPhoneLink()
            val result = RouterWanLink.query(host, community, phone)
            lastReport = RouterWanLink.format(result)
            resultView.text = lastReport
            val selected = result.selectedInterface
            statusView.text = when {
                selected != null -> buildString {
                    append("WAN ")
                    RouterWanLink.negotiatedEthernetLabel(selected)?.let { append(it) }
                    append(" · ")
                    append(selected.name)
                }
                result.phoneLink?.connected == true && result.phoneLink.linkMbps != null -> {
                    RouterWanLink.formatPhoneLink(result.phoneLink)
                }
                else -> result.error ?: getString(R.string.wan_link_no_match)
            }
            runButton.isEnabled = true
            hostInput.isEnabled = true
            communityInput.isEnabled = true
            runButton.text = getString(R.string.tool_run)
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) {
            TestResultExport.shareText(this, "Link speed", lastReport)
        }
    }
}

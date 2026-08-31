package com.towerscope.ar

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.ConnectionSnapshotCollector
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.ToolScaffold
import kotlinx.coroutines.launch

class ConnectionSnapshotActivity : AppCompatActivity() {

    private lateinit var detailView: TextView
    private lateinit var statusView: TextView
    private var lastReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_connection_snapshot)
        SystemBars.apply(findViewById(R.id.connectionRoot))
        ToolScaffold.bind(
            activity = this,
            titleRes = R.string.home_job_connection,
            subtitleRes = R.string.home_job_connection_sub,
            onShare = { share() }
        )

        detailView = findViewById(R.id.connectionDetail)
        statusView = findViewById(R.id.connectionStatus)

        findViewById<MaterialButton>(R.id.connectionRefreshButton).setOnClickListener { refresh() }
        refresh()
    }

    private fun refresh() {
        statusView.text = "Refreshing…"
        lifecycleScope.launch {
            val snapshot = ConnectionSnapshotCollector.collect(this@ConnectionSnapshotActivity)
            lastReport = ConnectionSnapshotCollector.format(snapshot)
            detailView.text = lastReport
            statusView.text = if (snapshot.isConnected) "Connected · ${snapshot.linkType}" else "Not connected"
        }
    }

    private fun share() {
        if (lastReport.isNotBlank()) {
            TestResultExport.shareText(this, "Connection snapshot", lastReport)
        }
    }
}

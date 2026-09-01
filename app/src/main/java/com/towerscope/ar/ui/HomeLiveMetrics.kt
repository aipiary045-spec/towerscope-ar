package com.towerscope.ar.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.towerscope.ar.R
import com.towerscope.ar.network.ConnectionSnapshot
import com.towerscope.ar.network.ConnectionSnapshotCollector
import com.towerscope.ar.network.NetworkSession
import com.towerscope.ar.network.PingMonitor
import com.towerscope.ar.network.WifiLinkStatus
import com.towerscope.ar.network.WifiMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object HomeLiveMetrics {

    data class MetricViews(
        val label: TextView,
        val hero: TextView,
        val meta: TextView
    )

    fun views(root: View, tileId: Int): MetricViews {
        val tile = root.findViewById<View>(tileId)
        return MetricViews(
            label = tile.findViewById(R.id.homeMetricLabel),
            hero = tile.findViewById(R.id.homeMetricHero),
            meta = tile.findViewById(R.id.homeMetricMeta)
        )
    }

    suspend fun refresh(
        context: Context,
        wifiMonitor: WifiMonitor,
        topologyRoot: View,
        wifi: MetricViews,
        internet: MetricViews,
        speed: MetricViews,
        internetLive: InternetLiveMonitor.State? = null
    ) {
        val snapshot = withContext(Dispatchers.IO) {
            ConnectionSnapshotCollector.collect(context, fetchPublicIp = false)
        }
        NetworkTopologyBinder.bind(topologyRoot, snapshot)
        val link = wifiMonitor.currentLink()
        bindWifi(context, wifiMonitor, link, wifi)
        val ping = withContext(Dispatchers.IO) { PingMonitor.pingOnce("1.1.1.1") }
        bindInternet(context, ping, internet)
        if (internetLive != null) {
            InternetSpeedMetricBinder.bind(context, internetLive, speed)
        } else {
            bindSpeedLegacy(context, NetworkSession.lastSpeedSnapshot(context), speed, snapshot)
        }
    }

    private fun bindWifi(
        context: Context,
        monitor: WifiMonitor,
        link: WifiLinkStatus,
        views: MetricViews
    ) {
        views.label.setText(R.string.home_metric_wifi)
        val rssi = link.rssiDbm
        views.hero.text = if (rssi != null) {
            String.format(Locale.US, "%d dBm", rssi)
        } else if (!link.connected) {
            context.getString(R.string.hub_preview_wifi_off)
        } else {
            "—"
        }
        views.hero.setTextColor(
            ContextCompat.getColor(
                context,
                when {
                    rssi == null -> R.color.text_muted
                    rssi >= -60 -> R.color.status_clear
                    rssi >= -75 -> R.color.status_warn
                    else -> R.color.status_blocked
                }
            )
        )
        val ssid = link.ssid ?: if (link.connected) "Hidden SSID" else "Not on Wi‑Fi"
        val channel = link.channel?.let { ch ->
            String.format(Locale.US, "Ch %d · %s", ch, link.band ?: "—")
        }
        views.meta.text = listOfNotNull(monitor.signalQualityLabel(rssi), channel, ssid)
            .joinToString("\n")
    }

    private fun bindInternet(context: Context, ping: PingMonitor.PingOnceResult, views: MetricViews) {
        views.label.setText(R.string.home_metric_internet)
        views.hero.text = if (ping.success && ping.latencyMs != null) {
            String.format(Locale.US, "%.0f ms", ping.latencyMs)
        } else {
            context.getString(R.string.hub_preview_ping_failed)
        }
        views.hero.setTextColor(
            ContextCompat.getColor(
                context,
                if (ping.success) R.color.status_clear else R.color.status_blocked
            )
        )
        views.meta.text = context.getString(R.string.hub_preview_ping_to, ping.host)
    }

    private fun bindSpeedLegacy(
        context: Context,
        snapshot: NetworkSession.SpeedSnapshot?,
        views: MetricViews,
        connection: ConnectionSnapshot
    ) {
        views.label.setText(R.string.home_metric_speed)
        if (snapshot != null) {
            views.hero.text = String.format(Locale.US, "%.0f Mbps", snapshot.downloadMbps)
            views.hero.setTextColor(ContextCompat.getColor(context, R.color.status_clear))
            views.meta.text = String.format(
                Locale.US,
                "%.0f↑ · %.0f ms",
                snapshot.uploadMbps,
                snapshot.latencyMs
            )
        } else {
            views.hero.text = when {
                connection.isValidated -> "—"
                connection.isConnected -> "—"
                else -> context.getString(R.string.hub_preview_offline)
            }
            views.hero.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            views.meta.text = context.getString(R.string.hub_preview_speed_none)
        }
    }
}

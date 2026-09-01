package com.towerscope.ar.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.towerscope.ar.R
import com.towerscope.ar.network.ConnectionSnapshot
import com.towerscope.ar.network.ConnectionSnapshotCollector
import com.towerscope.ar.network.NetworkSession
import com.towerscope.ar.network.PingMonitor
import com.towerscope.ar.network.SubnetInfo
import com.towerscope.ar.network.SubnetScanner
import com.towerscope.ar.network.WifiLinkStatus
import com.towerscope.ar.network.WifiMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object NetworkHubPreviews {

  data class PreviewViews(
    val label: TextView,
    val hero: TextView,
    val meta: TextView,
    val detail: TextView
  )

  fun views(root: View, previewId: Int): PreviewViews {
    val card = root.findViewById<View>(previewId)
    return PreviewViews(
      label = card.findViewById(R.id.hubPreviewLabel),
      hero = card.findViewById(R.id.hubPreviewHero),
      meta = card.findViewById(R.id.hubPreviewMeta),
      detail = card.findViewById(R.id.hubPreviewDetail)
    )
  }

  suspend fun refresh(
    root: View,
    context: Context,
    wifiMonitor: WifiMonitor,
    connection: PreviewViews,
    wifi: PreviewViews,
    local: PreviewViews,
    internet: PreviewViews
  ) {
    val snapshot = withContext(Dispatchers.IO) {
      ConnectionSnapshotCollector.collect(context, fetchPublicIp = false)
    }
    NetworkTopologyBinder.bind(root, snapshot)
    bindConnection(context, snapshot, connection)
    bindWifi(context, wifiMonitor.currentLink(), wifi)
    bindLocal(context, snapshot, SubnetScanner.localSubnet(context), local)
    val ping = withContext(Dispatchers.IO) { PingMonitor.pingOnce("1.1.1.1") }
    bindInternet(context, ping, NetworkSession.lastSpeedSnapshot(context), internet)
  }

  private fun bindConnection(context: Context, snapshot: ConnectionSnapshot, views: PreviewViews) {
    views.label.setText(R.string.hub_preview_connection_label)
    views.hero.text = when {
      snapshot.isValidated -> context.getString(R.string.hub_preview_online)
      snapshot.isConnected -> context.getString(R.string.hub_preview_limited)
      else -> context.getString(R.string.hub_preview_offline)
    }
    views.hero.setTextColor(
      ContextCompat.getColor(
        context,
        when {
          snapshot.isValidated -> R.color.status_clear
          snapshot.isConnected -> R.color.status_warn
          else -> R.color.status_blocked
        }
      )
    )
    views.meta.isVisible = true
    views.meta.text = snapshot.linkType
    views.detail.text = buildString {
      snapshot.localIpv4?.let { append(context.getString(R.string.hub_preview_device_ip, it)) }
      snapshot.gatewayIpv4?.let {
        if (isNotEmpty()) append('\n')
        append(context.getString(R.string.hub_preview_gateway_ip, it))
      }
      if (snapshot.dnsServers.isNotEmpty()) {
        if (isNotEmpty()) append('\n')
        append("DNS ${snapshot.dnsServers.take(2).joinToString(", ")}")
      }
      if (isEmpty()) append(context.getString(R.string.hub_preview_connection_empty))
    }
  }

  private fun bindWifi(context: Context, link: WifiLinkStatus, views: PreviewViews) {
    views.label.setText(R.string.hub_preview_wifi_label)
    val rssi = link.rssiDbm
    val monitor = WifiMonitor(context)
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
    views.meta.isVisible = true
    views.meta.text = monitor.signalQualityLabel(rssi)
    views.meta.setTextColor(views.hero.currentTextColor)
    views.detail.text = buildString {
      val ssid = link.ssid ?: if (link.connected) "Hidden SSID" else "Not on Wi‑Fi"
      append(ssid)
      link.channel?.let { ch ->
        append('\n')
        append(
          String.format(
            Locale.US,
            "Ch %d · %s",
            ch,
            link.band ?: "—"
          )
        )
      }
      link.linkSpeedMbps?.let { speed ->
        append('\n')
        append(String.format(Locale.US, "Link %d Mbps", speed))
      }
    }
  }

  private fun bindLocal(
    context: Context,
    snapshot: ConnectionSnapshot,
    subnet: SubnetInfo?,
    views: PreviewViews
  ) {
    views.label.setText(R.string.hub_preview_local_label)
    if (subnet != null) {
      views.hero.text = String.format(Locale.US, "%s/%d", subnet.networkBase, subnet.prefixLength)
      views.meta.isVisible = true
      views.meta.text = subnet.localIp
      views.detail.text = buildString {
        append(context.getString(R.string.hub_preview_local_hosts, subnet.hostCount))
        snapshot.gatewayIpv4?.let {
          append('\n')
          append(context.getString(R.string.hub_preview_gateway_ip, it))
        }
        append('\n')
        append(context.getString(R.string.hub_preview_local_hint))
      }
    } else {
      views.hero.text = "—"
      views.meta.isVisible = false
      views.detail.text = context.getString(R.string.hub_preview_local_unavailable)
    }
  }

  private fun bindInternet(
    context: Context,
    ping: PingMonitor.PingOnceResult,
    speed: NetworkSession.SpeedSnapshot?,
    views: PreviewViews
  ) {
    views.label.setText(R.string.hub_preview_internet_label)
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
    views.meta.isVisible = true
    views.meta.text = context.getString(R.string.hub_preview_ping_to, ping.host)
    views.detail.text = if (speed != null) {
      String.format(
        Locale.US,
        "Last speed test\n%.0f↓ / %.0f↑ Mbps · %.0f ms latency",
        speed.downloadMbps,
        speed.uploadMbps,
        speed.latencyMs
      )
    } else {
      context.getString(R.string.hub_preview_speed_none)
    }
  }
}

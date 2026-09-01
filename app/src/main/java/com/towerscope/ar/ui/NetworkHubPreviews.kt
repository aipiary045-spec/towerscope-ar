package com.towerscope.ar.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.towerscope.ar.R
import com.towerscope.ar.network.ConnectionSnapshot
import com.towerscope.ar.network.ConnectionSnapshotCollector
import com.towerscope.ar.network.InterferenceLevel
import com.towerscope.ar.network.NetworkSession
import com.towerscope.ar.network.PingMonitor
import com.towerscope.ar.network.SubnetInfo
import com.towerscope.ar.network.SubnetScanner
import com.towerscope.ar.network.WifiChannelAnalysis
import com.towerscope.ar.network.WifiLinkStatus
import com.towerscope.ar.network.WifiMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

object NetworkHubPreviews {

  data class PreviewViews(
    val label: TextView,
    val hero: TextView,
    val meta: TextView,
    val detail: TextView,
    val chart: LosProfileChartView? = null
  )

  fun views(root: View, previewId: Int): PreviewViews {
    val card = root.findViewById<View>(previewId)
    return PreviewViews(
      label = card.findViewById(R.id.hubPreviewLabel),
      hero = card.findViewById(R.id.hubPreviewHero),
      meta = card.findViewById(R.id.hubPreviewMeta),
      detail = card.findViewById(R.id.hubPreviewDetail),
      chart = card.findViewById(R.id.hubPreviewChart)
    )
  }

  suspend fun refresh(
    root: View,
    context: Context,
    wifiMonitor: WifiMonitor,
    connection: PreviewViews,
    wifi: PreviewViews,
    local: PreviewViews,
    internet: PreviewViews,
    internetLive: InternetLiveMonitor.State? = null,
    internetPingMetric: HomeLiveMetrics.MetricViews? = null,
    internetSpeedMetric: HomeLiveMetrics.MetricViews? = null
  ) {
    val snapshot = withContext(Dispatchers.IO) {
      ConnectionSnapshotCollector.collect(context, fetchPublicIp = false)
    }
    NetworkTopologyBinder.bind(root, snapshot)
    bindConnection(context, snapshot, connection)
    val link = wifiMonitor.currentLink()
    wifiMonitor.startScan()
    val analysis = wifiMonitor.analyzeChannels(
      link,
      wifiMonitor.annotateScan(link, wifiMonitor.latestScanResults())
    )
    bindWifi(context, link, analysis, wifi)
    bindLocal(context, snapshot, SubnetScanner.localSubnet(context), local)
    if (internetLive != null) {
      bindInternetLive(context, internetLive, internet)
      internetPingMetric?.let { bindInternetPingMetric(context, internetLive, it) }
      internetSpeedMetric?.let { bindInternetSpeedMetric(context, internetLive, it) }
    } else {
      val ping = withContext(Dispatchers.IO) { PingMonitor.pingOnce("1.1.1.1") }
      bindInternet(context, ping, NetworkSession.lastSpeedSnapshot(context), internet)
    }
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

  private fun bindWifi(
    context: Context,
    link: WifiLinkStatus,
    analysis: WifiChannelAnalysis,
    views: PreviewViews
  ) {
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
    views.meta.text = context.getString(
      R.string.hub_preview_interference_label,
      WifiMonitor.interferenceLabel(analysis.interferenceLevel)
    )
    views.meta.setTextColor(
      ContextCompat.getColor(
        context,
        when (analysis.interferenceLevel) {
          InterferenceLevel.LOW -> R.color.status_clear
          InterferenceLevel.MODERATE -> R.color.status_warn
          InterferenceLevel.HIGH -> R.color.chip_poor
          InterferenceLevel.UNKNOWN -> R.color.text_muted
        }
      )
    )
    views.detail.text = buildString {
      append(monitor.signalQualityLabel(rssi))
      val ssid = link.ssid ?: if (link.connected) "Hidden SSID" else "Not on Wi‑Fi"
      append('\n')
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
      if (analysis.interferenceHints.isNotEmpty()) {
        append('\n')
        append(analysis.interferenceHints.first())
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

  private fun bindInternetLive(
    context: Context,
    live: InternetLiveMonitor.State,
    views: PreviewViews
  ) {
    views.label.setText(R.string.hub_preview_internet_label)
    val avg = live.avgMs
    views.hero.text = when {
      live.sampleCount == 0 && live.lastMs == null -> context.getString(R.string.hub_preview_ping_failed)
      avg != null && avg > 0 -> String.format(Locale.US, "%.0f ms", avg)
      live.lastMs != null -> String.format(Locale.US, "%.0f ms", live.lastMs)
      else -> context.getString(R.string.hub_preview_ping_failed)
    }
    val reachable = live.sampleCount > 0 || live.lastMs != null
    views.hero.setTextColor(
      ContextCompat.getColor(
        context,
        if (reachable) R.color.status_clear else R.color.status_blocked
      )
    )
    views.meta.isVisible = true
    views.meta.text = if (live.sampleCount > 0) {
      context.getString(
        R.string.hub_internet_ping_meta,
        live.jitterMs ?: 0.0,
        live.lossPercent,
        live.sampleCount
      )
    } else {
      context.getString(R.string.hub_preview_ping_to, live.host)
    }
    views.detail.text = formatSpeedDetail(context, live)
  }

  private fun bindInternetPingMetric(
    context: Context,
    live: InternetLiveMonitor.State,
    views: HomeLiveMetrics.MetricViews
  ) {
    views.label.setText(R.string.hub_internet_metric_ping)
    val avg = live.avgMs
    views.hero.text = when {
      avg != null && avg > 0 -> String.format(Locale.US, "%.0f ms", avg)
      live.lastMs != null -> String.format(Locale.US, "%.0f ms", live.lastMs)
      else -> "—"
    }
    views.hero.setTextColor(
      ContextCompat.getColor(
        context,
        if (live.sampleCount > 0) R.color.status_clear else R.color.text_muted
      )
    )
    views.meta.text = if (live.sampleCount > 0) {
      context.getString(
        R.string.hub_internet_ping_tile_meta,
        live.jitterMs ?: 0.0,
        live.lossPercent
      )
    } else {
      context.getString(R.string.hub_internet_ping_collecting)
    }
  }

  private fun bindInternetSpeedMetric(
    context: Context,
    live: InternetLiveMonitor.State,
    views: HomeLiveMetrics.MetricViews
  ) {
    InternetSpeedMetricBinder.bind(
      context = context,
      live = live,
      views = views,
      labelRes = R.string.hub_internet_metric_speed
    )
  }

  private fun formatSpeedDetail(context: Context, live: InternetLiveMonitor.State): String {
    return when {
      live.quickSpeedRunning -> context.getString(R.string.hub_internet_speed_sampling)
      live.quickSpeedMbps != null -> context.getString(
        R.string.hub_internet_speed_detail_quick,
        live.quickSpeedMbps
      )
      live.cachedSpeed != null && !NetworkSession.isLastSpeedQuick(context) -> {
        val age = formatSpeedAge(context, live.speedAgeMs, live.cachedSpeed)
        String.format(
          Locale.US,
          "%.0f↓ / %.0f↑ Mbps · %.0f ms\n%s",
          live.cachedSpeed.downloadMbps,
          live.cachedSpeed.uploadMbps,
          live.cachedSpeed.latencyMs,
          age
        )
      }
      else -> context.getString(R.string.hub_preview_speed_none)
    }
  }

  private fun formatSpeedAge(
    context: Context,
    ageMs: Long?,
    speed: NetworkSession.SpeedSnapshot
  ): String {
    if (ageMs == null) {
      return context.getString(R.string.hub_internet_speed_last_full)
    }
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ageMs)
    return when {
      minutes < 1 -> context.getString(R.string.hub_internet_speed_age_recent)
      minutes < 60 -> context.getString(R.string.hub_internet_speed_age_minutes, minutes.toInt())
      else -> context.getString(
        R.string.hub_internet_speed_age_hours,
        TimeUnit.MILLISECONDS.toHours(ageMs).toInt()
      )
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

package com.towerscope.ar.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.towerscope.ar.R
import com.towerscope.ar.network.NetworkSession
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Shared download-speed tile binding for Home and Network Hub. */
object InternetSpeedMetricBinder {

    fun bind(
        context: Context,
        live: InternetLiveMonitor.State,
        views: HomeLiveMetrics.MetricViews,
        labelRes: Int = R.string.home_metric_speed
    ) {
        views.label.setText(labelRes)
        when {
            live.quickSpeedRunning -> {
                views.hero.text = "…"
                views.hero.setTextColor(ContextCompat.getColor(context, R.color.accent_teal))
                views.meta.text = context.getString(R.string.hub_internet_speed_sampling)
            }
            live.quickSpeedMbps != null -> {
                views.hero.text = String.format(Locale.US, "%.0f", live.quickSpeedMbps)
                views.hero.setTextColor(ContextCompat.getColor(context, R.color.status_clear))
                views.meta.text = context.getString(R.string.home_metric_speed_sample)
            }
            live.cachedSpeed != null && !NetworkSession.isLastSpeedQuick(context) -> {
                views.hero.text = String.format(Locale.US, "%.0f", live.cachedSpeed.downloadMbps)
                views.hero.setTextColor(ContextCompat.getColor(context, R.color.status_clear))
                views.meta.text = formatFullTestMeta(context, live.speedAgeMs, live.cachedSpeed)
            }
            else -> {
                views.hero.text = "—"
                views.hero.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                views.meta.text = context.getString(R.string.home_metric_speed_tap_network)
            }
        }
    }

    private fun formatFullTestMeta(
        context: Context,
        ageMs: Long?,
        speed: NetworkSession.SpeedSnapshot
    ): String {
        val age = when {
            ageMs == null -> context.getString(R.string.hub_internet_speed_last_full)
            TimeUnit.MILLISECONDS.toMinutes(ageMs) < 1 ->
                context.getString(R.string.hub_internet_speed_age_recent)
            TimeUnit.MILLISECONDS.toMinutes(ageMs) < 60 ->
                context.getString(
                    R.string.hub_internet_speed_age_minutes,
                    TimeUnit.MILLISECONDS.toMinutes(ageMs).toInt()
                )
            else ->
                context.getString(
                    R.string.hub_internet_speed_age_hours,
                    TimeUnit.MILLISECONDS.toHours(ageMs).toInt()
                )
        }
        return String.format(
            Locale.US,
            "%.0f↑ Mbps · %s",
            speed.uploadMbps,
            age
        )
    }
}

package com.towerscope.ar.ui

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.towerscope.ar.R
import com.towerscope.ar.data.LosProfile
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.util.LinkEstimate
import com.towerscope.ar.viewmodel.TowerUiState

object InstallHubPreviews {

    fun bindLos(context: Context, state: TowerUiState, views: NetworkHubPreviews.PreviewViews) {
        views.label.setText(R.string.install_preview_los_label)
        val chart = views.chart
        when {
            state.towers.isEmpty() -> {
                views.hero.text = "—"
                views.hero.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                views.meta.isVisible = false
                views.detail.text = context.getString(R.string.install_dashboard_los_no_sites)
                hideChart(chart, state)
            }
            state.positioningLocation() == null -> {
                views.hero.text = "—"
                views.hero.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                views.meta.isVisible = false
                views.detail.text = state.losRangeStatus
                    ?: context.getString(R.string.install_dashboard_los_need_gps)
                hideChart(chart, state)
            }
            state.losRangeLoading && state.losRangeRows.isEmpty() -> {
                views.hero.text = "…"
                views.hero.setTextColor(ContextCompat.getColor(context, R.color.accent_teal))
                views.meta.isVisible = true
                views.meta.text = context.getString(R.string.install_dashboard_los_loading)
                views.detail.text = state.losRangeStatus.orEmpty()
                hideChart(chart, state)
            }
            else -> {
                val best = state.bestLosCandidate()
                if (best == null) {
                    views.hero.text = "—"
                    views.hero.setTextColor(ContextCompat.getColor(context, R.color.status_warn))
                    views.meta.isVisible = true
                    views.meta.text = state.losRangeStatus
                        ?: context.getString(R.string.install_dashboard_los_none_in_range)
                    views.detail.text = context.getString(R.string.install_preview_los_hint)
                    hideChart(chart, state)
                } else {
                    val clutter = state.clutterHeightMeters.toDouble()
                    val freq = state.frequencyGhz.toDouble()
                    val profile = best.profile
                    val geometric = profile?.minClearanceMeters(clutter)
                    val fresnel = profile?.minFresnelClearanceMeters(clutter, freq)
                    val dbm = LinkEstimate.estimatedReceiveLevelDbm(
                        distanceMeters = best.distanceMeters,
                        frequencyGhz = freq,
                        txPowerDbm = state.txPowerDbm.toDouble(),
                        apGainDbi = state.apAntennaGainDbi.toDouble(),
                        cpeGainDbi = state.cpeAntennaGainDbi.toDouble(),
                        geometricClearanceMeters = geometric,
                        fresnelClearanceMeters = fresnel
                    )
                    val quality = LinkEstimate.signalQuality(dbm)
                    views.hero.text = best.tower.name
                    views.hero.setTextColor(
                        ContextCompat.getColor(
                            context,
                            when (quality) {
                                LinkEstimate.SignalQuality.STRONG,
                                LinkEstimate.SignalQuality.OK -> R.color.status_clear
                                LinkEstimate.SignalQuality.WEAK -> R.color.accent_yellow
                                LinkEstimate.SignalQuality.POOR -> R.color.status_blocked
                            }
                        )
                    )
                    views.meta.isVisible = true
                    views.meta.text = "${quality.label} · ${LinkEstimate.formatReceiveLevel(dbm)}"
                    val bearing = state.bearingTo(best.tower)
                    val az = bearing?.let { GeoUtils.formatAzimuthPadded(it) } ?: "—"
                    views.detail.text = buildString {
                        append(GeoUtils.formatDistance(best.distanceMeters))
                        append(" · Az ")
                        append(az)
                        append('\n')
                        append(context.getString(R.string.install_preview_los_hint))
                    }
                    bindChart(chart, state, profile)
                }
            }
        }
    }

    private fun hideChart(chart: LosProfileChartView?, state: TowerUiState) {
        chart?.isVisible = false
        chart?.setProfile(
            null,
            state.clutterHeightMeters.toDouble(),
            state.frequencyGhz.toDouble()
        )
    }

    private fun bindChart(
        chart: LosProfileChartView?,
        state: TowerUiState,
        profile: LosProfile?
    ) {
        if (chart == null) return
        if (profile == null || profile.samples.size < 2) {
            hideChart(chart, state)
            return
        }
        chart.isVisible = true
        chart.setProfile(
            profile,
            state.clutterHeightMeters.toDouble(),
            state.frequencyGhz.toDouble()
        )
    }
}

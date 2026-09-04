package com.towerscope.ar.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.towerscope.ar.R
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.util.UnitFormat
import com.towerscope.ar.viewmodel.TowerUiState

object HeroActionCardBinder {

    fun bind(
        heroRoot: View,
        context: Context,
        state: TowerUiState,
        onClick: () -> Unit
    ) {
        val meta = heroRoot.findViewById<TextView>(R.id.heroActionMeta)
        heroRoot.setOnClickListener { onClick() }

        when {
            state.towers.isEmpty() -> {
                heroRoot.findViewById<TextView>(R.id.heroActionSubtitle).text =
                    context.getString(R.string.home_hero_compass_empty)
                meta.isVisible = false
            }
            state.positioningLocation() == null -> {
                heroRoot.findViewById<TextView>(R.id.heroActionSubtitle).text =
                    context.getString(R.string.home_hero_compass_waiting)
                meta.isVisible = false
            }
            else -> {
                heroRoot.findViewById<TextView>(R.id.heroActionSubtitle).text =
                    context.getString(R.string.home_hero_compass_sub)
                val nearest = state.visibleTowersSortedByDistance().firstOrNull()
                if (nearest != null) {
                    val distance = state.distanceTo(nearest)
                    val bearing = state.bearingTo(nearest)
                    val distText = distance?.let {
                        UnitFormat.formatDistance(it, state.distanceUnitSystem)
                    } ?: "—"
                    val azText = bearing?.let { GeoUtils.formatBearing(it) } ?: "—"
                    meta.text = context.getString(
                        R.string.home_hero_compass_meta,
                        nearest.name,
                        distText,
                        azText
                    )
                    meta.isVisible = true
                } else {
                    meta.isVisible = false
                }
            }
        }
    }
}

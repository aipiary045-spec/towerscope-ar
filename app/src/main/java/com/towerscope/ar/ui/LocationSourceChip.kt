package com.towerscope.ar.ui

import android.content.Context
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.towerscope.ar.R
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.LocationMode
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState

/**
 * Compact chip that summarizes the active check location and opens [LocationSourceBottomSheet].
 */
class LocationSourceChip(
    private val chip: TextView,
    private val fragmentManager: FragmentManager,
    private val viewModel: TowerScopeViewModel,
    private val onModeChanged: (() -> Unit)? = null,
    private val onCoordinatesApplied: ((latitude: Double, longitude: Double) -> Unit)? = null
) {
    init {
        chip.setOnClickListener { openSheet() }
    }

    fun render(state: TowerUiState, context: Context) {
        val label = chipLabel(state, context)
        if (chip.text.toString() == label) return
        chip.text = label
    }

    private fun openSheet() {
        if (fragmentManager.findFragmentByTag(LocationSourceBottomSheet.TAG) != null) return
        LocationSourceBottomSheet().apply {
            this.onModeChanged = onModeChanged
            this.onCoordinatesApplied = onCoordinatesApplied
        }.show(fragmentManager, LocationSourceBottomSheet.TAG)
    }

    companion object {
        fun chipLabel(state: TowerUiState, context: Context): String {
            val suffix = "  ▾"
            return when (state.locationMode) {
                LocationMode.CURRENT_GPS -> {
                    val base = if (state.userLocation != null) {
                        context.getString(R.string.location_chip_gps)
                    } else {
                        context.getString(R.string.location_chip_gps_waiting)
                    }
                    base + suffix
                }
                LocationMode.CUSTOM -> {
                    val base = if (state.hasInstallSite) {
                        context.getString(
                            R.string.location_chip_custom,
                            GeoUtils.formatCoordinates(
                                state.installLatitude!!,
                                state.installLongitude!!
                            )
                        )
                    } else {
                        context.getString(R.string.location_chip_custom_unset)
                    }
                    base + suffix
                }
            }
        }
    }
}

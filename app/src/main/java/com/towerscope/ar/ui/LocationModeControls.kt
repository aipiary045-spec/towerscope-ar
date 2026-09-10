package com.towerscope.ar.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.R
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.LocationMode
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState

/**
 * Binds the shared "My location / Other location" toggle used on Locate and LOS screens.
 */
class LocationModeControls(
    root: View,
    private val viewModel: TowerScopeViewModel,
    private val onModeChanged: (() -> Unit)? = null
) {
    private val gpsButton: MaterialButton = root.findViewById(R.id.locationModeGpsButton)
    private val customButton: MaterialButton = root.findViewById(R.id.locationModeCustomButton)
    private val hint: TextView = root.findViewById(R.id.locationModeHint)

    init {
        gpsButton.setOnClickListener {
            if (viewModel.uiState.value.locationMode != LocationMode.CURRENT_GPS) {
                viewModel.setLocationMode(LocationMode.CURRENT_GPS)
                onModeChanged?.invoke()
            }
        }
        customButton.setOnClickListener {
            if (viewModel.uiState.value.locationMode != LocationMode.CUSTOM) {
                viewModel.setLocationMode(LocationMode.CUSTOM)
                onModeChanged?.invoke()
            }
        }
    }

    fun render(state: TowerUiState, context: Context) {
        val isGps = state.locationMode == LocationMode.CURRENT_GPS
        styleSelected(gpsButton, isGps, context)
        styleSelected(customButton, !isGps, context)
        hint.text = locationModeHint(state, context)
    }

    private fun styleSelected(button: MaterialButton, selected: Boolean, context: Context) {
        if (selected) {
            button.setBackgroundResource(R.drawable.bg_btn_primary)
            button.setTextColor(ContextCompat.getColor(context, R.color.text_on_accent))
            button.strokeWidth = 0
        } else {
            button.background = null
            button.setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
            button.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            button.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
            button.strokeColor = ContextCompat.getColorStateList(context, R.color.border_strong)
        }
    }

    companion object {
        fun locationModeHint(state: TowerUiState, context: Context): String {
            return when (state.locationMode) {
                LocationMode.CURRENT_GPS -> {
                    val user = state.userLocation
                    if (user != null) {
                        context.getString(
                            R.string.location_mode_gps_hint,
                            GeoUtils.formatCoordinates(user.latitude, user.longitude)
                        )
                    } else {
                        context.getString(R.string.location_mode_gps_waiting)
                    }
                }
                LocationMode.CUSTOM -> {
                    if (state.hasInstallSite) {
                        context.getString(
                            R.string.location_mode_custom_set,
                            GeoUtils.formatCoordinates(
                                state.installLatitude!!,
                                state.installLongitude!!
                            )
                        )
                    } else {
                        context.getString(R.string.location_mode_custom_unset)
                    }
                }
            }
        }
    }
}

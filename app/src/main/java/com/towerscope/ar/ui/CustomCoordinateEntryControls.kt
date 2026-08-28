package com.towerscope.ar.ui

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.R
import com.towerscope.ar.util.CoordinateParser
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import java.util.Locale

/**
 * Lat/lon entry for custom check locations on the Locate map.
 */
class CustomCoordinateEntryControls(
    private val root: View,
    private val viewModel: TowerScopeViewModel,
    private val onCoordinatesApplied: (latitude: Double, longitude: Double) -> Unit
) {
    private val latitudeInput: EditText = root.findViewById(R.id.coordinateLatitudeInput)
    private val longitudeInput: EditText = root.findViewById(R.id.coordinateLongitudeInput)
    private val errorLabel: TextView = root.findViewById(R.id.coordinateEntryError)
    private val pasteButton: MaterialButton = root.findViewById(R.id.coordinatePasteButton)
    private val applyButton: MaterialButton = root.findViewById(R.id.coordinateApplyButton)

    init {
        pasteButton.setOnClickListener { pasteFromClipboard(it.context) }
        applyButton.setOnClickListener { applyCoordinates() }
    }

    fun render(state: TowerUiState) {
        val show = state.locationMode == com.towerscope.ar.viewmodel.LocationMode.CUSTOM
        root.isVisible = show
        if (!show) return

        val latFocused = latitudeInput.hasFocus()
        val lonFocused = longitudeInput.hasFocus()
        if (!latFocused && !lonFocused && state.hasInstallSite) {
            val lat = state.installLatitude
            val lon = state.installLongitude
            if (lat != null && lon != null) {
                val latText = formatField(lat)
                val lonText = formatField(lon)
                if (latitudeInput.text.toString() != latText) {
                    latitudeInput.setText(latText)
                }
                if (longitudeInput.text.toString() != lonText) {
                    longitudeInput.setText(lonText)
                }
            }
        }
    }

    private fun pasteFromClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) {
            showError(context.getString(R.string.coordinate_entry_clipboard_empty))
            return
        }
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        val result = CoordinateParser.parseClipboard(
            clipboardText = text,
            latitudeInput = latitudeInput.text.toString(),
            longitudeInput = longitudeInput.text.toString()
        )
        result.fold(
            onSuccess = { parsed ->
                clearError()
                latitudeInput.setText(formatField(parsed.latitude))
                longitudeInput.setText(formatField(parsed.longitude))
            },
            onFailure = { error ->
                showError(error.message ?: context.getString(R.string.coordinate_entry_invalid))
            }
        )
    }

    private fun applyCoordinates() {
        val context = root.context
        val result = CoordinateParser.parseFields(
            latitudeInput = latitudeInput.text.toString(),
            longitudeInput = longitudeInput.text.toString()
        )
        result.fold(
            onSuccess = { parsed ->
                clearError()
                viewModel.setInstallSite(parsed.latitude, parsed.longitude)
                onCoordinatesApplied(parsed.latitude, parsed.longitude)
            },
            onFailure = { error ->
                showError(error.message ?: context.getString(R.string.coordinate_entry_invalid))
            }
        )
    }

    private fun showError(message: String) {
        errorLabel.text = message
        errorLabel.isVisible = true
    }

    private fun clearError() {
        errorLabel.isVisible = false
    }

    private fun formatField(value: Double): String =
        String.format(Locale.US, "%.6f", value)
}

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
 * Single-field GPS coordinate entry for custom check locations.
 */
class CustomCoordinateEntryControls(
    private val root: View,
    private val viewModel: TowerScopeViewModel,
    private val onCoordinatesApplied: (latitude: Double, longitude: Double) -> Unit
) {
    private val coordinateInput: EditText = root.findViewById(R.id.coordinateInput)
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
        if (!show || coordinateInput.hasFocus()) return

        if (state.hasInstallSite) {
            val lat = state.installLatitude
            val lon = state.installLongitude
            if (lat != null && lon != null) {
                val text = formatPair(lat, lon)
                if (coordinateInput.text.toString() != text) {
                    coordinateInput.setText(text)
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
        val result = CoordinateParser.parsePair(text)
        result.fold(
            onSuccess = { parsed ->
                clearError()
                coordinateInput.setText(formatPair(parsed.latitude, parsed.longitude))
            },
            onFailure = {
                // Keep raw clipboard text so the user can edit before applying.
                clearError()
                coordinateInput.setText(text.trim())
            }
        )
    }

    private fun applyCoordinates() {
        val context = root.context
        val result = CoordinateParser.parsePair(coordinateInput.text.toString())
        result.fold(
            onSuccess = { parsed ->
                clearError()
                coordinateInput.setText(formatPair(parsed.latitude, parsed.longitude))
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

    private fun formatPair(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
}

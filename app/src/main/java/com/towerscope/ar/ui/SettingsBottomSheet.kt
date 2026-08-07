package com.towerscope.ar.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.DataMenuActivity
import com.towerscope.ar.R
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch

/**
 * Secondary controls moved off the AR scan HUD (data, theme, cal, filters).
 */
class SettingsBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: TowerScopeViewModel by activityViewModels()
    private var suppressSearchCallback = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_settings, container, false)

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val status = view.findViewById<TextView>(R.id.settingsStatus)
        val dataButton = view.findViewById<MaterialButton>(R.id.settingsDataButton)
        val themeButton = view.findViewById<TextView>(R.id.settingsThemeButton)
        val altitudeButton = view.findViewById<TextView>(R.id.settingsAltitudeButton)
        val calibrateButton = view.findViewById<MaterialButton>(R.id.settingsCalibrateButton)
        val searchField = view.findViewById<EditText>(R.id.settingsSearchField)
        val distanceLabel = view.findViewById<TextView>(R.id.settingsDistanceLabel)
        val distanceSlider = view.findViewById<SeekBar>(R.id.settingsDistanceSlider)
        val showHiddenButton = view.findViewById<MaterialButton>(R.id.settingsShowHiddenButton)
        val closeButton = view.findViewById<MaterialButton>(R.id.settingsCloseButton)

        distanceSlider.max =
            (TowerUiState.MAX_DISTANCE_METERS - TowerUiState.MIN_DISTANCE_METERS).toInt()

        dataButton.setOnClickListener {
            startActivity(Intent(requireContext(), DataMenuActivity::class.java))
            dismiss()
        }
        themeButton.setOnClickListener { viewModel.cycleHudTheme() }
        altitudeButton.setOnClickListener { viewModel.toggleUseKmlAltitude() }
        calibrateButton.setOnClickListener {
            viewModel.beginHeadingCalibration()
            dismiss()
        }
        calibrateButton.setOnLongClickListener {
            if (viewModel.uiState.value.isHeadingCalibrated) {
                viewModel.clearHeadingCalibration()
                true
            } else {
                false
            }
        }
        showHiddenButton.setOnClickListener { viewModel.clearHiddenTowers() }
        closeButton.setOnClickListener { dismiss() }

        distanceSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                viewModel.setMaxDistanceMeters(TowerUiState.MIN_DISTANCE_METERS + progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressSearchCallback) return
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    themeButton.text = state.hudTheme.label
                    altitudeButton.text = if (state.useKmlAltitude) "KML alt" else "Ground"
                    calibrateButton.text = if (state.isHeadingCalibrated) {
                        "Calibrate with sun / moon  ✓"
                    } else {
                        "Calibrate with sun / moon"
                    }

                    distanceLabel.text =
                        "RANGE  ${GeoUtils.formatDistance(state.maxDistanceMeters.toDouble())}"
                    val progress =
                        (state.maxDistanceMeters - TowerUiState.MIN_DISTANCE_METERS).toInt()
                    if (distanceSlider.progress != progress) {
                        distanceSlider.progress = progress
                    }

                    if (searchField.text.toString() != state.searchQuery) {
                        suppressSearchCallback = true
                        searchField.setText(state.searchQuery)
                        searchField.setSelection(state.searchQuery.length)
                        suppressSearchCallback = false
                    }

                    showHiddenButton.isVisible = state.hiddenTowerIds.isNotEmpty()

                    val message = state.errorMessage ?: state.statusMessage
                    status.isVisible = message != null
                    status.text = message.orEmpty()
                }
            }
        }
    }

    companion object {
        const val TAG = "SettingsBottomSheet"

        fun newInstance(): SettingsBottomSheet = SettingsBottomSheet()
    }
}

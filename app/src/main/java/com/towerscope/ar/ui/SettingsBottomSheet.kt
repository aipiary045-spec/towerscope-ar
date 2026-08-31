package com.towerscope.ar.ui

import android.content.Intent
import android.hardware.SensorManager
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
import androidx.core.content.ContextCompat
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
import com.towerscope.ar.MainActivity
import com.towerscope.ar.R
import com.towerscope.ar.util.CoordinateFormat
import com.towerscope.ar.util.DistanceUnitSystem
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.util.LinkEstimate
import com.towerscope.ar.viewmodel.LocationMode
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch

/**
 * Secondary controls (data, theme, compass, filters).
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
        val distanceUnitsButton = view.findViewById<TextView>(R.id.settingsDistanceUnitsButton)
        val coordFormatButton = view.findViewById<TextView>(R.id.settingsCoordFormatButton)
        val calibrateButton = view.findViewById<MaterialButton>(R.id.settingsCalibrateButton)
        val calibrateState = view.findViewById<TextView>(R.id.settingsCalibrateState)
        val offsetLabel = view.findViewById<TextView>(R.id.settingsOffsetLabel)
        val offsetMinus = view.findViewById<MaterialButton>(R.id.settingsOffsetMinusButton)
        val offsetPlus = view.findViewById<MaterialButton>(R.id.settingsOffsetPlusButton)
        val clearOffset = view.findViewById<MaterialButton>(R.id.settingsClearOffsetButton)
        val searchField = view.findViewById<EditText>(R.id.settingsSearchField)
        val distanceLabel = view.findViewById<TextView>(R.id.settingsDistanceLabel)
        val distanceSlider = view.findViewById<SeekBar>(R.id.settingsDistanceSlider)
        val frequencyLabel = view.findViewById<TextView>(R.id.settingsFrequencyLabel)
        val frequencySlider = view.findViewById<SeekBar>(R.id.settingsFrequencySlider)
        val cpeHeightLabel = view.findViewById<TextView>(R.id.settingsCpeHeightLabel)
        val cpeHeightSlider = view.findViewById<SeekBar>(R.id.settingsCpeHeightSlider)
        val txPowerLabel = view.findViewById<TextView>(R.id.settingsTxPowerLabel)
        val txPowerSlider = view.findViewById<SeekBar>(R.id.settingsTxPowerSlider)
        val apGainLabel = view.findViewById<TextView>(R.id.settingsApGainLabel)
        val apGainSlider = view.findViewById<SeekBar>(R.id.settingsApGainSlider)
        val cpeGainLabel = view.findViewById<TextView>(R.id.settingsCpeGainLabel)
        val cpeGainSlider = view.findViewById<SeekBar>(R.id.settingsCpeGainSlider)
        val installStatus = view.findViewById<TextView>(R.id.settingsInstallStatus)
        val clearInstallButton = view.findViewById<MaterialButton>(R.id.settingsClearInstallButton)
        val showHiddenButton = view.findViewById<MaterialButton>(R.id.settingsShowHiddenButton)
        val closeButton = view.findViewById<MaterialButton>(R.id.settingsCloseButton)

        bindAccordion(
            header = view.findViewById(R.id.settingsHeaderAppearance),
            body = view.findViewById(R.id.settingsBodyAppearance),
            chevron = view.findViewById(R.id.settingsChevronAppearance),
            expanded = true
        )
        bindAccordion(
            header = view.findViewById(R.id.settingsHeaderSites),
            body = view.findViewById(R.id.settingsBodySites),
            chevron = view.findViewById(R.id.settingsChevronSites)
        )
        bindAccordion(
            header = view.findViewById(R.id.settingsHeaderUnits),
            body = view.findViewById(R.id.settingsBodyUnits),
            chevron = view.findViewById(R.id.settingsChevronUnits)
        )
        bindAccordion(
            header = view.findViewById(R.id.settingsHeaderCompass),
            body = view.findViewById(R.id.settingsBodyCompass),
            chevron = view.findViewById(R.id.settingsChevronCompass)
        )
        bindAccordion(
            header = view.findViewById(R.id.settingsHeaderRf),
            body = view.findViewById(R.id.settingsBodyRf),
            chevron = view.findViewById(R.id.settingsChevronRf)
        )
        bindAccordion(
            header = view.findViewById(R.id.settingsHeaderFilter),
            body = view.findViewById(R.id.settingsBodyFilter),
            chevron = view.findViewById(R.id.settingsChevronFilter)
        )

        val frequencyPresets = listOf(2.4f, 3.65f, 5.2f, 5.8f, 6.0f, 24.0f, 60.0f)
        frequencySlider.max = frequencyPresets.lastIndex
        cpeHeightSlider.max =
            (TowerUiState.MAX_CPE_ANTENNA_AGL_METERS - TowerUiState.MIN_CPE_ANTENNA_AGL_METERS).toInt()
        txPowerSlider.max = LinkEstimate.MAX_TX_POWER_DBM.toInt()
        apGainSlider.max = LinkEstimate.MAX_ANTENNA_GAIN_DBI.toInt()
        cpeGainSlider.max = LinkEstimate.MAX_ANTENNA_GAIN_DBI.toInt()

        distanceSlider.max =
            (TowerUiState.MAX_DISTANCE_METERS - TowerUiState.MIN_DISTANCE_METERS).toInt()

        distanceSlider.progressDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_progress)
        distanceSlider.thumb = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_thumb)
        frequencySlider.progressDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_progress)
        frequencySlider.thumb = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_thumb)
        cpeHeightSlider.progressDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_progress)
        cpeHeightSlider.thumb = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_thumb)
        txPowerSlider.progressDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_progress)
        txPowerSlider.thumb = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_thumb)
        apGainSlider.progressDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_progress)
        apGainSlider.thumb = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_thumb)
        cpeGainSlider.progressDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_progress)
        cpeGainSlider.thumb = ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_thumb)

        dataButton.setOnClickListener {
            startActivity(Intent(requireContext(), DataMenuActivity::class.java))
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.settingsFieldPresetButton).setOnClickListener {
            viewModel.applyFieldPreset()
            status.text = getString(R.string.settings_field_preset)
            status.isVisible = true
        }
        themeButton.setOnClickListener {
            viewModel.cycleHudTheme()
            dismissAllowingStateLoss()
            activity?.recreate()
        }
        distanceUnitsButton.setOnClickListener { viewModel.cycleDistanceUnitSystem() }
        coordFormatButton.setOnClickListener { viewModel.cycleCoordinateFormat() }
        calibrateButton.setOnClickListener {
            dismiss()
            if (activity is MainActivity) {
                viewModel.beginCompassImprove()
            } else {
                startActivity(
                    Intent(requireContext(), MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_START_COMPASS_IMPROVE, true)
                )
            }
        }
        offsetMinus.setOnClickListener { viewModel.nudgeHeadingOffset(-1.0) }
        offsetPlus.setOnClickListener { viewModel.nudgeHeadingOffset(1.0) }
        clearOffset.setOnClickListener { viewModel.clearHeadingOffset() }
        showHiddenButton.setOnClickListener { viewModel.clearHiddenTowers() }
        clearInstallButton.setOnClickListener { viewModel.clearInstallSite() }
        closeButton.setOnClickListener { dismiss() }

        distanceSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val meters = TowerUiState.MIN_DISTANCE_METERS + progress
                distanceLabel.text =
                    "RANGE  ${GeoUtils.formatDistance(meters.toDouble())}"
                if (!fromUser) return
                viewModel.setMaxDistanceMeters(meters)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        frequencySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ghz = frequencyPresets[progress.coerceIn(0, frequencyPresets.lastIndex)]
                frequencyLabel.text = String.format(java.util.Locale.US, "FREQ  %.1f GHz", ghz)
                if (!fromUser) return
                viewModel.setFrequencyGhz(ghz)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        cpeHeightSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val meters = TowerUiState.MIN_CPE_ANTENNA_AGL_METERS + progress
                cpeHeightLabel.text = String.format(java.util.Locale.US, "CPE height  %.0f m", meters)
                if (!fromUser) return
                viewModel.setCpeAntennaAglMeters(meters)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        txPowerSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txPowerLabel.text = String.format(java.util.Locale.US, "TX POWER  %d dBm", progress)
                if (!fromUser) return
                viewModel.setTxPowerDbm(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        apGainSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                apGainLabel.text = String.format(java.util.Locale.US, "AP GAIN  %d dBi", progress)
                if (!fromUser) return
                viewModel.setApAntennaGainDbi(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        cpeGainSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                cpeGainLabel.text = String.format(java.util.Locale.US, "CPE GAIN  %d dBi", progress)
                if (!fromUser) return
                viewModel.setCpeAntennaGainDbi(progress.toFloat())
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
                    view.findViewById<TextView>(R.id.settingsSummaryAppearance).text =
                        state.hudTheme.label
                    distanceUnitsButton.text = when (state.distanceUnitSystem) {
                        DistanceUnitSystem.IMPERIAL -> "mi / ft"
                        DistanceUnitSystem.METRIC -> "km / m"
                    }
                    val unitsLabel = when (state.distanceUnitSystem) {
                        DistanceUnitSystem.IMPERIAL -> "mi / ft"
                        DistanceUnitSystem.METRIC -> "km / m"
                    }
                    val coordLabel = when (state.coordinateFormat) {
                        CoordinateFormat.DECIMAL -> "Decimal"
                        CoordinateFormat.DMS -> "DMS"
                    }
                    view.findViewById<TextView>(R.id.settingsSummaryUnits).text =
                        "$unitsLabel · $coordLabel"
                    coordFormatButton.text = coordLabel

                    val sensorLabel = when {
                        state.deviceHeadingDegrees == null -> "SENSOR  —"
                        state.compassSensorAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_HIGH ->
                            "SENSOR  High"
                        state.compassSensorAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
                            "SENSOR  OK"
                        state.compassSensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
                            "SENSOR  Low — improve"
                        else -> "SENSOR  Unreliable"
                    }
                    calibrateState.text = sensorLabel
                    if (state.needsCompassCalibration || state.deviceHeadingDegrees == null) {
                        calibrateState.setTextColor(requireContext().getColor(R.color.accent_yellow))
                        calibrateState.setBackgroundResource(R.drawable.bg_cal_state_needed)
                    } else {
                        calibrateState.setTextColor(requireContext().getColor(R.color.status_clear))
                        calibrateState.setBackgroundResource(R.drawable.bg_cal_state_ok)
                    }

                    val offset = state.headingCalibrationOffsetDegrees
                    offsetLabel.text = if (offset != null) {
                        String.format(java.util.Locale.US, "OFFSET  %+.0f°", offset)
                    } else {
                        "OFFSET  none"
                    }
                    clearOffset.isVisible = offset != null

                    distanceLabel.text =
                        "RANGE  ${GeoUtils.formatDistance(state.maxDistanceMeters.toDouble())}"
                    val progress =
                        (state.maxDistanceMeters - TowerUiState.MIN_DISTANCE_METERS).toInt()
                    if (distanceSlider.progress != progress) {
                        distanceSlider.progress = progress
                    }

                    val freqIndex = frequencyPresets.indexOfFirst {
                        kotlin.math.abs(it - state.frequencyGhz) < 0.05f
                    }.let { if (it < 0) 3 else it }
                    if (frequencySlider.progress != freqIndex) {
                        frequencySlider.progress = freqIndex
                    }
                    frequencyLabel.text =
                        String.format(java.util.Locale.US, "FREQ  %.1f GHz", state.frequencyGhz)

                    val cpeProgress =
                        (state.cpeAntennaAglMeters - TowerUiState.MIN_CPE_ANTENNA_AGL_METERS).toInt()
                    if (cpeHeightSlider.progress != cpeProgress) {
                        cpeHeightSlider.progress = cpeProgress
                    }
                    cpeHeightLabel.text = String.format(
                        java.util.Locale.US,
                        "CPE height  %.0f m",
                        state.cpeAntennaAglMeters
                    )

                    val txProgress = state.txPowerDbm.toInt()
                    if (txPowerSlider.progress != txProgress) txPowerSlider.progress = txProgress
                    txPowerLabel.text =
                        String.format(java.util.Locale.US, "TX POWER  %.0f dBm", state.txPowerDbm)
                    val apGainProgress = state.apAntennaGainDbi.toInt()
                    if (apGainSlider.progress != apGainProgress) apGainSlider.progress = apGainProgress
                    apGainLabel.text =
                        String.format(java.util.Locale.US, "AP GAIN  %.0f dBi", state.apAntennaGainDbi)
                    val cpeGainProgress = state.cpeAntennaGainDbi.toInt()
                    if (cpeGainSlider.progress != cpeGainProgress) {
                        cpeGainSlider.progress = cpeGainProgress
                    }
                    cpeGainLabel.text =
                        String.format(java.util.Locale.US, "CPE GAIN  %.0f dBi", state.cpeAntennaGainDbi)

                    if (state.hasInstallSite) {
                        val active = state.locationMode == LocationMode.CUSTOM
                        installStatus.text = String.format(
                            java.util.Locale.US,
                            if (active) {
                                "Check location · custom · %.5f, %.5f"
                            } else {
                                "Pinned location (inactive) · %.5f, %.5f"
                            },
                            state.installLatitude,
                            state.installLongitude
                        )
                        clearInstallButton.isVisible = true
                    } else {
                        installStatus.text = when (state.locationMode) {
                            LocationMode.CURRENT_GPS ->
                                "Check location · using your GPS (pin on Locate map)"
                            LocationMode.CUSTOM ->
                                "Check location · set a custom pin on Locate map"
                        }
                        clearInstallButton.isVisible = false
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

    private fun bindAccordion(
        header: View,
        body: View,
        chevron: TextView,
        expanded: Boolean = false
    ) {
        fun applyExpanded(isExpanded: Boolean) {
            body.isVisible = isExpanded
            chevron.rotation = if (isExpanded) 90f else 0f
            header.isSelected = isExpanded
        }
        applyExpanded(expanded)
        header.setOnClickListener {
            applyExpanded(!body.isVisible)
        }
    }

    companion object {
        const val TAG = "SettingsBottomSheet"

        fun newInstance(): SettingsBottomSheet = SettingsBottomSheet()
    }
}

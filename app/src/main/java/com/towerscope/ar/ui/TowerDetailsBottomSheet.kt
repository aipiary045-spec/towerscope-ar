package com.towerscope.ar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.towerscope.ar.R
import com.towerscope.ar.data.ElevationSource
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch
import java.util.Locale

class TowerDetailsBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: TowerScopeViewModel by activityViewModels()
    private lateinit var towerId: String
    private var suppressLosSwitchCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        towerId = requireArguments().getString(ARG_TOWER_ID)
            ?: error("towerId required")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_tower_details, container, false)

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
        val detailName = view.findViewById<TextView>(R.id.detailName)
        val detailDistance = view.findViewById<TextView>(R.id.detailDistance)
        val detailBearing = view.findViewById<TextView>(R.id.detailBearing)
        val detailCoords = view.findViewById<TextView>(R.id.detailCoords)
        val detailAltitude = view.findViewById<TextView>(R.id.detailAltitude)
        val losStatus = view.findViewById<TextView>(R.id.losStatus)
        val losChart = view.findViewById<LosProfileChartView>(R.id.losChart)
        val clutterLabel = view.findViewById<TextView>(R.id.clutterLabel)
        val clutterSlider = view.findViewById<SeekBar>(R.id.clutterSlider)
        val losSection = view.findViewById<View>(R.id.losSection)
        val losSwitch = view.findViewById<SwitchMaterial>(R.id.detailLosSwitch)
        val frequencyLabel = view.findViewById<TextView>(R.id.detailFrequencyLabel)
        val frequencySlider = view.findViewById<SeekBar>(R.id.detailFrequencySlider)
        val cpeHeightLabel = view.findViewById<TextView>(R.id.detailCpeHeightLabel)
        val cpeHeightSlider = view.findViewById<SeekBar>(R.id.detailCpeHeightSlider)

        val frequencyPresets = listOf(2.4f, 3.65f, 5.2f, 5.8f, 6.0f, 24.0f, 60.0f)
        frequencySlider.max = frequencyPresets.lastIndex
        cpeHeightSlider.max =
            (TowerUiState.MAX_CPE_ANTENNA_AGL_METERS - TowerUiState.MIN_CPE_ANTENNA_AGL_METERS).toInt()
        frequencySlider.progressDrawable =
            ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_progress)
        frequencySlider.thumb =
            ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_thumb)
        cpeHeightSlider.progressDrawable =
            ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_progress)
        cpeHeightSlider.thumb =
            ContextCompat.getDrawable(requireContext(), R.drawable.bg_seekbar_thumb)

        view.findViewById<Button>(R.id.closeSheetButton).setOnClickListener { dismiss() }
        view.findViewById<Button>(R.id.showOnlyButton).setOnClickListener {
            viewModel.showOnlyTower(towerId)
            dismiss()
        }
        view.findViewById<Button>(R.id.hideTowerButton).setOnClickListener {
            viewModel.hideTower(towerId)
            dismiss()
        }
        view.findViewById<Button>(R.id.copyCoordsButton).setOnClickListener {
            val tower = viewModel.uiState.value.towerById(towerId) ?: return@setOnClickListener
            val text = String.format(
                Locale.US,
                "%s\n%.6f, %.6f",
                tower.name,
                tower.latitude,
                tower.longitude
            )
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("tower_coords", text))
            Toast.makeText(requireContext(), "Coordinates copied", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.openMapsButton).setOnClickListener {
            val tower = viewModel.uiState.value.towerById(towerId) ?: return@setOnClickListener
            val encodedName = Uri.encode(tower.name)
            val uri = Uri.parse(
                String.format(
                    Locale.US,
                    "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                    tower.latitude,
                    tower.longitude,
                    tower.latitude,
                    tower.longitude,
                    encodedName
                )
            )
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        clutterSlider.max = TowerUiState.MAX_CLUTTER_METERS.toInt()
        clutterSlider.progress = viewModel.uiState.value.clutterHeightMeters.toInt()
        clutterSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.setClutterHeightMeters(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        frequencySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ghz = frequencyPresets[progress.coerceIn(0, frequencyPresets.lastIndex)]
                frequencyLabel.text = String.format(Locale.US, "FREQ  %.1f GHz", ghz)
                if (!fromUser) return
                viewModel.setFrequencyGhz(ghz)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        cpeHeightSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val meters = TowerUiState.MIN_CPE_ANTENNA_AGL_METERS + progress
                cpeHeightLabel.text = String.format(Locale.US, "CPE height  %.0f m", meters)
                if (!fromUser) return
                viewModel.setCpeAntennaAglMeters(meters)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        losSwitch.isChecked = viewModel.uiState.value.showElevationProfile
        losSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressLosSwitchCallback) return@setOnCheckedChangeListener
            viewModel.setShowElevationProfile(checked)
        }

        if (viewModel.uiState.value.showElevationProfile) {
            viewModel.loadLosProfile(towerId)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val tower = state.towerById(towerId)
                    if (tower == null) {
                        dismissAllowingStateLoss()
                        return@collect
                    }
                    detailName.text = tower.name
                    val distance = state.distanceTo(tower)
                    detailDistance.text = if (distance != null) {
                        "Distance  ${GeoUtils.formatDistance(distance)}"
                    } else {
                        "Distance  —"
                    }
                    val bearing = state.bearingTo(tower)
                    val heading = state.effectiveHeadingDegrees()
                    detailBearing.text = when {
                        bearing != null && heading != null -> {
                            val relative = GeoUtils.relativeBearingDegrees(heading, bearing)
                            "Bearing  ${GeoUtils.formatBearing(bearing)}  ·  ${GeoUtils.formatRelativeTurn(relative)}"
                        }
                        bearing != null -> "Bearing  ${GeoUtils.formatBearing(bearing)}"
                        else -> "Bearing  —"
                    }
                    detailCoords.text = "Lat/Lon  " + GeoUtils.formatCoordinates(
                        tower.latitude,
                        tower.longitude
                    )
                    detailAltitude.text = tower.altitudeMeters?.let {
                        String.format(Locale.US, "Altitude  %.1f m", it)
                    } ?: "Altitude  —"

                    val freqIndex = frequencyPresets.indexOfFirst {
                        kotlin.math.abs(it - state.frequencyGhz) < 0.05f
                    }.let { if (it < 0) 3 else it }
                    if (frequencySlider.progress != freqIndex) {
                        frequencySlider.progress = freqIndex
                    }
                    frequencyLabel.text =
                        String.format(Locale.US, "FREQ  %.1f GHz", state.frequencyGhz)
                    val cpeProgress =
                        (state.cpeAntennaAglMeters - TowerUiState.MIN_CPE_ANTENNA_AGL_METERS).toInt()
                    if (cpeHeightSlider.progress != cpeProgress) {
                        cpeHeightSlider.progress = cpeProgress
                    }
                    cpeHeightLabel.text = String.format(
                        Locale.US,
                        "CPE height  %.0f m",
                        state.cpeAntennaAglMeters
                    )

                    if (losSwitch.isChecked != state.showElevationProfile) {
                        suppressLosSwitchCallback = true
                        losSwitch.isChecked = state.showElevationProfile
                        suppressLosSwitchCallback = false
                    }

                    renderLos(state, losSection, losStatus, losChart, clutterLabel, clutterSlider)
                }
            }
        }
    }

    private fun renderLos(
        state: TowerUiState,
        losSection: View,
        losStatus: TextView,
        losChart: LosProfileChartView,
        clutterLabel: TextView,
        clutterSlider: SeekBar
    ) {
        if (!state.showElevationProfile) {
            losSection.isVisible = false
            return
        }
        losSection.isVisible = true

        val clutter = state.clutterHeightMeters.toDouble()
        val profileForLabel = state.losProfile
        val clutterApplies = profileForLabel == null ||
            profileForLabel.samples.any { it.source == ElevationSource.DEM }
        clutterLabel.text = if (profileForLabel != null && profileForLabel.usesLidar && !clutterApplies) {
            String.format(Locale.US, "Clutter (DEM only)  %.0f m · LiDAR surface", clutter)
        } else if (profileForLabel != null && profileForLabel.usesLidar) {
            String.format(
                Locale.US,
                "Clutter (DEM gaps)  %.0f m · LiDAR %.0f%%",
                clutter,
                profileForLabel.lidarCoverageFraction * 100.0
            )
        } else {
            String.format(Locale.US, "Clutter (trees)  %.0f m · 3DEP DEM", clutter)
        }
        if (clutterSlider.progress != state.clutterHeightMeters.toInt()) {
            clutterSlider.progress = state.clutterHeightMeters.toInt()
        }

        when {
            state.losProfileLoading && state.losProfile == null -> {
                losStatus.text = "Querying LiDAR / 3DEP elevations…"
                losStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
                losChart.isVisible = false
            }
            state.losProfileError != null && state.losProfile == null -> {
                losStatus.text = state.losProfileError
                losStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.chip_poor))
                losChart.isVisible = false
            }
            state.losProfile != null -> {
                val profile = state.losProfile
                val freq = state.frequencyGhz.toDouble()
                val clearance = profile.minClearanceMeters(clutter)
                val fresnel = profile.minFresnelClearanceMeters(clutter, freq)
                val clear = clearance > 0.0
                val fresnelClear = fresnel > 0.0
                val sourceLabel = when {
                    profile.lidarCoverageFraction >= 0.9 -> "LiDAR surface"
                    profile.usesLidar -> String.format(
                        Locale.US,
                        "LiDAR+DEM · %.0f%% LiDAR",
                        profile.lidarCoverageFraction * 100.0
                    )
                    else -> "3DEP DEM"
                }
                losStatus.text = when {
                    fresnelClear -> String.format(
                        Locale.US,
                        "F1 OK · +%.0f m (60%%) · geo %.0f m · %.1f GHz · %s",
                        fresnel,
                        clearance,
                        freq,
                        sourceLabel
                    )
                    clear -> String.format(
                        Locale.US,
                        "Geo clear %.0f m · F1 short %.0f m · %.1f GHz · %s",
                        clearance,
                        -fresnel,
                        freq,
                        sourceLabel
                    )
                    else -> String.format(
                        Locale.US,
                        "Blocked · short %.0f m · %.1f GHz · %s",
                        -clearance,
                        freq,
                        sourceLabel
                    )
                }
                losStatus.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        when {
                            fresnelClear -> R.color.chip_good
                            clear -> R.color.chip_fair
                            else -> R.color.chip_poor
                        }
                    )
                )
                losChart.isVisible = true
                losChart.setProfile(profile, clutter, freq)
            }
            else -> {
                losStatus.text = "Line-of-sight profile unavailable"
                losStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
                losChart.isVisible = false
            }
        }
    }

    companion object {
        const val TAG = "TowerDetailsBottomSheet"
        private const val ARG_TOWER_ID = "tower_id"

        fun newInstance(towerId: String): TowerDetailsBottomSheet =
            TowerDetailsBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_TOWER_ID, towerId) }
            }
    }
}

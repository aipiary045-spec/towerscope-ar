package com.towerscope.ar

import android.Manifest
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.LinearLayoutManager
import com.towerscope.ar.ui.LosRangeRowAdapter
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.ToolScaffold
import com.towerscope.ar.ui.ToolTopology
import com.towerscope.ar.ui.LocationSourceChip
import com.towerscope.ar.ui.TowerDetailsBottomSheet
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.util.LinkEstimate
import com.towerscope.ar.viewmodel.LocationMode
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Ranked LOS elevation profiles for towers in the saved range.
 */
class LosProfilesActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var rowAdapter: LosRangeRowAdapter
    private lateinit var frequencyLabel: TextView
    private lateinit var frequencySlider: SeekBar
    private lateinit var cpeHeightLabel: TextView
    private lateinit var cpeHeightSlider: SeekBar
    private lateinit var txPowerLabel: TextView
    private lateinit var txPowerSlider: SeekBar
    private lateinit var apGainLabel: TextView
    private lateinit var apGainSlider: SeekBar
    private lateinit var cpeGainLabel: TextView
    private lateinit var cpeGainSlider: SeekBar
    private lateinit var linkSettingsSummary: TextView
    private lateinit var linkSettingsToggle: TextView
    private lateinit var linkSettingsExpanded: View
    private lateinit var locationSourceChip: LocationSourceChip
    private var linkSettingsOpen = false
    private var startedScan = false
    private var priorityTowerId: String? = null
    private var lastCpeHeight: Float? = null
    private var lastRowsKey: String? = null
    private val frequencyPresets = listOf(2.4f, 3.65f, 5.2f, 5.8f, 6.0f, 24.0f, 60.0f)
    private val shimmerAnimators = mutableListOf<ObjectAnimator>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOk || coarseOk) {
            viewModel.startLocationUpdates()
            maybeStartScan()
        } else {
            status.text = "Location permission is required for elevation profiles"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_los_profiles)
        SystemBars.apply(
            root = findViewById(R.id.losRoot),
            alsoBottom = findViewById(R.id.losFooter)
        )
        ToolTopology.bindWhenResumed(this, findViewById(R.id.losRoot))
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]
        priorityTowerId = TowerIntents.towerIdFrom(intent)?.also { viewModel.selectTower(it) }
        ToolScaffold.bind(
            activity = this,
            titleRes = R.string.los_check_title,
            subtitleRes = R.string.los_check_subtitle
        )

        subtitle = findViewById(R.id.losRangeSubtitle)
        status = findViewById(R.id.losRangeStatus)
        rowAdapter = LosRangeRowAdapter(
            onRowClick = { towerId ->
                viewModel.selectTower(towerId)
                if (supportFragmentManager.findFragmentByTag(TowerDetailsBottomSheet.TAG) == null) {
                    TowerDetailsBottomSheet.newInstance(towerId)
                        .show(supportFragmentManager, TowerDetailsBottomSheet.TAG)
                }
            },
            onShimmerStart = { shimmer1, shimmer2 ->
                LosRangeRowAdapter.pulse(shimmer1, shimmerAnimators)
                LosRangeRowAdapter.pulse(shimmer2, shimmerAnimators)
            }
        )
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.losRangeList).apply {
            layoutManager = LinearLayoutManager(this@LosProfilesActivity)
            adapter = rowAdapter
            itemAnimator = null
        }
        frequencyLabel = findViewById(R.id.losFrequencyLabel)
        frequencySlider = findViewById(R.id.losFrequencySlider)
        cpeHeightLabel = findViewById(R.id.losCpeHeightLabel)
        cpeHeightSlider = findViewById(R.id.losCpeHeightSlider)
        txPowerLabel = findViewById(R.id.losTxPowerLabel)
        txPowerSlider = findViewById(R.id.losTxPowerSlider)
        apGainLabel = findViewById(R.id.losApGainLabel)
        apGainSlider = findViewById(R.id.losApGainSlider)
        cpeGainLabel = findViewById(R.id.losCpeGainLabel)
        cpeGainSlider = findViewById(R.id.losCpeGainSlider)
        linkSettingsSummary = findViewById(R.id.losLinkSettingsSummary)
        linkSettingsToggle = findViewById(R.id.losLinkSettingsToggle)
        linkSettingsExpanded = findViewById(R.id.losLinkSettingsExpanded)
        locationSourceChip = LocationSourceChip(
            chip = findViewById(R.id.losLocationChip),
            fragmentManager = supportFragmentManager,
            viewModel = viewModel,
            onModeChanged = {
                startedScan = false
                maybeStartScan(force = true)
            }
        )

        frequencySlider.max = frequencyPresets.lastIndex
        cpeHeightSlider.max =
            (TowerUiState.MAX_CPE_ANTENNA_AGL_METERS - TowerUiState.MIN_CPE_ANTENNA_AGL_METERS).toInt()
        txPowerSlider.max = LinkEstimate.MAX_TX_POWER_DBM.toInt()
        apGainSlider.max = LinkEstimate.MAX_ANTENNA_GAIN_DBI.toInt()
        cpeGainSlider.max = LinkEstimate.MAX_ANTENNA_GAIN_DBI.toInt()
        listOf(frequencySlider, cpeHeightSlider, txPowerSlider, apGainSlider, cpeGainSlider).forEach { bar ->
            bar.progressDrawable = ContextCompat.getDrawable(this, R.drawable.bg_seekbar_progress)
            bar.thumb = ContextCompat.getDrawable(this, R.drawable.bg_seekbar_thumb)
        }

        val toggleLinkSettings = View.OnClickListener {
            linkSettingsOpen = !linkSettingsOpen
            applyLinkSettingsExpanded()
        }
        findViewById<View>(R.id.losLinkSettingsHeader).setOnClickListener(toggleLinkSettings)
        linkSettingsToggle.setOnClickListener(toggleLinkSettings)
        applyLinkSettingsExpanded()

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
        txPowerSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txPowerLabel.text = String.format(Locale.US, "TX POWER  %d dBm", progress)
                if (!fromUser) return
                viewModel.setTxPowerDbm(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        apGainSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                apGainLabel.text = String.format(Locale.US, "AP GAIN  %d dBi", progress)
                if (!fromUser) return
                viewModel.setApAntennaGainDbi(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        cpeGainSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                cpeGainLabel.text = String.format(Locale.US, "CPE GAIN  %d dBi", progress)
                if (!fromUser) return
                viewModel.setCpeAntennaGainDbi(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        findViewById<MaterialButton>(R.id.losRangeCancelButton).setOnClickListener {
            viewModel.clearLosRangeProfiles()
            startedScan = false
        }
        findViewById<MaterialButton>(R.id.losRangeRefreshButton).setOnClickListener {
            startedScan = false
            maybeStartScan(force = true)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    locationSourceChip.render(state, this@LosProfilesActivity)
                    subtitle.text = "Heat map button / tap row · long-press details"
                    status.text = state.losRangeStatus.orEmpty()
                    findViewById<MaterialButton>(R.id.losRangeCancelButton).isVisible = state.losRangeLoading
                    linkSettingsSummary.text = String.format(
                        Locale.US,
                        "%.1f GHz · CPE %.0f m · Tx %.0f · AP %.0f · CPE %.0f dBi",
                        state.frequencyGhz,
                        state.cpeAntennaAglMeters,
                        state.txPowerDbm,
                        state.apAntennaGainDbi,
                        state.cpeAntennaGainDbi
                    )

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
                    val txProgress = state.txPowerDbm.toInt()
                    if (txPowerSlider.progress != txProgress) txPowerSlider.progress = txProgress
                    txPowerLabel.text =
                        String.format(Locale.US, "TX POWER  %.0f dBm", state.txPowerDbm)
                    val apGainProgress = state.apAntennaGainDbi.toInt()
                    if (apGainSlider.progress != apGainProgress) apGainSlider.progress = apGainProgress
                    apGainLabel.text =
                        String.format(Locale.US, "AP GAIN  %.0f dBi", state.apAntennaGainDbi)
                    val cpeGainProgress = state.cpeAntennaGainDbi.toInt()
                    if (cpeGainSlider.progress != cpeGainProgress) {
                        cpeGainSlider.progress = cpeGainProgress
                    }
                    cpeGainLabel.text =
                        String.format(Locale.US, "CPE GAIN  %.0f dBi", state.cpeAntennaGainDbi)

                    if (lastCpeHeight != null && lastCpeHeight != state.cpeAntennaAglMeters) {
                        startedScan = false
                    }
                    lastCpeHeight = state.cpeAntennaAglMeters

                    val rowsKey = rowsRenderKey(state)
                    if (rowsKey != lastRowsKey) {
                        lastRowsKey = rowsKey
                        submitRows(state)
                    }
                    maybeStartScan()
                }
            }
        }

        ensureLocationPermission()
    }

    /** Avoid rebuilding rows on every GPS tick (which cancels taps). */
    private fun rowsRenderKey(state: TowerUiState): String {
        val rows = state.losRangeRows.joinToString(";") { row ->
            buildString {
                append(row.tower.id)
                append(':')
                append(row.loading)
                append(':')
                append(row.error != null)
                append(':')
                append(row.profile != null)
                append(':')
                append((row.distanceMeters / 10.0).toInt())
            }
        }
        return buildString {
            append(rows)
            append('|')
            append(state.frequencyGhz)
            append('|')
            append(state.txPowerDbm)
            append('|')
            append(state.apAntennaGainDbi)
            append('|')
            append(state.cpeAntennaGainDbi)
            append('|')
            append(state.cpeAntennaAglMeters)
            append('|')
            append(state.clutterHeightMeters)
            append('|')
            append(state.locationMode)
            append('|')
            append(state.hasInstallSite)
            append('|')
            append(state.losRangeStatus)
        }
    }

    private fun applyLinkSettingsExpanded() {
        linkSettingsExpanded.isVisible = linkSettingsOpen
        linkSettingsToggle.text = if (linkSettingsOpen) "Done" else "Edit"
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncFromFileStore()
    }

    override fun onStart() {
        super.onStart()
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates()
        }
    }

    override fun onStop() {
        viewModel.stopLocationUpdates()
        super.onStop()
    }

    override fun onDestroy() {
        clearShimmers()
        viewModel.clearLosRangeProfiles()
        super.onDestroy()
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates()
            maybeStartScan()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun maybeStartScan(force: Boolean = false) {
        val state = viewModel.uiState.value
        if (!force && state.losRangeLoading) return
        if (!force && startedScan && state.losRangeRows.isNotEmpty()) return
        if (state.positioningLocation() == null) {
            status.text = when (state.locationMode) {
                LocationMode.CUSTOM -> "Set a custom location on the Locate map"
                LocationMode.CURRENT_GPS -> "Waiting for GPS fix"
            }
            startedScan = false
            return
        }
        val canScan = state.towersInRangeForLos().isNotEmpty() ||
            (priorityTowerId != null && state.towerById(priorityTowerId!!) != null)
        if (!canScan) {
            status.text = when {
                state.towers.isEmpty() -> "Import sites to rank LOS"
                else -> state.losRangeStatus
                    ?: "No sites in range (${GeoUtils.formatDistance(state.maxDistanceMeters.toDouble())})"
            }
            startedScan = false
            return
        }
        if (!force && startedScan) return
        startedScan = true
        viewModel.refreshLosRangeProfiles(priorityTowerId)
    }

    private fun clearShimmers() {
        shimmerAnimators.forEach { it.cancel() }
        shimmerAnimators.clear()
    }

    private fun submitRows(state: TowerUiState) {
        clearShimmers()
        val items = state.losRangeRows.mapIndexed { index, row ->
            LosRangeRowAdapter.Item(row = row, rank = index + 1, state = state)
        }
        rowAdapter.submitList(items)
    }
}


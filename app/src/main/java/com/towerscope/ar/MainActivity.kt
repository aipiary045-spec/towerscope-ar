package com.towerscope.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.towerscope.ar.ui.EarthTrackingQuality
import com.towerscope.ar.ui.HudThemeApplier
import com.towerscope.ar.ui.TowerArSceneBinding
import com.towerscope.ar.ui.TowerDetailsBottomSheet
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private var arBinding: TowerArSceneBinding? = null

    private lateinit var permissionGate: View
    private lateinit var topBar: View
    private lateinit var compassStrip: View
    private lateinit var bottomPanel: View
    private lateinit var trackingWarning: TextView
    private lateinit var appTitle: TextView
    private lateinit var themeButton: Button
    private lateinit var gpsChip: TextView
    private lateinit var earthChip: TextView
    private lateinit var headingLabel: TextView
    private lateinit var focusTowerLabel: TextView
    private lateinit var messageBanner: TextView
    private lateinit var visibleCount: TextView
    private lateinit var searchField: EditText
    private lateinit var distanceLabel: TextView
    private lateinit var distanceSlider: SeekBar
    private lateinit var dataButton: Button
    private lateinit var showHiddenButton: Button
    private lateinit var nearestHeader: TextView
    private lateinit var towerChips: LinearLayout
    private lateinit var arContainer: FrameLayout

    private var suppressSearchCallback = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraOk = result[Manifest.permission.CAMERA] == true
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (cameraOk && (fineOk || coarseOk)) {
            onPermissionsGranted()
        } else {
            permissionGate.isVisible = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]
        bindViews()
        wireActions()
        observeState()
        ensurePermissions()
    }

    private fun bindViews() {
        permissionGate = findViewById(R.id.permissionGate)
        topBar = findViewById(R.id.topBar)
        compassStrip = findViewById(R.id.compassStrip)
        bottomPanel = findViewById(R.id.bottomPanel)
        trackingWarning = findViewById(R.id.trackingWarning)
        appTitle = findViewById(R.id.appTitle)
        themeButton = findViewById(R.id.themeButton)
        gpsChip = findViewById(R.id.gpsChip)
        earthChip = findViewById(R.id.earthChip)
        headingLabel = findViewById(R.id.headingLabel)
        focusTowerLabel = findViewById(R.id.focusTowerLabel)
        messageBanner = findViewById(R.id.messageBanner)
        visibleCount = findViewById(R.id.visibleCount)
        searchField = findViewById(R.id.searchField)
        distanceLabel = findViewById(R.id.distanceLabel)
        distanceSlider = findViewById(R.id.distanceSlider)
        dataButton = findViewById(R.id.dataButton)
        showHiddenButton = findViewById(R.id.showHiddenButton)
        nearestHeader = findViewById(R.id.nearestHeader)
        towerChips = findViewById(R.id.towerChips)
        arContainer = findViewById(R.id.arContainer)

        distanceSlider.max = (TowerUiState.MAX_DISTANCE_METERS - TowerUiState.MIN_DISTANCE_METERS).toInt()
        distanceSlider.progress =
            (TowerUiState.DEFAULT_MAX_DISTANCE_METERS - TowerUiState.MIN_DISTANCE_METERS).toInt()
    }

    private fun wireActions() {
        findViewById<Button>(R.id.grantPermissionsButton).setOnClickListener { requestPermissions() }
        themeButton.setOnClickListener { viewModel.cycleHudTheme() }
        dataButton.setOnClickListener {
            startActivity(Intent(this, DataMenuActivity::class.java))
        }
        showHiddenButton.setOnClickListener { viewModel.clearHiddenTowers() }
        distanceSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val meters = TowerUiState.MIN_DISTANCE_METERS + progress
                viewModel.setMaxDistanceMeters(meters)
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
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: TowerUiState) {
        val visible = state.visibleTowers()
        renderTrackingChips(state)
        renderCompass(state)
        renderTheme(state)

        visibleCount.text = buildString {
            append("Visible ${visible.size} / ${state.towers.size}")
            if (state.hiddenTowerIds.isNotEmpty()) {
                append("  ·  ${state.hiddenTowerIds.size} hidden")
            }
        }
        distanceLabel.text =
            "Max distance  ${GeoUtils.formatDistance(state.maxDistanceMeters.toDouble())}"
        showHiddenButton.isVisible = state.hiddenTowerIds.isNotEmpty()

        if (searchField.text.toString() != state.searchQuery) {
            suppressSearchCallback = true
            searchField.setText(state.searchQuery)
            searchField.setSelection(state.searchQuery.length)
            suppressSearchCallback = false
        }

        val message = state.errorMessage ?: state.statusMessage
        messageBanner.isVisible = message != null
        messageBanner.text = message.orEmpty()
        messageBanner.setTextColor(
            if (state.errorMessage != null) {
                ContextCompat.getColor(this, R.color.chip_poor)
            } else {
                HudThemeApplier.colorsFor(state.hudTheme, messageBanner).secondary
            }
        )

        towerChips.removeAllViews()
        val chipColors = HudThemeApplier.colorsFor(state.hudTheme, towerChips)
        state.nearestMatches(5).forEach { tower ->
            val distance = state.distanceTo(tower)
            val label = if (distance != null) {
                "${tower.name} · ${GeoUtils.formatDistance(distance)}"
            } else {
                tower.name
            }
            val chip = Button(this).apply {
                text = label
                setTextColor(chipColors.accent)
                setBackgroundColor(0x00000000)
                setOnClickListener { openTowerDetails(tower.id) }
            }
            towerChips.addView(chip)
        }

        arBinding?.update(
            uiState = state,
            onEarthTrackingQualityChanged = viewModel::setEarthTrackingQuality,
            onCameraHeadingChanged = viewModel::setCameraHeadingDegrees,
            onTowerTapped = { tower -> openTowerDetails(tower.id) }
        )
    }

    private fun renderTrackingChips(state: TowerUiState) {
        val accuracy = state.userLocation?.accuracyMeters
        val gpsTier = when {
            accuracy == null || !accuracy.isFinite() -> null
            accuracy <= 10f -> "Good"
            accuracy <= 30f -> "Fair"
            else -> "Poor"
        }
        gpsChip.text = if (gpsTier != null && accuracy != null) {
            "GPS $gpsTier ±${accuracy.toInt()}m"
        } else {
            "GPS…"
        }
        val gpsColor = when (gpsTier) {
            "Good" -> R.color.chip_good
            "Fair" -> R.color.chip_fair
            "Poor" -> R.color.chip_poor
            else -> R.color.chip_off
        }
        gpsChip.setBackgroundColor(ContextCompat.getColor(this, gpsColor))
        gpsChip.setTextColor(0xFF0B1C2C.toInt())

        when (state.earthTrackingQuality) {
            EarthTrackingQuality.TRACKING -> {
                earthChip.text = "EARTH OK"
                earthChip.setBackgroundColor(ContextCompat.getColor(this, R.color.chip_good))
            }
            EarthTrackingQuality.LIMITED -> {
                earthChip.text = "EARTH…"
                earthChip.setBackgroundColor(ContextCompat.getColor(this, R.color.chip_fair))
            }
            EarthTrackingQuality.NONE -> {
                earthChip.text = "EARTH OFF"
                earthChip.setBackgroundColor(ContextCompat.getColor(this, R.color.chip_off))
            }
        }
        earthChip.setTextColor(0xFF0B1C2C.toInt())

        val gpsWeak = gpsTier == null || gpsTier == "Poor" || gpsTier == "Fair"
        val earthWeak = state.earthTrackingQuality != EarthTrackingQuality.TRACKING
        trackingWarning.isVisible = gpsWeak && earthWeak && state.towers.isNotEmpty()
    }

    private fun renderCompass(state: TowerUiState) {
        val heading = state.effectiveHeadingDegrees()
        headingLabel.text = if (heading != null) {
            "Heading  ${GeoUtils.formatBearing(heading)}"
        } else {
            "Heading  —"
        }

        val focus = state.focusTower()
        focusTowerLabel.text = if (focus == null) {
            "No tower in range"
        } else {
            val distance = state.distanceTo(focus)
            val bearing = state.bearingTo(focus)
            val turn = if (heading != null && bearing != null) {
                GeoUtils.formatRelativeTurn(GeoUtils.relativeBearingDegrees(heading, bearing))
            } else {
                null
            }
            buildString {
                append(focus.name)
                if (distance != null) append("  ·  ").append(GeoUtils.formatDistance(distance))
                if (bearing != null) append("  ·  ").append(GeoUtils.formatBearing(bearing))
                if (turn != null) append("  ·  ").append(turn)
            }
        }
    }

    private fun renderTheme(state: TowerUiState) {
        HudThemeApplier.apply(
            theme = state.hudTheme,
            topBar = topBar,
            compassStrip = compassStrip,
            bottomPanel = bottomPanel,
            trackingWarning = trackingWarning,
            messageBanner = messageBanner,
            appTitle = appTitle,
            headingLabel = headingLabel,
            focusTowerLabel = focusTowerLabel,
            visibleCount = visibleCount,
            distanceLabel = distanceLabel,
            nearestHeader = nearestHeader,
            searchField = searchField,
            themeButton = themeButton,
            dataButton = dataButton
        )
    }

    private fun openTowerDetails(towerId: String) {
        viewModel.selectTower(towerId)
        val existing = supportFragmentManager.findFragmentByTag(TowerDetailsBottomSheet.TAG)
        if (existing is TowerDetailsBottomSheet) {
            existing.dismissAllowingStateLoss()
        }
        TowerDetailsBottomSheet.newInstance(towerId)
            .show(supportFragmentManager, TowerDetailsBottomSheet.TAG)
    }

    private fun ensurePermissions() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && (fineGranted || coarseGranted)) {
            onPermissionsGranted()
        } else {
            permissionGate.isVisible = true
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun onPermissionsGranted() {
        permissionGate.isVisible = false
        viewModel.startLocationUpdates()
        if (arBinding == null) {
            val binding = TowerArSceneBinding(this)
            arBinding = binding
            arContainer.removeAllViews()
            arContainer.addView(binding.view)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncFromFileStore()
    }

    override fun onDestroy() {
        arBinding?.destroy()
        arBinding = null
        super.onDestroy()
    }
}

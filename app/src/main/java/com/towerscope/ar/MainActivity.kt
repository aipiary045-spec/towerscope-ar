package com.towerscope.ar

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.ui.CompassRadarView
import com.towerscope.ar.ui.HudThemeApplier
import com.towerscope.ar.ui.SettingsBottomSheet
import com.towerscope.ar.ui.TowerDetailsBottomSheet
import com.towerscope.ar.util.CelestialBodies
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel

    private lateinit var permissionGate: View
    private lateinit var topChrome: View
    private lateinit var topBar: View
    private lateinit var compassStrip: View
    private lateinit var bottomPanel: View
    private lateinit var trackingWarning: TextView
    private lateinit var appTitle: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var calibrateButton: ImageButton
    private lateinit var gpsChip: TextView
    private lateinit var compassChip: TextView
    private lateinit var headingLabel: TextView
    private lateinit var focusTowerLabel: TextView
    private lateinit var visibleCount: TextView
    private lateinit var nearestHeader: TextView
    private lateinit var towerChips: LinearLayout
    private lateinit var compassRadar: CompassRadarView
    private lateinit var calibrationOverlay: View
    private lateinit var calibrationTitle: TextView
    private lateinit var calibrationHint: TextView
    private lateinit var calibrationConfirmButton: MaterialButton
    private lateinit var calibrationCancelButton: MaterialButton

    private var bottomPanelBasePadding = 0
    private var topChromeBasePadding = 0
    private var onboardingShownThisSession = false
    private var lastToastMessage: String? = null
    private var lastChipSignature: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOk || coarseOk) {
            onPermissionsGranted()
        } else {
            permissionGate.isVisible = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]
        bindViews()
        applySystemBarInsets()
        wireActions()
        observeState()
        ensurePermissions()
        if (intent.getBooleanExtra(EXTRA_START_CALIBRATION, false)) {
            intent.removeExtra(EXTRA_START_CALIBRATION)
            viewModel.beginHeadingCalibration()
        }
    }

    private fun bindViews() {
        permissionGate = findViewById(R.id.permissionGate)
        topChrome = findViewById(R.id.topChrome)
        topBar = findViewById(R.id.topBar)
        compassStrip = findViewById(R.id.compassStrip)
        bottomPanel = findViewById(R.id.bottomPanel)
        trackingWarning = findViewById(R.id.trackingWarning)
        appTitle = findViewById(R.id.appTitle)
        settingsButton = findViewById(R.id.settingsButton)
        calibrateButton = findViewById(R.id.calibrateButton)
        gpsChip = findViewById(R.id.gpsChip)
        compassChip = findViewById(R.id.compassChip)
        headingLabel = findViewById(R.id.headingLabel)
        focusTowerLabel = findViewById(R.id.focusTowerLabel)
        visibleCount = findViewById(R.id.visibleCount)
        nearestHeader = findViewById(R.id.nearestHeader)
        towerChips = findViewById(R.id.towerChips)
        compassRadar = findViewById(R.id.compassRadar)
        calibrationOverlay = findViewById(R.id.calibrationOverlay)
        calibrationTitle = findViewById(R.id.calibrationTitle)
        calibrationHint = findViewById(R.id.calibrationHint)
        calibrationConfirmButton = findViewById(R.id.calibrationConfirmButton)
        calibrationCancelButton = findViewById(R.id.calibrationCancelButton)
        compassRadar.setOnTowerSelectedListener { towerId -> openTowerDetails(towerId) }

        bottomPanelBasePadding = bottomPanel.paddingBottom
        topChromeBasePadding = topChrome.paddingTop
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(topChrome) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = topChromeBasePadding + bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bottomPanelBasePadding + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.root))
    }

    private fun wireActions() {
        findViewById<MaterialButton>(R.id.grantPermissionsButton).setOnClickListener { requestPermissions() }
        settingsButton.setOnClickListener { openSettings() }
        calibrateButton.setOnClickListener { viewModel.beginHeadingCalibration() }
        calibrateButton.setOnLongClickListener {
            if (viewModel.uiState.value.isHeadingCalibrated) {
                viewModel.clearHeadingCalibration()
                Toast.makeText(this, "Calibration cleared", Toast.LENGTH_SHORT).show()
                true
            } else {
                false
            }
        }
        calibrationConfirmButton.setOnClickListener { viewModel.confirmHeadingCalibration() }
        calibrationCancelButton.setOnClickListener { viewModel.cancelHeadingCalibration() }
        focusTowerLabel.setOnClickListener {
            viewModel.uiState.value.focusTower()?.let { openTowerDetails(it.id) }
        }
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
        renderCalibration(state)
        maybeToastStatus(state)
        renderRadar(state)

        visibleCount.text = buildString {
            append("Visible ${visible.size} / ${state.towers.size}")
            if (state.hiddenTowerIds.isNotEmpty()) {
                append("  ·  ${state.hiddenTowerIds.size} hidden")
            }
        }

        renderNearbyChips(state)
    }

    private fun renderRadar(state: TowerUiState) {
        val colors = HudThemeApplier.colorsFor(state.hudTheme, compassRadar)
        val focusLine = focusTowerLabel.text?.toString().orEmpty()
        val markers = state.directionIndicators().map { (tower, relative, distance) ->
            CompassRadarView.TowerMarker(
                towerId = tower.id,
                name = tower.name,
                relativeBearingDegrees = relative,
                distanceMeters = distance
            )
        }
        compassRadar.update(
            headingDegrees = state.effectiveHeadingDegrees(),
            maxDistanceMeters = state.maxDistanceMeters,
            markers = markers,
            focusTowerId = state.focusTower()?.id,
            focusLine = focusLine,
            accentColor = colors.accent,
            secondaryColor = colors.secondary,
            textColor = colors.text,
            mutedColor = colors.mutedText
        )
    }

    private fun maybeToastStatus(state: TowerUiState) {
        val message = state.errorMessage ?: state.statusMessage ?: return
        if (message == lastToastMessage) return
        lastToastMessage = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (state.errorMessage == null) {
            viewModel.clearStatusMessage()
        }
    }

    private fun renderNearbyChips(state: TowerUiState) {
        val matches = state.nearestMatches(5)
        val signature = matches.joinToString("|") { tower ->
            val distance = state.distanceTo(tower)?.toInt() ?: -1
            "${tower.id}:$distance:${state.hudTheme.name}"
        }
        if (signature == lastChipSignature && towerChips.childCount == matches.size) return
        lastChipSignature = signature

        towerChips.removeAllViews()
        val chipColors = HudThemeApplier.colorsFor(state.hudTheme, towerChips)
        val density = resources.displayMetrics.density
        matches.forEach { tower ->
            val distance = state.distanceTo(tower)
            val label = if (distance != null) {
                "${tower.name}  ${GeoUtils.formatDistance(distance)}"
            } else {
                tower.name
            }
            val chip = TextView(this).apply {
                text = label
                setTextColor(chipColors.text)
                textSize = 11f
                setPadding(
                    (10 * density).toInt(),
                    (5 * density).toInt(),
                    (10 * density).toInt(),
                    (5 * density).toInt()
                )
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_hud_match_chip)
                isClickable = true
                isFocusable = true
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = (6 * density).toInt()
                layoutParams = params
                setOnClickListener { openTowerDetails(tower.id) }
            }
            towerChips.addView(chip)
        }
    }

    private fun renderTrackingChips(state: TowerUiState) {
        val accuracy = state.userLocation?.accuracyMeters
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val gpsTier = when {
            !hasFine -> "Coarse"
            accuracy == null || !accuracy.isFinite() -> null
            accuracy <= 8f -> "Good"
            accuracy <= 20f -> "Fair"
            else -> "Poor"
        }
        gpsChip.text = if (gpsTier != null && accuracy != null && accuracy.isFinite()) {
            "GPS · $gpsTier ±${accuracy.toInt()}m"
        } else if (gpsTier == "Coarse") {
            "GPS · Coarse"
        } else {
            "GPS · —"
        }
        val gpsColorRes = when (gpsTier) {
            "Good" -> R.color.chip_good
            "Fair" -> R.color.chip_fair
            "Poor", "Coarse" -> R.color.chip_poor
            else -> R.color.chip_off
        }
        val gpsColor = ContextCompat.getColor(this, gpsColorRes)
        gpsChip.setTextColor(gpsColor)
        gpsChip.background = HudThemeApplier.statusChipBackground(gpsChip, gpsColor)

        val (compassLabel, compassColorRes) = when {
            state.deviceHeadingDegrees == null -> "Compass · —" to R.color.chip_off
            state.needsCompassCalibration -> "Compass · Calibrate" to R.color.chip_poor
            state.compassSensorAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_HIGH ->
                "Compass · Good" to R.color.chip_good
            state.compassSensorAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
                "Compass · Fair" to R.color.chip_fair
            else -> "Compass · Weak" to R.color.chip_poor
        }
        val calibratedTag = if (state.isHeadingCalibrated) " ✓" else ""
        compassChip.text = "$compassLabel$calibratedTag"
        val compassColor = ContextCompat.getColor(this, compassColorRes)
        compassChip.setTextColor(compassColor)
        compassChip.background = HudThemeApplier.statusChipBackground(compassChip, compassColor)

        val showWarning = state.towers.isNotEmpty() && (
            state.needsCompassCalibration ||
                state.userLocation == null ||
                (accuracy != null && accuracy > 25f)
            )
        trackingWarning.isVisible = showWarning
        if (trackingWarning.isVisible) {
            trackingWarning.text = when {
                state.needsCompassCalibration ->
                    "Compass needs calibration — tap the sun button"
                state.userLocation == null ->
                    "Waiting for GPS — go outdoors with a clear sky"
                else ->
                    "GPS accuracy weak (±${accuracy?.toInt()}m) — wait for a tighter fix"
            }
        }
    }

    private fun renderCompass(state: TowerUiState) {
        val heading = state.effectiveHeadingDegrees()
        val calTag = if (state.isHeadingCalibrated) "  ·  cal" else ""
        headingLabel.text = if (heading != null) {
            "HEADING  ${GeoUtils.formatBearing(heading)}$calTag"
        } else {
            "HEADING  —$calTag"
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
            appTitle = appTitle,
            headingLabel = headingLabel,
            focusTowerLabel = focusTowerLabel,
            visibleCount = visibleCount,
            nearestHeader = nearestHeader
        )
        val colors = HudThemeApplier.colorsFor(state.hudTheme, settingsButton)
        settingsButton.imageTintList = android.content.res.ColorStateList.valueOf(colors.secondary)
        calibrateButton.imageTintList = android.content.res.ColorStateList.valueOf(colors.accent)
    }

    private fun renderCalibration(state: TowerUiState) {
        calibrationOverlay.isVisible = state.calibrationActive
        compassRadar.isVisible = !state.calibrationActive
        topChrome.isVisible = !state.calibrationActive
        bottomPanel.isVisible = !state.calibrationActive
        settingsButton.isVisible = !state.calibrationActive
        calibrateButton.isVisible = !state.calibrationActive

        if (!state.calibrationActive) return

        val bodyLabel = when (state.calibrationBody) {
            CelestialBodies.Body.SUN -> "Sun"
            CelestialBodies.Body.MOON -> "Moon"
            null -> "sky body"
        }
        calibrationTitle.text = "Point the top of the phone at the $bodyLabel"
        val elev = state.calibrationTargetElevationDegrees
        val elevText = if (elev != null) {
            String.format(Locale.US, "Elev %.0f° · ", elev)
        } else {
            ""
        }
        calibrationHint.text = when (state.calibrationBody) {
            CelestialBodies.Body.SUN ->
                "${elevText}Do not stare at the Sun — glance to align, then Confirm"
            CelestialBodies.Body.MOON ->
                "${elevText}Aim at the Moon, hold steady, then Confirm"
            null -> "Aim, then Confirm"
        }
    }

    private fun openSettings() {
        val existing = supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG)
        if (existing is SettingsBottomSheet) {
            existing.dismissAllowingStateLoss()
        }
        SettingsBottomSheet.newInstance()
            .show(supportFragmentManager, SettingsBottomSheet.TAG)
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
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            onPermissionsGranted()
        } else {
            permissionGate.isVisible = true
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun onPermissionsGranted() {
        permissionGate.isVisible = false
        viewModel.startLocationUpdates()
        viewModel.startDeviceHeadingUpdates()
        maybeShowOnboarding()
    }

    private fun maybeShowOnboarding() {
        if (onboardingShownThisSession || viewModel.hasCompletedOnboarding()) return
        onboardingShownThisSession = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("TowerScope field tips")
            .setMessage(
                "1. From Home, upload your KML/KMZ (or use Settings → Tower data).\n" +
                    "2. Go outdoors for a clear GPS fix.\n" +
                    "3. Hold the phone upright — the top of the disc is the direction you face.\n" +
                    "4. Tap the sun button to calibrate with the sun or moon for best accuracy.\n" +
                    "5. Tap a tower on the radar for details, or use Elevation profiles for ranked LOS."
            )
            .setPositiveButton("Got it") { _, _ ->
                viewModel.markOnboardingComplete()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncFromFileStore()
    }

    companion object {
        const val EXTRA_START_CALIBRATION = "start_calibration"
    }
}

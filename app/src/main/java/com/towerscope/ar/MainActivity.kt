package com.towerscope.ar

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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
import com.towerscope.ar.ui.EarthTrackingQuality
import com.towerscope.ar.ui.HudThemeApplier
import com.towerscope.ar.ui.OffScreenTowerOverlay
import com.towerscope.ar.ui.TowerArSceneBinding
import com.towerscope.ar.ui.TowerDetailsBottomSheet
import com.towerscope.ar.util.CelestialBodies
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private var arBinding: TowerArSceneBinding? = null

    private lateinit var permissionGate: View
    private lateinit var topChrome: View
    private lateinit var topBar: View
    private lateinit var compassStrip: View
    private lateinit var bottomPanel: View
    private lateinit var trackingWarning: TextView
    private lateinit var appTitle: TextView
    private lateinit var themeButton: TextView
    private lateinit var calibrateButton: TextView
    private lateinit var gpsChip: TextView
    private lateinit var earthChip: TextView
    private lateinit var headingLabel: TextView
    private lateinit var focusTowerLabel: TextView
    private lateinit var messageBanner: TextView
    private lateinit var visibleCount: TextView
    private lateinit var bottomExtras: View
    private lateinit var hudExpandButton: TextView
    private lateinit var altitudeModeButton: TextView
    private lateinit var searchField: EditText
    private lateinit var distanceLabel: TextView
    private lateinit var distanceSlider: SeekBar
    private lateinit var dataButton: MaterialButton
    private lateinit var showHiddenButton: MaterialButton
    private lateinit var nearestHeader: TextView
    private lateinit var towerChips: LinearLayout
    private lateinit var arContainer: FrameLayout
    private lateinit var directionOverlay: OffScreenTowerOverlay
    private lateinit var calibrationOverlay: View
    private lateinit var calibrationTitle: TextView
    private lateinit var calibrationHint: TextView
    private lateinit var calibrationConfirmButton: MaterialButton
    private lateinit var calibrationCancelButton: MaterialButton

    private var bottomPanelBasePadding = 0
    private var topChromeBasePadding = 0

    private var suppressSearchCallback = false
    private var onboardingShownThisSession = false

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
    }

    private fun bindViews() {
        permissionGate = findViewById(R.id.permissionGate)
        topChrome = findViewById(R.id.topChrome)
        topBar = findViewById(R.id.topBar)
        compassStrip = findViewById(R.id.compassStrip)
        bottomPanel = findViewById(R.id.bottomPanel)
        trackingWarning = findViewById(R.id.trackingWarning)
        appTitle = findViewById(R.id.appTitle)
        themeButton = findViewById(R.id.themeButton)
        calibrateButton = findViewById(R.id.calibrateButton)
        gpsChip = findViewById(R.id.gpsChip)
        earthChip = findViewById(R.id.earthChip)
        headingLabel = findViewById(R.id.headingLabel)
        focusTowerLabel = findViewById(R.id.focusTowerLabel)
        messageBanner = findViewById(R.id.messageBanner)
        visibleCount = findViewById(R.id.visibleCount)
        bottomExtras = findViewById(R.id.bottomExtras)
        hudExpandButton = findViewById(R.id.hudExpandButton)
        altitudeModeButton = findViewById(R.id.altitudeModeButton)
        searchField = findViewById(R.id.searchField)
        distanceLabel = findViewById(R.id.distanceLabel)
        distanceSlider = findViewById(R.id.distanceSlider)
        dataButton = findViewById(R.id.dataButton)
        showHiddenButton = findViewById(R.id.showHiddenButton)
        nearestHeader = findViewById(R.id.nearestHeader)
        towerChips = findViewById(R.id.towerChips)
        arContainer = findViewById(R.id.arContainer)
        directionOverlay = findViewById(R.id.directionOverlay)
        calibrationOverlay = findViewById(R.id.calibrationOverlay)
        calibrationTitle = findViewById(R.id.calibrationTitle)
        calibrationHint = findViewById(R.id.calibrationHint)
        calibrationConfirmButton = findViewById(R.id.calibrationConfirmButton)
        calibrationCancelButton = findViewById(R.id.calibrationCancelButton)
        directionOverlay.setOnTowerSelectedListener { towerId -> openTowerDetails(towerId) }

        bottomPanelBasePadding = bottomPanel.paddingBottom
        topChromeBasePadding = topChrome.paddingTop

        distanceSlider.max = (TowerUiState.MAX_DISTANCE_METERS - TowerUiState.MIN_DISTANCE_METERS).toInt()
        distanceSlider.progress =
            (TowerUiState.DEFAULT_MAX_DISTANCE_METERS - TowerUiState.MIN_DISTANCE_METERS).toInt()
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
        themeButton.setOnClickListener { viewModel.cycleHudTheme() }
        calibrateButton.setOnClickListener { viewModel.beginHeadingCalibration() }
        calibrateButton.setOnLongClickListener {
            if (viewModel.uiState.value.isHeadingCalibrated) {
                viewModel.clearHeadingCalibration()
                true
            } else {
                false
            }
        }
        calibrationConfirmButton.setOnClickListener { viewModel.confirmHeadingCalibration() }
        calibrationCancelButton.setOnClickListener { viewModel.cancelHeadingCalibration() }
        hudExpandButton.setOnClickListener { viewModel.toggleHudExpanded() }
        altitudeModeButton.setOnClickListener { viewModel.toggleUseKmlAltitude() }
        dataButton.setOnClickListener {
            startActivity(Intent(this, DataMenuActivity::class.java))
        }
        showHiddenButton.setOnClickListener { viewModel.clearHiddenTowers() }
        focusTowerLabel.setOnClickListener {
            viewModel.uiState.value.focusTower()?.let { openTowerDetails(it.id) }
        }
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

    private var lastChipSignature: String? = null

    private fun render(state: TowerUiState) {
        val visible = state.visibleTowers()
        renderTrackingChips(state)
        renderCompass(state)
        renderTheme(state)
        renderCalibration(state)

        visibleCount.text = buildString {
            append("Visible ${visible.size} / ${state.towers.size}")
            if (state.hiddenTowerIds.isNotEmpty()) {
                append("  ·  ${state.hiddenTowerIds.size} hidden")
            }
        }
        distanceLabel.text =
            "RANGE  ${GeoUtils.formatDistance(state.maxDistanceMeters.toDouble())}"
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

        renderNearbyChips(state)

        arBinding?.update(
            uiState = state,
            onEarthTrackingQualityChanged = viewModel::setEarthTrackingQuality,
            onEarthCameraPoseChanged = viewModel::setEarthCameraPose,
            onCameraHeadingChanged = viewModel::setCameraHeadingDegrees,
            onTowerTapped = { tower -> openTowerDetails(tower.id) }
        )

        renderDirectionOverlay(state)
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

    private fun renderDirectionOverlay(state: TowerUiState) {
        val items = state.directionIndicators().map { (tower, relative, distance) ->
            OffScreenTowerOverlay.Indicator(
                towerId = tower.id,
                name = tower.name,
                relativeBearingDegrees = relative,
                distanceMeters = distance
            )
        }
        directionOverlay.setIndicators(
            items = items,
            focusTowerId = state.focusTower()?.id
        )
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

        val earthAcc = state.earthCameraPose?.horizontalAccuracyMeters
        val (earthLabel, earthColorRes) = when (state.earthTrackingQuality) {
            EarthTrackingQuality.TRACKING -> {
                val accText = if (earthAcc != null && earthAcc.isFinite()) {
                    " ±${earthAcc.toInt()}m"
                } else {
                    ""
                }
                "Earth · Ready$accText" to R.color.chip_good
            }
            EarthTrackingQuality.LIMITED -> {
                val accText = if (earthAcc != null && earthAcc.isFinite()) {
                    " ±${earthAcc.toInt()}m"
                } else {
                    ""
                }
                "Earth · Weak$accText" to R.color.chip_fair
            }
            EarthTrackingQuality.NONE -> "Earth · Off" to R.color.chip_off
        }
        earthChip.text = earthLabel
        val earthColor = ContextCompat.getColor(this, earthColorRes)
        earthChip.setTextColor(earthColor)
        earthChip.background = HudThemeApplier.statusChipBackground(earthChip, earthColor)

        val compassHint = state.needsCompassCalibration &&
            state.cameraHeadingDegrees == null &&
            state.towers.isNotEmpty()
        val earthWeak = state.earthTrackingQuality != EarthTrackingQuality.TRACKING
        trackingWarning.isVisible = (earthWeak || compassHint) && state.towers.isNotEmpty()
        if (trackingWarning.isVisible) {
            trackingWarning.text = when {
                compassHint ->
                    "Compass needs calibration — figure-8 the phone slowly"
                state.earthTrackingQuality == EarthTrackingQuality.LIMITED ->
                    "Walk slowly outdoors until Earth ≤12 m (towers pinned, still refining)"
                else ->
                    "Outdoors with clear sky — wait for Earth lock for precise towers"
            }
        }

        bottomExtras.isVisible = state.hudExpanded
        hudExpandButton.text = if (state.hudExpanded) "Less" else "More"
        altitudeModeButton.text = if (state.useKmlAltitude) "KML alt" else "Ground"
    }

    private fun renderCompass(state: TowerUiState) {
        val heading = state.effectiveHeadingDegrees()
        val calTag = when {
            state.cameraHeadingDegrees != null -> ""
            state.isHeadingCalibrated -> "  ·  cal"
            else -> ""
        }
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
            messageBanner = messageBanner,
            appTitle = appTitle,
            headingLabel = headingLabel,
            focusTowerLabel = focusTowerLabel,
            visibleCount = visibleCount,
            distanceLabel = distanceLabel,
            nearestHeader = nearestHeader,
            searchField = searchField,
            themeButton = themeButton,
            calibrateButton = calibrateButton,
            dataButton = dataButton,
            showHiddenButton = showHiddenButton
        )
    }

    private fun renderCalibration(state: TowerUiState) {
        calibrationOverlay.isVisible = state.calibrationActive
        directionOverlay.isVisible = !state.calibrationActive
        topChrome.isVisible = !state.calibrationActive
        bottomPanel.isVisible = !state.calibrationActive

        calibrateButton.text = if (state.isHeadingCalibrated) "Cal✓" else "Cal"

        if (!state.calibrationActive) return

        val bodyLabel = when (state.calibrationBody) {
            CelestialBodies.Body.SUN -> "Sun"
            CelestialBodies.Body.MOON -> "Moon"
            null -> "sky body"
        }
        calibrationTitle.text = "Center the $bodyLabel in the crosshair"
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
                "${elevText}Center the Moon, hold steady, then Confirm"
            null -> "Align, then Confirm"
        }
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
        viewModel.startDeviceHeadingUpdates()
        if (arBinding == null) {
            val binding = TowerArSceneBinding(this)
            arBinding = binding
            arContainer.removeAllViews()
            arContainer.addView(binding.view)
            binding.onResume()
        }
        maybeShowOnboarding()
    }

    private fun maybeShowOnboarding() {
        if (onboardingShownThisSession || viewModel.hasCompletedOnboarding()) return
        onboardingShownThisSession = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("TowerScope field tips")
            .setMessage(
                "1. Load your KML/KMZ from Tower data.\n" +
                    "2. Go outdoors with a clear view of the sky.\n" +
                    "3. Walk slowly until Earth shows Ready (≤12 m).\n" +
                    "4. Tap Cal and center the Sun (or Moon at night) to fix compass heading.\n" +
                    "5. Labels mark Earth/terrain pins; GPS labels are last-resort only.\n" +
                    "6. Keep Ground mode unless your KML altitudes are true WGS84 HAE.\n" +
                    "7. Tap NEARBY chips to hide or show only one tower.\n\n" +
                    "Precision needs Earth Ready — GPS-only markers can be far off."
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
        arBinding?.onResume()
    }

    override fun onPause() {
        arBinding?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        arBinding?.destroy()
        arBinding = null
        super.onDestroy()
    }
}

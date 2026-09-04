package com.towerscope.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
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
import com.towerscope.ar.ui.HudThemeApplier
import com.towerscope.ar.ui.LocationSourceChip
import com.towerscope.ar.ui.TowerDetailsBottomSheet
import com.towerscope.ar.util.CardinalSector
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.LocationMode
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.File
import kotlin.math.max
import kotlin.math.min
/**
 * Free satellite map (Esri World Imagery via osmdroid):
 * live GPS, optional install/customer pin, in-range APs, and path to focus site.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var mapView: MapView
    private lateinit var topChrome: View
    private lateinit var topBar: View
    private lateinit var focusStrip: View
    private lateinit var bottomPanel: View
    private lateinit var trackingWarning: TextView
    private lateinit var mapTitle: TextView
    private lateinit var mapSubtitle: TextView
    private lateinit var gpsChip: TextView
    private lateinit var sitesHeader: TextView
    private lateinit var focusLabel: TextView
    private lateinit var metaLabel: TextView
    private lateinit var towerChips: LinearLayout
    private lateinit var rangeToggle: MaterialButton
    private lateinit var detailsButton: MaterialButton
    private lateinit var locationSourceChip: LocationSourceChip
    private var topChromeBasePadding = 0
    private var bottomPanelBasePadding = 0

    private var losLine: Polyline? = null
    private val towerMarkers = mutableMapOf<String, Marker>()
    private var userMarker: Marker? = null
    private var installMarker: Marker? = null
    private val sectorPolygons = mutableListOf<Polygon>()
    private var lastChipSignature: String? = null
    private var hasFittedOnce = false
    private var lastMapFitRequestId = 0L
    private var lastSectorTowerId: String? = null
    private var lastActiveSector: CardinalSector? = null
    private var lastSectorRadiusMeters: Double = -1.0
    private var lastInfoWindowTowerId: String? = null
    private val markerIcons = mutableMapOf<Boolean, BitmapDrawable>()
    private val markerSelection = mutableMapOf<String, Boolean>()
    private var lastRenderSignature: String? = null
    private var lastSnappedCustomLat: Double? = null
    private var lastSnappedCustomLon: Double? = null
    private var pendingLaunchTowerId: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOk || coarseOk) {
            viewModel.startLocationUpdates(includeHeading = false)
            render(viewModel.uiState.value)
        } else {
            focusLabel.text = getString(R.string.map_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureOsmDroid()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_map)
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]
        pendingLaunchTowerId = TowerIntents.towerIdFrom(intent)?.also { viewModel.selectTower(it) }

        bindViews()
        applySystemBarInsets()
        setupMap()
        wireActions()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val signature = mapRenderSignature(state)
                    if (signature == lastRenderSignature) return@collect
                    lastRenderSignature = signature
                    render(state)
                }
            }
        }

        ensureLocationPermission()
    }

    private fun bindViews() {
        topChrome = findViewById(R.id.mapTopChrome)
        topBar = findViewById(R.id.mapTopBar)
        focusStrip = findViewById(R.id.mapFocusStrip)
        bottomPanel = findViewById(R.id.mapBottomPanel)
        trackingWarning = findViewById(R.id.mapTrackingWarning)
        mapTitle = findViewById(R.id.mapTitle)
        mapSubtitle = findViewById(R.id.mapSubtitle)
        gpsChip = findViewById(R.id.mapGpsChip)
        sitesHeader = findViewById(R.id.mapSitesHeader)
        mapView = findViewById(R.id.mapView)
        focusLabel = findViewById(R.id.mapFocusLabel)
        metaLabel = findViewById(R.id.mapMetaLabel)
        towerChips = findViewById(R.id.mapTowerChips)
        rangeToggle = findViewById(R.id.mapRangeToggle)
        detailsButton = findViewById(R.id.mapDetailsButton)
        locationSourceChip = LocationSourceChip(
            chip = findViewById(R.id.mapLocationChip),
            fragmentManager = supportFragmentManager,
            viewModel = viewModel,
            onCoordinatesApplied = { latitude, longitude ->
                snapToCustomLocation(latitude, longitude)
                metaLabel.text = getString(R.string.map_custom_set_coords)
            }
        )
        topChromeBasePadding = topChrome.paddingTop
        bottomPanelBasePadding = bottomPanel.paddingBottom
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
        ViewCompat.requestApplyInsets(findViewById(R.id.mapRoot))
    }

    private fun wireActions() {
        findViewById<ImageButton>(R.id.mapBackButton).setOnClickListener { finish() }
        rangeToggle.setOnClickListener {
            val showAll = !viewModel.uiState.value.mapShowAllSites
            viewModel.setMapShowAllSites(showAll)
        }
        detailsButton.setOnClickListener {
            viewModel.uiState.value.mapFocusTower()?.id?.let { openTowerDetails(it) }
        }
        findViewById<ImageButton>(R.id.mapFitButton).setOnClickListener {
            fitToYouAndFocus()
        }
        findViewById<ImageButton>(R.id.mapMyLocationButton).setOnClickListener {
            centerOnUser()
        }
        findViewById<ImageButton>(R.id.mapInstallButton).setOnClickListener {
            viewModel.setInstallSiteFromGps()
            metaLabel.text = getString(R.string.map_custom_set_gps)
        }
        findViewById<ImageButton>(R.id.mapInstallButton).setOnLongClickListener {
            viewModel.clearInstallSite()
            metaLabel.text = getString(R.string.map_custom_cleared)
            true
        }
    }

    private fun configureOsmDroid() {
        val cfg = Configuration.getInstance()
        val base = File(cacheDir, "osmdroid")
        val tiles = File(base, "tiles")
        if (!base.exists()) base.mkdirs()
        if (!tiles.exists()) tiles.mkdirs()
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = tiles
        cfg.userAgentValue = "WispEaze/1.0 (Android; field map)"
        cfg.tileDownloadThreads = 4
        cfg.tileFileSystemCacheMaxBytes = 128L * 1024L * 1024L
        cfg.load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    }

    private fun setupMap() {
        mapView.setTileSource(ESRI_WORLD_IMAGERY)
        mapView.setMultiTouchControls(true)
        mapView.minZoomLevel = 3.0
        mapView.maxZoomLevel = ESRI_MAX_ZOOM.toDouble()
        mapView.controller.setZoom(13.0)
        // Native 256px tiles; past Esri's native zoom the service returns "no data" placeholders.
        mapView.isTilesScaledToDpi = false
        mapView.setHorizontalMapRepetitionEnabled(false)
        mapView.setVerticalMapRepetitionEnabled(false)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)

        // Start near CONUS so tiles load even before GPS
        mapView.controller.setCenter(GeoPoint(36.7, -97.0))

        val scale = ScaleBarOverlay(mapView)
        scale.setAlignBottom(true)
        scale.setAlignRight(false)
        mapView.overlays.add(scale)

        val events = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                viewModel.setInstallSite(p.latitude, p.longitude)
                snapToCustomLocation(p.latitude, p.longitude)
                metaLabel.text = getString(R.string.map_custom_pinned)
                return true
            }
        }
        mapView.overlays.add(0, MapEventsOverlay(events))
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        viewModel.syncFromFileStore()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates(includeHeading = false)
        }
    }

    override fun onStop() {
        viewModel.stopLocationUpdates()
        super.onStop()
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates(includeHeading = false)
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun mapRenderSignature(state: TowerUiState): String {
        val user = state.userLocation
        val userKey = user?.let { "${coarseCoord(it.latitude)}:${coarseCoord(it.longitude)}" } ?: "none"
        val installKey = if (state.hasInstallSite) {
            "${coarseCoord(state.installLatitude!!)}:${coarseCoord(state.installLongitude!!)}"
        } else {
            "none"
        }
        val focusId = state.mapFocusTower()?.id ?: "none"
        return buildString {
            append(userKey)
            append('|').append(installKey)
            append('|').append(state.locationMode)
            append('|').append(state.mapShowAllSites)
            append('|').append(state.selectedTowerId)
            append('|').append(focusId)
            append('|').append(state.maxDistanceMeters)
            append('|').append(state.towers.size)
            append('|').append(state.hiddenTowerIds.hashCode())
        }
    }

    private fun coarseCoord(value: Double): Int = (value * 10_000).toInt()

    private fun applyPendingLaunchTower(state: TowerUiState) {
        val towerId = pendingLaunchTowerId ?: return
        val tower = state.towerById(towerId) ?: return
        val distance = state.distanceTo(tower)
        if (distance != null && distance > state.maxDistanceMeters) {
            viewModel.setMapShowAllSites(true)
        }
        pendingLaunchTowerId = null
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun render(state: TowerUiState) {
        applyPendingLaunchTower(state)
        lastRenderSignature = mapRenderSignature(state)
        val focus = state.mapFocusTower()
        val distance = focus?.let { state.distanceTo(it) }
        val bearing = focus?.let { state.bearingTo(it) }
        val mapSites = state.mapVisibleTowers()
        rangeToggle.text = if (state.mapShowAllSites) {
            getString(R.string.map_range_all)
        } else {
            getString(R.string.map_range_nearby)
        }
        focusLabel.text = when {
            focus == null && state.towers.isEmpty() -> getString(R.string.map_no_sites)
            focus == null && state.mapShowAllSites -> getString(R.string.map_no_sites_show)
            focus == null -> getString(R.string.map_no_ap_range)
            else -> focus.name
        }
        metaLabel.text = buildString {
            when (state.locationMode) {
                LocationMode.CURRENT_GPS -> append(getString(R.string.map_meta_from_gps))
                LocationMode.CUSTOM -> append(getString(R.string.map_meta_from_pin))
            }
            append("  ·  ")
            if (distance != null) append(GeoUtils.formatDistance(distance))
            if (bearing != null) {
                if (distance != null) append("  ·  ")
                append("Az ").append(GeoUtils.formatAzimuthPadded(bearing))
            }
            val sector = sectorTowardInstall(state, focus)
            if (sector != null && focus != null) {
                append("  ·  ")
                append("AP ").append(sector.shortLabel).append(" sector")
            }
            if (distance == null && bearing == null && sector == null) {
                if (state.mapShowAllSites) {
                    append("All ").append(mapSites.size).append(" sites")
                } else {
                    append("Range ").append(GeoUtils.formatDistance(state.maxDistanceMeters.toDouble()))
                }
            } else {
                append("  ·  ").append(mapSites.size)
                append(if (state.mapShowAllSites) " sites" else " in range")
            }
        }

        detailsButton.isEnabled = focus != null
        detailsButton.alpha = if (focus != null) 1f else 0.45f

        renderTrackingChips(state)
        renderTheme(state)
        renderChips(state)
        locationSourceChip.render(state, this)
        renderMapOverlays(state)
        snapToCustomLocationIfNeeded(state)

        if (state.mapFitSitesRequestId != 0L &&
            state.mapFitSitesRequestId != lastMapFitRequestId &&
            state.towers.isNotEmpty()
        ) {
            lastMapFitRequestId = state.mapFitSitesRequestId
            fitAllSites(animated = true)
        }

        if (!hasFittedOnce && state.userLocation != null && focus != null) {
            fitToYouAndFocus(animated = false)
        } else if (!hasFittedOnce && state.userLocation != null) {
            centerOnUser()
            hasFittedOnce = true
        }
    }

    private fun renderChips(state: TowerUiState) {
        val matches = state.mapNearestMatches(8)
        val focusId = state.mapFocusTower()?.id
        val signature = matches.joinToString("|") { tower ->
            val distanceBucket = state.distanceTo(tower)?.let { (it / 50.0).toInt() } ?: -1
            "${tower.id}:$distanceBucket:${state.hudTheme.name}:${tower.id == focusId}"
        } + "|f=$focusId"
        if (signature == lastChipSignature && towerChips.childCount == matches.size) return
        lastChipSignature = signature

        towerChips.removeAllViews()
        val chipColors = HudThemeApplier.colorsFor(state.hudTheme, towerChips)
        val density = resources.displayMetrics.density
        matches.forEach { tower ->
            val distance = state.distanceTo(tower)
            val isFocus = tower.id == focusId
            val label = if (distance != null) {
                "${tower.name}  ${GeoUtils.formatDistance(distance)}"
            } else {
                tower.name
            }
            val chip = TextView(this).apply {
                text = label
                setTextColor(if (isFocus) chipColors.accent else chipColors.text)
                textSize = 12f
                typeface = resources.getFont(
                    if (isFocus) R.font.source_sans3_semibold else R.font.source_sans3_regular
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = (36 * density).toInt()
                setPadding(
                    (12 * density).toInt(),
                    (7 * density).toInt(),
                    (12 * density).toInt(),
                    (7 * density).toInt()
                )
                background = ContextCompat.getDrawable(
                    this@MapActivity,
                    if (isFocus) R.drawable.bg_nav_item_selected else R.drawable.bg_hud_match_chip
                )
                isClickable = true
                isFocusable = true
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = (6 * density).toInt()
                layoutParams = params
                setOnClickListener {
                    viewModel.selectTower(tower.id)
                    fitToYouAndFocus()
                }
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

        val showWarning = state.towers.isNotEmpty() && (
            (state.locationMode == LocationMode.CUSTOM && state.hasInstallSite) ||
                state.userLocation == null ||
                (accuracy != null && accuracy > 25f)
            )
        trackingWarning.isVisible = showWarning
        if (trackingWarning.isVisible) {
            trackingWarning.text = when {
                state.locationMode == LocationMode.CUSTOM && state.hasInstallSite ->
                    getString(R.string.map_warning_custom_location)
                state.userLocation == null ->
                    getString(R.string.map_warning_waiting_gps)
                else ->
                    getString(R.string.map_warning_gps_weak, accuracy?.toInt() ?: 0)
            }
        }
    }

    private fun renderTheme(state: TowerUiState) {
        HudThemeApplier.apply(
            theme = state.hudTheme,
            topBar = topBar,
            compassStrip = focusStrip,
            bottomPanel = bottomPanel,
            trackingWarning = trackingWarning,
            appTitle = mapTitle,
            headingLabel = mapSubtitle,
            focusTowerLabel = focusLabel,
            visibleCount = metaLabel,
            nearestHeader = sitesHeader
        )
        focusStrip.setBackgroundResource(
            if (state.mapFocusTower() != null) {
                R.drawable.bg_compass_aim_active
            } else {
                R.drawable.bg_field_card
            }
        )
        val colors = HudThemeApplier.colorsFor(state.hudTheme, topBar)
        findViewById<ImageButton>(R.id.mapBackButton).imageTintList =
            android.content.res.ColorStateList.valueOf(colors.mutedText)
        findViewById<ImageButton>(R.id.mapFitButton).imageTintList =
            android.content.res.ColorStateList.valueOf(colors.accent)
        findViewById<ImageButton>(R.id.mapMyLocationButton).imageTintList =
            android.content.res.ColorStateList.valueOf(colors.secondary)
        findViewById<ImageButton>(R.id.mapInstallButton).imageTintList =
            android.content.res.ColorStateList.valueOf(colors.accent)
    }

    private fun renderMapOverlays(state: TowerUiState) {
        val visible = state.mapVisibleTowers()
        val focusId = state.mapFocusTower()?.id
        val visibleIds = visible.map { it.id }.toSet()

        val stale = towerMarkers.keys.filter { it !in visibleIds }
        stale.forEach { id ->
            towerMarkers.remove(id)?.let { mapView.overlays.remove(it) }
            markerSelection.remove(id)
        }

        visible.forEach { tower ->
            val point = GeoPoint(tower.latitude, tower.longitude)
            val selected = tower.id == focusId
            val existing = towerMarkers[tower.id]
            if (existing == null) {
                val marker = Marker(mapView).apply {
                    position = point
                    title = tower.name
                    snippet = state.distanceTo(tower)?.let(GeoUtils::formatDistance)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = markerIcon(selected)
                    relatedObject = tower.id
                    setOnMarkerClickListener { m, _ ->
                        val id = m.relatedObject as? String
                        if (id != null) {
                            viewModel.selectTower(id)
                            true
                        } else {
                            false
                        }
                    }
                }
                mapView.overlays.add(marker)
                towerMarkers[tower.id] = marker
                markerSelection[tower.id] = selected
            } else {
                existing.position = point
                existing.title = tower.name
                existing.snippet = state.distanceTo(tower)?.let(GeoUtils::formatDistance)
                val wasSelected = markerSelection[tower.id] == true
                if (wasSelected != selected) {
                    existing.icon = markerIcon(selected)
                    markerSelection[tower.id] = selected
                }
                if (selected && lastInfoWindowTowerId != tower.id) {
                    existing.showInfoWindow()
                    lastInfoWindowTowerId = tower.id
                } else if (!selected && lastInfoWindowTowerId == tower.id) {
                    existing.closeInfoWindow()
                    lastInfoWindowTowerId = null
                }
            }
        }

        val user = state.userLocation
        if (user != null) {
            val userPoint = GeoPoint(user.latitude, user.longitude)
            if (userMarker == null) {
                userMarker = Marker(mapView).apply {
                    position = userPoint
                    title = "You (GPS)"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = userIcon()
                }
                mapView.overlays.add(userMarker)
            } else {
                userMarker?.position = userPoint
            }
        }

        val installLat = state.installLatitude
        val installLon = state.installLongitude
        if (installLat != null && installLon != null) {
            val installPoint = GeoPoint(installLat, installLon)
            if (installMarker == null) {
                installMarker = Marker(mapView).apply {
                    position = installPoint
                    title = "Custom location"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = installIcon()
                }
                mapView.overlays.add(installMarker)
            } else {
                installMarker?.position = installPoint
            }
        } else if (installMarker != null) {
            mapView.overlays.remove(installMarker)
            installMarker = null
        }

        updateLosLine(state)
        updateSectorWedges(state)
        mapView.invalidate()
    }

    /**
     * Assumed N/E/S/W 90° pies on the focused AP.
     * Highlight the sector that faces the install site (or live GPS).
     */
    private fun updateSectorWedges(state: TowerUiState) {
        val focus = state.mapFocusTower()
        val origin = state.positioningLocation()
        if (focus == null) {
            clearSectorWedges()
            return
        }
        val active = if (origin != null) {
            val fromTower = GeoUtils.bearingDegrees(
                focus.latitude,
                focus.longitude,
                origin.latitude,
                origin.longitude
            )
            CardinalSector.facingSite(fromTower)
        } else {
            null
        }
        val distance = origin?.let {
            GeoUtils.haversineMeters(
                focus.latitude,
                focus.longitude,
                it.latitude,
                it.longitude
            )
        } ?: 0.0
        val radius = max(
            min(state.maxDistanceMeters.toDouble(), max(distance * 1.15, 250.0)),
            250.0
        ).coerceAtMost(3_000.0)

        if (
            focus.id == lastSectorTowerId &&
            active == lastActiveSector &&
            kotlin.math.abs(radius - lastSectorRadiusMeters) < 50.0 &&
            sectorPolygons.size == CardinalSector.ALL.size
        ) {
            return
        }
        lastSectorTowerId = focus.id
        lastActiveSector = active
        lastSectorRadiusMeters = radius

        clearSectorPolygonOverlays()
        val insertAt = mapView.overlays.indexOfFirst { it is ScaleBarOverlay }.coerceAtLeast(0) + 1
        CardinalSector.ALL.forEachIndexed { index, sector ->
            val highlighted = sector == active
            val poly = Polygon().apply {
                points = wedgePoints(
                    focus.latitude,
                    focus.longitude,
                    sector.startAzimuthDegrees,
                    sector.endAzimuthDegrees,
                    radius
                )
                fillPaint.color = if (highlighted) {
                    Color.argb(0x55, 0xF0, 0xD0, 0x60)
                } else {
                    Color.argb(0x28, 0x3E, 0xC9, 0xD6)
                }
                outlinePaint.color = if (highlighted) {
                    ContextCompat.getColor(this@MapActivity, R.color.accent_yellow)
                } else {
                    Color.argb(0x88, 0x3E, 0xC9, 0xD6)
                }
                outlinePaint.strokeWidth = if (highlighted) {
                    3f * resources.displayMetrics.density
                } else {
                    1.5f * resources.displayMetrics.density
                }
                outlinePaint.isAntiAlias = true
                title = "${sector.fullLabel} sector (assumed 90°)"
            }
            mapView.overlays.add(insertAt + index, poly)
            sectorPolygons.add(poly)
        }
    }

    private fun clearSectorWedges() {
        clearSectorPolygonOverlays()
        lastSectorTowerId = null
        lastActiveSector = null
        lastSectorRadiusMeters = -1.0
    }

    private fun clearSectorPolygonOverlays() {
        sectorPolygons.forEach { mapView.overlays.remove(it) }
        sectorPolygons.clear()
    }

    private fun wedgePoints(
        lat: Double,
        lon: Double,
        startAzimuth: Double,
        endAzimuth: Double,
        radiusMeters: Double,
        arcSteps: Int = 24
    ): ArrayList<GeoPoint> {
        val points = ArrayList<GeoPoint>(arcSteps + 3)
        points.add(GeoPoint(lat, lon))
        var span = endAzimuth - startAzimuth
        if (span <= 0.0) span += 360.0
        for (i in 0..arcSteps) {
            val t = i.toDouble() / arcSteps.toDouble()
            val az = GeoUtils.normalizeBearing(startAzimuth + span * t)
            val p = GeoUtils.destinationPoint(lat, lon, az, radiusMeters)
            points.add(GeoPoint(p.latitude, p.longitude))
        }
        points.add(GeoPoint(lat, lon))
        return points
    }

    /** Sector of [tower] that faces the install/GPS origin. */
    private fun sectorTowardInstall(state: TowerUiState, tower: com.towerscope.ar.data.Tower?): CardinalSector? {
        val origin = state.positioningLocation() ?: return null
        if (tower == null) return null
        val bearing = GeoUtils.bearingDegrees(
            tower.latitude,
            tower.longitude,
            origin.latitude,
            origin.longitude
        )
        return CardinalSector.facingSite(bearing)
    }

    private fun updateLosLine(state: TowerUiState) {
        val origin = state.positioningLocation()
        val focus = state.mapFocusTower()
        if (origin == null || focus == null) {
            losLine?.let { mapView.overlays.remove(it) }
            losLine = null
            return
        }
        val points = arrayListOf(
            GeoPoint(origin.latitude, origin.longitude),
            GeoPoint(focus.latitude, focus.longitude)
        )
        val existing = losLine
        if (existing == null) {
            val line = Polyline().apply {
                setPoints(points)
                outlinePaint.color = ContextCompat.getColor(this@MapActivity, R.color.accent_yellow)
                outlinePaint.strokeWidth = 10f * resources.displayMetrics.density / 2.5f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.isAntiAlias = true
            }
            // Insert under markers so pins stay on top
            val insertAt = mapView.overlays.indexOfFirst { it is ScaleBarOverlay }.coerceAtLeast(0) + 1
            mapView.overlays.add(insertAt, line)
            losLine = line
        } else {
            existing.setPoints(points)
        }
    }

    private fun fitAllSites(animated: Boolean = true) {
        val state = viewModel.uiState.value
        val points = state.towers.map { GeoPoint(it.latitude, it.longitude) }
        if (points.isEmpty()) return
        if (points.size == 1) {
            mapView.controller.animateTo(points.first())
            mapView.controller.setZoom(13.0)
        } else {
            val box = BoundingBox.fromGeoPoints(points)
            mapView.post {
                mapView.zoomToBoundingBox(
                    box.increaseByScale(1.25f),
                    animated,
                    (64 * resources.displayMetrics.density).toInt()
                )
            }
        }
        hasFittedOnce = true
    }

    private fun fitToYouAndFocus(animated: Boolean = true) {
        val state = viewModel.uiState.value
        val points = mutableListOf<GeoPoint>()
        state.positioningLocation()?.let { points.add(GeoPoint(it.latitude, it.longitude)) }
        state.userLocation?.let { points.add(GeoPoint(it.latitude, it.longitude)) }
        state.mapFocusTower()?.let { points.add(GeoPoint(it.latitude, it.longitude)) }
        if (points.isEmpty()) {
            state.mapVisibleTowers().forEach {
                points.add(GeoPoint(it.latitude, it.longitude))
            }
        }
        if (points.isEmpty()) {
            hasFittedOnce = true
            return
        }
        if (points.size == 1) {
            mapView.controller.animateTo(points.first())
            mapView.controller.setZoom(15.0)
        } else {
            val box = BoundingBox.fromGeoPoints(points)
            mapView.post {
                mapView.zoomToBoundingBox(
                    box.increaseByScale(1.35f),
                    animated,
                    (64 * resources.displayMetrics.density).toInt()
                )
            }
        }
        hasFittedOnce = true
    }

    private fun snapToCustomLocationIfNeeded(state: TowerUiState) {
        if (state.locationMode != LocationMode.CUSTOM) {
            lastSnappedCustomLat = null
            lastSnappedCustomLon = null
            return
        }
        val lat = state.installLatitude ?: return
        val lon = state.installLongitude ?: return
        if (lat == lastSnappedCustomLat && lon == lastSnappedCustomLon) return
        snapToCustomLocation(lat, lon)
    }

    private fun snapToCustomLocation(latitude: Double, longitude: Double) {
        lastSnappedCustomLat = latitude
        lastSnappedCustomLon = longitude
        mapView.post {
            val point = GeoPoint(latitude, longitude)
            mapView.controller.setCenter(point)
            if (mapView.zoomLevelDouble < 14.0) {
                mapView.controller.setZoom(15.5)
            }
            hasFittedOnce = true
            mapView.invalidate()
        }
    }

    private fun centerOnUser() {
        val user = viewModel.uiState.value.userLocation ?: return
        centerOnCoordinates(user.latitude, user.longitude)
    }

    private fun centerOnCoordinates(latitude: Double, longitude: Double) {
        snapToCustomLocation(latitude, longitude)
    }

    private fun markerIcon(selected: Boolean): BitmapDrawable {
        return markerIcons.getOrPut(selected) {
            val color = ContextCompat.getColor(
                this,
                if (selected) R.color.accent_yellow else R.color.accent_teal
            )
            val drawable = ContextCompat.getDrawable(this, R.drawable.ic_tower_lattice)!!.mutate()
            drawable.setTint(color)
            val bmp = drawable.toBitmap(
                (22 * resources.displayMetrics.density).toInt(),
                (28 * resources.displayMetrics.density).toInt()
            )
            BitmapDrawable(resources, bmp)
        }
    }

    private fun userIcon(): BitmapDrawable {
        val size = (18 * resources.displayMetrics.density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@MapActivity, R.color.status_clear)
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
        }
        val r = size / 2f
        canvas.drawCircle(r, r, r - stroke.strokeWidth, fill)
        canvas.drawCircle(r, r, r - stroke.strokeWidth, stroke)
        return BitmapDrawable(resources, bmp)
    }

    private fun installIcon(): BitmapDrawable {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_install_pin)!!.mutate()
        val bmp = drawable.toBitmap(
            (22 * resources.displayMetrics.density).toInt(),
            (28 * resources.displayMetrics.density).toInt()
        )
        return BitmapDrawable(resources, bmp)
    }

    private fun openTowerDetails(towerId: String) {
        viewModel.selectTower(towerId)
        if (supportFragmentManager.findFragmentByTag(TowerDetailsBottomSheet.TAG) == null) {
            TowerDetailsBottomSheet.newInstance(towerId)
                .show(supportFragmentManager, TowerDetailsBottomSheet.TAG)
        }
    }

    companion object {
        // Esri returns "Map data not yet available" placeholder tiles above ~z18 in most areas.
        private const val ESRI_MAX_ZOOM = 18

        private fun arcGisImagery(
            name: String,
            base: String,
            copyright: String,
            maxZoom: Int
        ): OnlineTileSourceBase = object : OnlineTileSourceBase(
            name,
            1,
            maxZoom,
            256,
            "",
            arrayOf(base),
            copyright
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return "$baseUrl$z/$y/$x"
            }
        }

        private val ESRI_WORLD_IMAGERY = arcGisImagery(
            name = "EsriWorldImagery",
            base = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/",
            copyright = "© Esri, Maxar, Earthstar Geographics",
            maxZoom = ESRI_MAX_ZOOM
        )
    }
}

package com.towerscope.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.ui.LocationSourceChip
import com.towerscope.ar.ui.SystemBars
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
 * Free satellite map (Esri / USGS imagery via osmdroid):
 * live GPS, optional install/customer pin, in-range APs, and path to focus site.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var mapView: MapView
    private lateinit var focusLabel: TextView
    private lateinit var metaLabel: TextView
    private lateinit var towerChips: LinearLayout
    private lateinit var rangeToggle: MaterialButton
    private lateinit var locationSourceChip: LocationSourceChip

    private var losLine: Polyline? = null
    private val towerMarkers = mutableMapOf<String, Marker>()
    private var userMarker: Marker? = null
    private var installMarker: Marker? = null
    private val sectorPolygons = mutableListOf<Polygon>()
    private var lastChipSignature: String? = null
    private var hasFittedOnce = false
    private var lastSectorTowerId: String? = null
    private var lastActiveSector: CardinalSector? = null
    private var lastSectorRadiusMeters: Double = -1.0
    private var lastInfoWindowTowerId: String? = null
    private val markerIcons = mutableMapOf<Boolean, BitmapDrawable>()
    private val markerSelection = mutableMapOf<String, Boolean>()
    private var lastRenderSignature: String? = null
    private var lastSnappedCustomLat: Double? = null
    private var lastSnappedCustomLon: Double? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOk || coarseOk) {
            viewModel.startLocationUpdates(includeHeading = false)
            render(viewModel.uiState.value)
        } else {
            focusLabel.text = "Location permission required"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureOsmDroid()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_map)
        SystemBars.apply(
            root = findViewById(R.id.mapRoot),
            alsoBottom = findViewById(R.id.mapBottomPanel)
        )
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        mapView = findViewById(R.id.mapView)
        focusLabel = findViewById(R.id.mapFocusLabel)
        metaLabel = findViewById(R.id.mapMetaLabel)
        towerChips = findViewById(R.id.mapTowerChips)
        rangeToggle = findViewById(R.id.mapRangeToggle)
        locationSourceChip = LocationSourceChip(
            chip = findViewById(R.id.mapLocationChip),
            fragmentManager = supportFragmentManager,
            viewModel = viewModel,
            onCoordinatesApplied = { latitude, longitude ->
                snapToCustomLocation(latitude, longitude)
                metaLabel.text = "Custom location set from coordinates"
            }
        )

        setupMap()

        rangeToggle.setOnClickListener {
            val showAll = !viewModel.uiState.value.mapShowAllSites
            viewModel.setMapShowAllSites(showAll)
        }
        findViewById<MaterialButton>(R.id.mapHomeButton).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.mapDetailsButton).setOnClickListener {
            viewModel.uiState.value.mapFocusTower()?.id?.let { openTowerDetails(it) }
        }
        findViewById<android.widget.ImageButton>(R.id.mapFitButton).setOnClickListener {
            fitToYouAndFocus()
        }
        findViewById<android.widget.ImageButton>(R.id.mapMyLocationButton).setOnClickListener {
            centerOnUser()
        }
        findViewById<android.widget.ImageButton>(R.id.mapInstallButton).setOnClickListener {
            viewModel.setInstallSiteFromGps()
            metaLabel.text = "Custom location set to your GPS · long-press map to move"
        }
        findViewById<android.widget.ImageButton>(R.id.mapInstallButton).setOnLongClickListener {
            viewModel.clearInstallSite()
            metaLabel.text = "Custom location cleared"
            true
        }
        findViewById<android.widget.ImageButton>(R.id.mapBasemapButton).setOnClickListener {
            cycleTileSource()
        }

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
        applyBasemap(BASEMAPS.first())
        mapView.setMultiTouchControls(true)
        mapView.minZoomLevel = 3.0
        mapView.controller.setZoom(13.0)
        // Native 256px tiles; DPI upscaling past source max zoom looks blocky on USGS NAIP.
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
                metaLabel.text = "Custom location pinned"
                return true
            }
        }
        mapView.overlays.add(0, MapEventsOverlay(events))
    }

    private var basemapIndex = 0

    private fun cycleTileSource() {
        basemapIndex = (basemapIndex + 1) % BASEMAPS.size
        applyBasemap(BASEMAPS[basemapIndex])
    }

    private fun applyBasemap(option: BasemapOption) {
        mapView.setTileSource(option.source)
        mapView.maxZoomLevel = option.maxZoom
        val zoom = mapView.zoomLevelDouble
        if (zoom > option.maxZoom) {
            mapView.controller.setZoom(option.maxZoom)
        }
        mapView.invalidate()
        metaLabel.text = "Basemap · ${option.label} · ${option.note}"
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

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun render(state: TowerUiState) {
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
            focus == null && state.towers.isEmpty() -> "No sites loaded — import in Installation Hub"
            focus == null && state.mapShowAllSites -> "No sites to show"
            focus == null -> "No AP in range"
            else -> focus.name
        }
        metaLabel.text = buildString {
            when (state.locationMode) {
                LocationMode.CURRENT_GPS -> append("From your GPS  ·  ")
                LocationMode.CUSTOM -> append("From custom pin  ·  ")
            }
            if (distance != null) append(GeoUtils.formatDistance(distance))
            if (bearing != null) {
                if (isNotEmpty() && !endsWith("  ·  ")) append("  ·  ")
                append("Az ").append(GeoUtils.formatAzimuthPadded(bearing))
            }
            val sector = sectorTowardInstall(state, focus)
            if (sector != null && focus != null) {
                if (isNotEmpty()) append("  ·  ")
                append("AP ").append(sector.shortLabel).append(" sector")
            }
            if (isEmpty()) {
                if (state.mapShowAllSites) append("All ${mapSites.size} sites")
                else append("Range ${GeoUtils.formatDistance(state.maxDistanceMeters.toDouble())}")
            } else {
                append("  ·  ").append(mapSites.size)
                append(if (state.mapShowAllSites) " sites" else " in range")
            }
        }

        renderChips(state)
        locationSourceChip.render(state, this)
        renderMapOverlays(state)
        snapToCustomLocationIfNeeded(state)

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
            "${tower.id}:$distanceBucket"
        } + "|f=$focusId"
        if (signature == lastChipSignature && towerChips.childCount == matches.size) return
        lastChipSignature = signature

        towerChips.removeAllViews()
        val density = resources.displayMetrics.density
        matches.forEach { tower ->
            val distance = state.distanceTo(tower)
            val selected = tower.id == focusId
            val label = if (distance != null) {
                "${tower.name}  ${GeoUtils.formatDistance(distance)}"
            } else {
                tower.name
            }
            val chip = TextView(this).apply {
                text = label
                setTextColor(
                    ContextCompat.getColor(
                        this@MapActivity,
                        if (selected) R.color.bg_deep else R.color.text_primary
                    )
                )
                textSize = 12f
                setPadding(
                    (12 * density).toInt(),
                    (8 * density).toInt(),
                    (12 * density).toInt(),
                    (8 * density).toInt()
                )
                setBackgroundColor(
                    ContextCompat.getColor(
                        this@MapActivity,
                        if (selected) R.color.accent_yellow else R.color.surface_elevated
                    )
                )
                minHeight = (48 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = (8 * density).toInt()
                layoutParams = params
                setOnClickListener {
                    viewModel.selectTower(tower.id)
                    fitToYouAndFocus()
                }
            }
            towerChips.addView(chip)
        }
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

    private data class BasemapOption(
        val source: OnlineTileSourceBase,
        val label: String,
        val maxZoom: Double,
        val note: String
    )

    companion object {
        // USGS NAIP is ~1 m; native detail ends around z16 — higher zooms are upscaled tiles.
        private const val USGS_MAX_ZOOM = 16
        private const val ESRI_MAX_ZOOM = 20

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

        private val USGS_IMAGERY = arcGisImagery(
            name = "UsgsImagery",
            base = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/",
            copyright = "© USGS National Map",
            maxZoom = USGS_MAX_ZOOM
        )

        private val BASEMAPS = listOf(
            BasemapOption(
                source = ESRI_WORLD_IMAGERY,
                label = "Esri",
                maxZoom = ESRI_MAX_ZOOM.toDouble(),
                note = "up to ~0.5 m in US"
            ),
            BasemapOption(
                source = USGS_IMAGERY,
                label = "USGS",
                maxZoom = USGS_MAX_ZOOM.toDouble(),
                note = "1 m NAIP · max zoom capped"
            )
        )
    }
}

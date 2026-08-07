package com.towerscope.ar.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.towerscope.ar.data.KmlParser
import com.towerscope.ar.data.LosProfile
import com.towerscope.ar.data.LosProfileDiskCache
import com.towerscope.ar.data.LosProfileService
import com.towerscope.ar.data.Tower
import com.towerscope.ar.data.TowerFileStore
import com.towerscope.ar.location.DeviceHeadingClient
import com.towerscope.ar.location.HighAccuracyLocationClient
import com.towerscope.ar.location.UserLocation
import com.towerscope.ar.ui.HudTheme
import com.towerscope.ar.util.CelestialBodies
import com.towerscope.ar.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One tower row on the separate elevation-profiles page. */
data class LosRangeRow(
    val tower: Tower,
    val distanceMeters: Double,
    val profile: LosProfile? = null,
    val error: String? = null,
    val loading: Boolean = false
) {
    fun clearanceMeters(clutterHeightMeters: Double): Double? =
        profile?.minClearanceMeters(clutterHeightMeters)
}

data class TowerUiState(
    val towers: List<Tower> = emptyList(),
    val hiddenTowerIds: Set<String> = emptySet(),
    val maxDistanceMeters: Float = DEFAULT_MAX_DISTANCE_METERS,
    val searchQuery: String = "",
    val selectedTowerId: String? = null,
    val userLocation: UserLocation? = null,
    val deviceHeadingDegrees: Double? = null,
    val compassSensorAccuracy: Int = android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
    val hudTheme: HudTheme = HudTheme.NIGHT,
    /** Bottom HUD search/range/controls expanded. */
    val hudExpanded: Boolean = true,
    /**
     * Degrees added to device compass heading after sun/moon calibration.
     * Null = not calibrated.
     */
    val headingCalibrationOffsetDegrees: Double? = null,
    val calibrationActive: Boolean = false,
    val calibrationBody: CelestialBodies.Body? = null,
    val calibrationTargetAzimuthDegrees: Double? = null,
    val calibrationTargetElevationDegrees: Double? = null,
    /** LOS elevation profile for the selected tower (null until loaded). */
    val losProfile: LosProfile? = null,
    val losProfileLoading: Boolean = false,
    val losProfileError: String? = null,
    /** Extra vegetation height added on DEM samples only (meters). LiDAR already includes canopy. */
    val clutterHeightMeters: Float = DEFAULT_CLUTTER_METERS,
    /** When false, tower details hide LOS chart and skip elevation queries. */
    val showElevationProfile: Boolean = true,
    /** Batch LOS list for the elevation-profiles page (best clearance first when ranked). */
    val losRangeRows: List<LosRangeRow> = emptyList(),
    val losRangeLoading: Boolean = false,
    val losRangeStatus: String? = null,
    val sourceName: String? = null,
    /** Epoch ms when tower data was last imported / saved. 0 = unknown. */
    val towersUpdatedAtMs: Long = 0L,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isLoadingFile: Boolean = false
) {
    val needsCompassCalibration: Boolean
        get() = compassSensorAccuracy <= android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW

    val isHeadingCalibrated: Boolean
        get() = headingCalibrationOffsetDegrees != null

    /** Fused GPS location used for bearings, distances, and LOS. */
    fun positioningLocation(): UserLocation? = userLocation

    /**
     * Facing heading from the device compass, plus optional sun/moon calibration offset.
     * Never uses GPS course — that is travel direction, not facing.
     */
    fun effectiveHeadingDegrees(): Double? {
        val device = deviceHeadingDegrees ?: return null
        val offset = headingCalibrationOffsetDegrees ?: 0.0
        return CelestialBodies.normalizeDegrees(device + offset)
    }

    /** Towers with known distance/bearing for the radar disc (relative to heading). */
    fun directionIndicators(): List<Triple<Tower, Double, Double>> {
        val heading = effectiveHeadingDegrees() ?: return emptyList()
        return visibleTowers().mapNotNull { tower ->
            val distance = distanceTo(tower) ?: return@mapNotNull null
            val bearing = bearingTo(tower) ?: return@mapNotNull null
            val relative = GeoUtils.relativeBearingDegrees(heading, bearing)
            Triple(tower, relative, distance)
        }.sortedBy { it.third }
    }

    fun visibleTowers(): List<Tower> {
        val location = positioningLocation()
        val query = searchQuery.trim()
        return towers.filter { tower ->
            if (tower.id in hiddenTowerIds) return@filter false
            if (query.isNotEmpty() && !tower.name.contains(query, ignoreCase = true)) {
                return@filter false
            }
            if (location == null) return@filter true
            val distance = GeoUtils.haversineMeters(
                location.latitude,
                location.longitude,
                tower.latitude,
                tower.longitude
            )
            distance <= maxDistanceMeters
        }
    }

    /**
     * Towers inside the saved range for the elevation-profiles page.
     * Ignores compass search text; still respects hidden towers.
     */
    fun towersInRangeForLos(): List<Pair<Tower, Double>> {
        val location = positioningLocation() ?: return emptyList()
        return towers
            .asSequence()
            .filter { it.id !in hiddenTowerIds }
            .map { tower ->
                tower to GeoUtils.haversineMeters(
                    location.latitude,
                    location.longitude,
                    tower.latitude,
                    tower.longitude
                )
            }
            .filter { it.second <= maxDistanceMeters }
            .sortedBy { it.second }
            .take(MAX_LOS_RANGE_TOWERS)
            .toList()
    }

    fun distanceTo(tower: Tower): Double? {
        val location = positioningLocation() ?: return null
        return GeoUtils.haversineMeters(
            location.latitude,
            location.longitude,
            tower.latitude,
            tower.longitude
        )
    }

    fun bearingTo(tower: Tower): Double? {
        val location = positioningLocation() ?: return null
        return GeoUtils.bearingDegrees(
            location.latitude,
            location.longitude,
            tower.latitude,
            tower.longitude
        )
    }

    fun nearestVisibleTower(): Tower? {
        val location = positioningLocation() ?: return visibleTowers().firstOrNull()
        return visibleTowers().minByOrNull { tower ->
            GeoUtils.haversineMeters(
                location.latitude,
                location.longitude,
                tower.latitude,
                tower.longitude
            )
        }
    }

    fun focusTower(): Tower? {
        val selected = selectedTowerId?.let { id -> towers.firstOrNull { it.id == id } }
        if (selected != null && selected.id !in hiddenTowerIds) {
            val query = searchQuery.trim()
            if (query.isEmpty() || selected.name.contains(query, ignoreCase = true)) {
                return selected
            }
        }
        return nearestVisibleTower()
    }

    fun towerById(id: String): Tower? = towers.firstOrNull { it.id == id }

    fun nearestMatches(limit: Int = 5): List<Tower> {
        val location = positioningLocation()
        val visible = visibleTowers()
        if (location == null) return visible.take(limit)
        return visible
            .sortedBy {
                GeoUtils.haversineMeters(
                    location.latitude,
                    location.longitude,
                    it.latitude,
                    it.longitude
                )
            }
            .take(limit)
    }

    companion object {
        const val MIN_DISTANCE_METERS = 100f
        /** 10 miles in meters. */
        const val MAX_DISTANCE_METERS = (10.0 * GeoUtils.METERS_PER_MILE).toFloat()
        const val DEFAULT_MAX_DISTANCE_METERS = 2_000f
        const val DEFAULT_CLUTTER_METERS = 0f
        const val MAX_CLUTTER_METERS = 40f
        const val MAX_LOS_RANGE_TOWERS = 40
    }
}

class TowerScopeViewModel(application: Application) : AndroidViewModel(application) {

    private val locationClient = HighAccuracyLocationClient(application)
    private val headingClient = DeviceHeadingClient(application)
    private val fileStore = TowerFileStore(application)
    private val losProfileService = LosProfileService(
        diskCache = LosProfileDiskCache(application)
    )
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        TowerUiState(
            maxDistanceMeters = loadMaxDistanceMeters(),
            hudTheme = loadHudTheme(),
            hudExpanded = prefs.getBoolean(KEY_HUD_EXPANDED, true),
            headingCalibrationOffsetDegrees = loadHeadingOffset(),
            clutterHeightMeters = prefs.getFloat(KEY_CLUTTER_HEIGHT, TowerUiState.DEFAULT_CLUTTER_METERS),
            showElevationProfile = prefs.getBoolean(KEY_SHOW_ELEVATION_PROFILE, true)
        )
    )
    val uiState: StateFlow<TowerUiState> = _uiState.asStateFlow()

    private var locationJob: Job? = null
    private var headingJob: Job? = null
    private var losJob: Job? = null
    private var losRangeJob: Job? = null
    private var losLoadingTowerId: String? = null

    init {
        restorePersistedTowers()
    }

    private fun loadHudTheme(): HudTheme {
        val raw = prefs.getString(KEY_HUD_THEME, HudTheme.NIGHT.name) ?: HudTheme.NIGHT.name
        return runCatching { HudTheme.valueOf(raw) }.getOrDefault(HudTheme.NIGHT)
    }

    private fun loadMaxDistanceMeters(): Float {
        if (!prefs.contains(KEY_MAX_DISTANCE)) return TowerUiState.DEFAULT_MAX_DISTANCE_METERS
        return prefs.getFloat(KEY_MAX_DISTANCE, TowerUiState.DEFAULT_MAX_DISTANCE_METERS)
            .coerceIn(TowerUiState.MIN_DISTANCE_METERS, TowerUiState.MAX_DISTANCE_METERS)
    }

    private fun loadHeadingOffset(): Double? {
        if (!prefs.contains(KEY_HEADING_OFFSET)) return null
        val value = prefs.getFloat(KEY_HEADING_OFFSET, 0f).toDouble()
        return if (value.isFinite()) value else null
    }

    private fun restorePersistedTowers() {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { fileStore.loadPersistedTowers() } ?: return@launch
            _uiState.update {
                it.copy(
                    towers = restored.second,
                    sourceName = restored.first,
                    towersUpdatedAtMs = fileStore.lastUpdatedEpochMs(),
                    statusMessage = "Restored ${restored.second.size} towers from ${restored.first}"
                )
            }
        }
    }

    fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        if (!locationClient.hasLocationPermission()) {
            _uiState.update {
                it.copy(errorMessage = "Location permission is required for high-accuracy positioning.")
            }
            return
        }
        if (!locationClient.hasFineLocationPermission()) {
            _uiState.update {
                it.copy(statusMessage = "Fine location improves tower positioning — enable Precise location.")
            }
        }
        locationJob = viewModelScope.launch {
            locationClient.locationUpdates().collect { location ->
                _uiState.update { it.copy(userLocation = location) }
            }
        }
        startDeviceHeadingUpdates()
    }

    fun startDeviceHeadingUpdates() {
        if (headingJob?.isActive == true) return
        headingJob = viewModelScope.launch {
            headingClient.headingUpdates { _uiState.value.userLocation }.collect { heading ->
                _uiState.update {
                    it.copy(
                        deviceHeadingDegrees = heading.degrees,
                        compassSensorAccuracy = heading.sensorAccuracy
                    )
                }
            }
        }
    }

    fun stopLocationUpdates() {
        locationJob?.cancel()
        locationJob = null
        headingJob?.cancel()
        headingJob = null
    }

    fun setMaxDistanceMeters(meters: Float) {
        val clamped = meters.coerceIn(
            TowerUiState.MIN_DISTANCE_METERS,
            TowerUiState.MAX_DISTANCE_METERS
        )
        prefs.edit().putFloat(KEY_MAX_DISTANCE, clamped).apply()
        _uiState.update { it.copy(maxDistanceMeters = clamped) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectTower(towerId: String?) {
        _uiState.update { it.copy(selectedTowerId = towerId) }
        if (towerId != null) {
            loadLosProfile(towerId)
        } else {
            clearLosProfile()
        }
    }

    fun setClutterHeightMeters(meters: Float) {
        val clamped = meters.coerceIn(0f, TowerUiState.MAX_CLUTTER_METERS)
        prefs.edit().putFloat(KEY_CLUTTER_HEIGHT, clamped).apply()
        _uiState.update { state ->
            state.copy(
                clutterHeightMeters = clamped,
                losRangeRows = rankLosRangeRows(state.losRangeRows, clamped.toDouble())
            )
        }
    }

    fun setShowElevationProfile(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ELEVATION_PROFILE, enabled).apply()
        _uiState.update { it.copy(showElevationProfile = enabled) }
        if (!enabled) {
            clearLosProfile()
        } else {
            _uiState.value.selectedTowerId?.let { loadLosProfile(it) }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun clearLosProfile() {
        losJob?.cancel()
        losJob = null
        losLoadingTowerId = null
        _uiState.update {
            it.copy(
                losProfile = null,
                losProfileLoading = false,
                losProfileError = null
            )
        }
    }

    /**
     * Sample geodesic points to [towerId], query LiDAR/DEM elevations (cached), apply Earth curvature.
     */
    fun loadLosProfile(towerId: String) {
        if (!_uiState.value.showElevationProfile) {
            clearLosProfile()
            return
        }
        val tower = _uiState.value.towerById(towerId) ?: return
        val location = _uiState.value.positioningLocation()
        if (location == null) {
            _uiState.update {
                it.copy(
                    losProfile = null,
                    losProfileLoading = false,
                    losProfileError = "Need GPS to build line-of-sight profile"
                )
            }
            return
        }
        // Skip reload if we already have this tower's profile or its fetch is in flight.
        val existing = _uiState.value.losProfile
        if (existing != null && existing.towerId == towerId) return
        if (losLoadingTowerId == towerId && losJob?.isActive == true) return

        losJob?.cancel()
        losLoadingTowerId = towerId
        _uiState.update {
            it.copy(
                losProfileLoading = true,
                losProfileError = null,
                losProfile = if (existing?.towerId == towerId) existing else null
            )
        }
        losJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    losProfileService.buildProfile(
                        tower = tower,
                        observerLat = location.latitude,
                        observerLon = location.longitude
                    )
                }
            }
            if (losLoadingTowerId != towerId) return@launch
            _uiState.update { state ->
                result.fold(
                    onSuccess = { profile ->
                        state.copy(
                            losProfile = profile,
                            losProfileLoading = false,
                            losProfileError = null
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            losProfile = null,
                            losProfileLoading = false,
                            losProfileError = error.message ?: "Elevation query failed"
                        )
                    }
                )
            }
            losLoadingTowerId = null
        }
    }

    /**
     * Build LOS profiles for towers in the saved range (separate from AR).
     * Results stream in and stay sorted best-clearance → worst.
     */
    fun refreshLosRangeProfiles() {
        losRangeJob?.cancel()
        val location = _uiState.value.positioningLocation()
        if (location == null) {
            _uiState.update {
                it.copy(
                    losRangeRows = emptyList(),
                    losRangeLoading = false,
                    losRangeStatus = "Waiting for GPS…"
                )
            }
            return
        }
        val targets = _uiState.value.towersInRangeForLos()
        if (targets.isEmpty()) {
            _uiState.update {
                it.copy(
                    losRangeRows = emptyList(),
                    losRangeLoading = false,
                    losRangeStatus = "No towers in range (${GeoUtils.formatDistance(it.maxDistanceMeters.toDouble())})"
                )
            }
            return
        }

        val seed = targets.map { (tower, distance) ->
            LosRangeRow(tower = tower, distanceMeters = distance, loading = true)
        }
        _uiState.update {
            it.copy(
                losRangeRows = seed,
                losRangeLoading = true,
                losRangeStatus = "Profiling ${seed.size} towers…"
            )
        }

        losRangeJob = viewModelScope.launch {
            val gate = Semaphore(LOS_RANGE_CONCURRENCY)
            val clutter = _uiState.value.clutterHeightMeters.toDouble()
            coroutineScope {
                targets.map { (tower, distance) ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val result = runCatching {
                                losProfileService.buildProfile(
                                    tower = tower,
                                    observerLat = location.latitude,
                                    observerLon = location.longitude
                                )
                            }
                            withContext(Dispatchers.Main.immediate) {
                                _uiState.update { state ->
                                    val updated = state.losRangeRows.map { row ->
                                        if (row.tower.id != tower.id) row
                                        else result.fold(
                                            onSuccess = { profile ->
                                                LosRangeRow(
                                                    tower = tower,
                                                    distanceMeters = distance,
                                                    profile = profile,
                                                    loading = false
                                                )
                                            },
                                            onFailure = { error ->
                                                LosRangeRow(
                                                    tower = tower,
                                                    distanceMeters = distance,
                                                    error = error.message ?: "Failed",
                                                    loading = false
                                                )
                                            }
                                        )
                                    }
                                    val ranked = rankLosRangeRows(updated, clutter)
                                    val done = ranked.count { !it.loading }
                                    state.copy(
                                        losRangeRows = ranked,
                                        losRangeStatus = "Loaded $done / ${ranked.size}"
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            _uiState.update { state ->
                val ranked = rankLosRangeRows(state.losRangeRows, state.clutterHeightMeters.toDouble())
                state.copy(
                    losRangeRows = ranked,
                    losRangeLoading = false,
                    losRangeStatus = "Done · ${ranked.size} towers · best LOS first"
                )
            }
        }
    }

    fun clearLosRangeProfiles() {
        losRangeJob?.cancel()
        losRangeJob = null
        _uiState.update {
            it.copy(
                losRangeRows = emptyList(),
                losRangeLoading = false,
                losRangeStatus = null
            )
        }
    }

    fun cycleHudTheme() {
        _uiState.update { state ->
            val next = state.hudTheme.next()
            prefs.edit().putString(KEY_HUD_THEME, next.name).apply()
            state.copy(hudTheme = next)
        }
    }

    fun toggleHudExpanded() {
        _uiState.update { state ->
            val next = !state.hudExpanded
            prefs.edit().putBoolean(KEY_HUD_EXPANDED, next).apply()
            state.copy(hudExpanded = next)
        }
    }

    fun hasCompletedOnboarding(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun markOnboardingComplete() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    /** Enter sun/moon aiming mode when a body is high enough above the horizon. */
    fun beginHeadingCalibration() {
        val location = _uiState.value.positioningLocation() ?: _uiState.value.userLocation
        if (location == null) {
            _uiState.update {
                it.copy(statusMessage = "Need GPS before sun/moon calibration")
            }
            return
        }
        val target = CelestialBodies.preferredCalibrationTarget(
            latitude = location.latitude,
            longitude = location.longitude
        )
        if (target == null) {
            _uiState.update {
                it.copy(
                    statusMessage =
                        "Sun/moon too low — try again when one is clearly visible"
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                calibrationActive = true,
                calibrationBody = target.body,
                calibrationTargetAzimuthDegrees = target.azimuthDegrees,
                calibrationTargetElevationDegrees = target.elevationDegrees,
                statusMessage = null,
                errorMessage = null
            )
        }
    }

    fun cancelHeadingCalibration() {
        _uiState.update {
            it.copy(
                calibrationActive = false,
                calibrationBody = null,
                calibrationTargetAzimuthDegrees = null,
                calibrationTargetElevationDegrees = null
            )
        }
    }

    /**
     * User pointed the top of the phone at the sun/moon. Offset = celestial azimuth − device heading.
     */
    fun confirmHeadingCalibration() {
        val state = _uiState.value
        val targetAzimuth = state.calibrationTargetAzimuthDegrees
        val deviceHeading = state.deviceHeadingDegrees
        if (targetAzimuth == null || deviceHeading == null) {
            _uiState.update {
                it.copy(statusMessage = "Hold still — waiting for compass reading")
            }
            return
        }
        // Refresh celestial solution at confirm time for a tighter fix.
        val location = state.positioningLocation() ?: state.userLocation
        val body = state.calibrationBody
        val refreshedAzimuth = if (location != null && body != null) {
            when (body) {
                CelestialBodies.Body.SUN ->
                    CelestialBodies.sunPosition(location.latitude, location.longitude).azimuthDegrees
                CelestialBodies.Body.MOON ->
                    CelestialBodies.moonPosition(location.latitude, location.longitude).azimuthDegrees
            }
        } else {
            targetAzimuth
        }
        val offset = CelestialBodies.signedDeltaDegrees(deviceHeading, refreshedAzimuth)
        prefs.edit().putFloat(KEY_HEADING_OFFSET, offset.toFloat()).apply()
        val bodyLabel = when (body) {
            CelestialBodies.Body.SUN -> "Sun"
            CelestialBodies.Body.MOON -> "Moon"
            null -> "Sky"
        }
        _uiState.update {
            it.copy(
                headingCalibrationOffsetDegrees = offset,
                calibrationActive = false,
                calibrationBody = null,
                calibrationTargetAzimuthDegrees = null,
                calibrationTargetElevationDegrees = null,
                statusMessage = String.format(
                    java.util.Locale.US,
                    "%s calibration saved (%+.0f°)",
                    bodyLabel,
                    offset
                )
            )
        }
    }

    fun clearHeadingCalibration() {
        prefs.edit().remove(KEY_HEADING_OFFSET).apply()
        _uiState.update {
            it.copy(
                headingCalibrationOffsetDegrees = null,
                calibrationActive = false,
                calibrationBody = null,
                calibrationTargetAzimuthDegrees = null,
                calibrationTargetElevationDegrees = null,
                statusMessage = "Heading calibration cleared"
            )
        }
    }

    fun hideTower(towerId: String) {
        _uiState.update {
            it.copy(
                hiddenTowerIds = it.hiddenTowerIds + towerId,
                selectedTowerId = if (it.selectedTowerId == towerId) null else it.selectedTowerId,
                statusMessage = "Tower filtered out"
            )
        }
    }

    /** Hide every tower except [towerId] so it is the only one on screen. */
    fun showOnlyTower(towerId: String) {
        _uiState.update { state ->
            val others = state.towers.map { it.id }.filter { it != towerId }.toSet()
            state.copy(
                hiddenTowerIds = others,
                selectedTowerId = towerId,
                statusMessage = "Showing only selected tower"
            )
        }
    }

    fun clearHiddenTowers() {
        _uiState.update {
            it.copy(
                hiddenTowerIds = emptySet(),
                statusMessage = "All hidden towers restored"
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun loadTowersFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFile = true, errorMessage = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Unable to open file")
                    val displayName = resolver.query(uri, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
                    } ?: uri.lastPathSegment ?: "import.kml"
                    val towers = KmlParser.parseUri(getApplication(), uri)
                    if (towers.isNotEmpty()) {
                        fileStore.saveImport(displayName, uri, towers, bytes)
                    }
                    displayName to towers
                }
                val (name, towers) = result
                if (towers.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            errorMessage = "No placemarks with Point coordinates found",
                            isLoadingFile = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            towers = towers,
                            hiddenTowerIds = emptySet(),
                            selectedTowerId = null,
                            sourceName = name,
                            towersUpdatedAtMs = fileStore.lastUpdatedEpochMs(),
                            statusMessage = "Loaded ${towers.size} towers from $name (saved)",
                            isLoadingFile = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message ?: "Failed to parse KML/KMZ",
                        isLoadingFile = false
                    )
                }
            }
        }
    }

    fun clearSavedTowers() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fileStore.clear() }
            _uiState.update {
                it.copy(
                    towers = emptyList(),
                    hiddenTowerIds = emptySet(),
                    selectedTowerId = null,
                    sourceName = null,
                    towersUpdatedAtMs = 0L,
                    statusMessage = "Cleared saved tower data"
                )
            }
        }
    }

    /** Called when returning from DataMenuActivity so imports are reflected. */
    fun syncFromFileStore() {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { fileStore.loadPersistedTowers() }
            if (restored == null) {
                if (!fileStore.hasPersistedImport()) {
                    _uiState.update {
                        if (it.towers.isEmpty() && it.sourceName == null) it
                        else it.copy(
                            towers = emptyList(),
                            hiddenTowerIds = emptySet(),
                            selectedTowerId = null,
                            sourceName = null,
                            towersUpdatedAtMs = 0L
                        )
                    }
                }
                return@launch
            }
            val (name, towers) = restored
            _uiState.update { state ->
                if (state.sourceName == name && state.towers == towers) state
                else state.copy(
                    towers = towers,
                    sourceName = name,
                    towersUpdatedAtMs = fileStore.lastUpdatedEpochMs(),
                    hiddenTowerIds = emptySet(),
                    selectedTowerId = null,
                    statusMessage = "Loaded ${towers.size} towers from $name"
                )
            }
        }
    }

    override fun onCleared() {
        stopLocationUpdates()
        losJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val PREFS = "towerscope_prefs"
        private const val KEY_HUD_THEME = "hud_theme"
        private const val KEY_HUD_EXPANDED = "hud_expanded"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_HEADING_OFFSET = "heading_calibration_offset_deg"
        private const val KEY_CLUTTER_HEIGHT = "clutter_height_meters"
        private const val KEY_SHOW_ELEVATION_PROFILE = "show_elevation_profile"
        private const val KEY_MAX_DISTANCE = "max_distance_meters"
        private const val MAX_LOS_RANGE_TOWERS = 40
        private const val LOS_RANGE_CONCURRENCY = 2

        /** Best clearance first; clear above blocked; failures last. */
        fun rankLosRangeRows(rows: List<LosRangeRow>, clutterHeightMeters: Double): List<LosRangeRow> {
            return rows.sortedWith { a, b ->
                val ca = a.clearanceMeters(clutterHeightMeters)
                val cb = b.clearanceMeters(clutterHeightMeters)
                when {
                    ca == null && cb == null -> a.distanceMeters.compareTo(b.distanceMeters)
                    ca == null -> 1
                    cb == null -> -1
                    ca > 0 && cb <= 0 -> -1
                    ca <= 0 && cb > 0 -> 1
                    else -> {
                        val byClearance = cb.compareTo(ca) // higher clearance first
                        if (byClearance != 0) byClearance
                        else a.distanceMeters.compareTo(b.distanceMeters)
                    }
                }
            }
        }
    }
}

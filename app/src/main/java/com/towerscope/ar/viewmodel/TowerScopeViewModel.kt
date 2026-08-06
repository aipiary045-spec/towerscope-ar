package com.towerscope.ar.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.towerscope.ar.ar.GeospatialAccuracy
import com.towerscope.ar.data.KmlParser
import com.towerscope.ar.data.Tower
import com.towerscope.ar.data.TowerFileStore
import com.towerscope.ar.location.DeviceHeadingClient
import com.towerscope.ar.location.HighAccuracyLocationClient
import com.towerscope.ar.location.UserLocation
import com.towerscope.ar.ui.EarthTrackingQuality
import com.towerscope.ar.ui.HudTheme
import com.towerscope.ar.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Latest ARCore Geospatial camera pose (may be tracking but not yet accurate enough for markers). */
data class EarthCameraPose(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val headingDegrees: Double,
    val horizontalAccuracyMeters: Double,
    val headingAccuracyDegrees: Double
) {
    val isAccurateForMarkers: Boolean
        get() = horizontalAccuracyMeters.isFinite() &&
            horizontalAccuracyMeters <= GeospatialAccuracy.MARKER_HORIZONTAL_METERS
}

data class TowerUiState(
    val towers: List<Tower> = emptyList(),
    val hiddenTowerIds: Set<String> = emptySet(),
    val maxDistanceMeters: Float = DEFAULT_MAX_DISTANCE_METERS,
    val searchQuery: String = "",
    val selectedTowerId: String? = null,
    val userLocation: UserLocation? = null,
    val earthCameraPose: EarthCameraPose? = null,
    val cameraHeadingDegrees: Double? = null,
    val deviceHeadingDegrees: Double? = null,
    val earthTracking: Boolean = false,
    val earthTrackingQuality: EarthTrackingQuality = EarthTrackingQuality.NONE,
    val hudTheme: HudTheme = HudTheme.NIGHT,
    val sourceName: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isLoadingFile: Boolean = false
) {
    /**
     * Prefer accurate Earth camera lat/lng for distance/bearing so overlays match AR anchors.
     * Falls back to fused GPS when Earth is weak or off.
     */
    fun positioningLocation(): UserLocation? {
        val earth = earthCameraPose
        if (earth != null && earth.isAccurateForMarkers) {
            return UserLocation(
                latitude = earth.latitude,
                longitude = earth.longitude,
                altitudeMeters = earth.altitudeMeters,
                accuracyMeters = earth.horizontalAccuracyMeters.toFloat(),
                bearingDegrees = earth.headingDegrees.toFloat()
            )
        }
        return userLocation
    }

    fun effectiveHeadingDegrees(): Double? =
        cameraHeadingDegrees
            ?: deviceHeadingDegrees
            ?: userLocation?.bearingDegrees?.toDouble()

    /** Direction cues for the AR overlay (in-range towers with known distance/bearing). */
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
    }
}

class TowerScopeViewModel(application: Application) : AndroidViewModel(application) {

    private val locationClient = HighAccuracyLocationClient(application)
    private val headingClient = DeviceHeadingClient(application)
    private val fileStore = TowerFileStore(application)
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        TowerUiState(hudTheme = loadHudTheme())
    )
    val uiState: StateFlow<TowerUiState> = _uiState.asStateFlow()

    private var locationJob: Job? = null
    private var headingJob: Job? = null

    init {
        restorePersistedTowers()
    }

    private fun loadHudTheme(): HudTheme {
        val raw = prefs.getString(KEY_HUD_THEME, HudTheme.NIGHT.name) ?: HudTheme.NIGHT.name
        return runCatching { HudTheme.valueOf(raw) }.getOrDefault(HudTheme.NIGHT)
    }

    private fun restorePersistedTowers() {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { fileStore.loadPersistedTowers() } ?: return@launch
            _uiState.update {
                it.copy(
                    towers = restored.second,
                    sourceName = restored.first,
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
                _uiState.update { it.copy(deviceHeadingDegrees = heading) }
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
        _uiState.update {
            it.copy(
                maxDistanceMeters = meters.coerceIn(
                    TowerUiState.MIN_DISTANCE_METERS,
                    TowerUiState.MAX_DISTANCE_METERS
                )
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectTower(towerId: String?) {
        _uiState.update { it.copy(selectedTowerId = towerId) }
    }

    fun cycleHudTheme() {
        _uiState.update { state ->
            val next = state.hudTheme.next()
            prefs.edit().putString(KEY_HUD_THEME, next.name).apply()
            state.copy(hudTheme = next)
        }
    }

    fun setEarthTracking(tracking: Boolean) {
        _uiState.update { it.copy(earthTracking = tracking) }
    }

    fun setEarthTrackingQuality(quality: EarthTrackingQuality) {
        _uiState.update {
            if (it.earthTrackingQuality == quality &&
                it.earthTracking == (quality == EarthTrackingQuality.TRACKING)
            ) {
                it
            } else {
                it.copy(
                    earthTrackingQuality = quality,
                    earthTracking = quality == EarthTrackingQuality.TRACKING
                )
            }
        }
    }

    fun setEarthCameraPose(pose: EarthCameraPose?) {
        _uiState.update { current ->
            if (earthPoseEquivalent(current.earthCameraPose, pose)) current
            else current.copy(earthCameraPose = pose)
        }
    }

    fun setCameraHeadingDegrees(heading: Double?) {
        _uiState.update { current ->
            val existing = current.cameraHeadingDegrees
            if (heading == null && existing == null) return@update current
            if (heading != null && existing != null && kotlin.math.abs(heading - existing) < 1.0) {
                return@update current
            }
            current.copy(cameraHeadingDegrees = heading)
        }
    }

    fun hideTower(towerId: String) {
        _uiState.update {
            it.copy(
                hiddenTowerIds = it.hiddenTowerIds + towerId,
                selectedTowerId = if (it.selectedTowerId == towerId) null else it.selectedTowerId,
                statusMessage = "Tower filtered out of the scene"
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

    fun loadSampleTowers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFile = true, errorMessage = null) }
            try {
                val towers = withContext(Dispatchers.IO) {
                    val parsed = KmlParser.parseAsset(getApplication(), "sample_towers.kml")
                    val bytes = getApplication<Application>().assets.open("sample_towers.kml").use { it.readBytes() }
                    fileStore.saveImport("sample_towers.kml", null, parsed, bytes)
                    parsed
                }
                _uiState.update {
                    it.copy(
                        towers = towers,
                        hiddenTowerIds = emptySet(),
                        selectedTowerId = null,
                        sourceName = "sample_towers.kml",
                        statusMessage = "Loaded ${towers.size} sample towers (saved)",
                        isLoadingFile = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message ?: "Failed to load sample KML",
                        isLoadingFile = false
                    )
                }
            }
        }
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
                            sourceName = null
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
                    hiddenTowerIds = emptySet(),
                    selectedTowerId = null,
                    statusMessage = "Loaded ${towers.size} towers from $name"
                )
            }
        }
    }

    override fun onCleared() {
        stopLocationUpdates()
        super.onCleared()
    }

    companion object {
        private const val PREFS = "towerscope_prefs"
        private const val KEY_HUD_THEME = "hud_theme"

        private fun earthPoseEquivalent(a: EarthCameraPose?, b: EarthCameraPose?): Boolean {
            if (a === b) return true
            if (a == null || b == null) return false
            return kotlin.math.abs(a.latitude - b.latitude) < 0.00001 &&
                kotlin.math.abs(a.longitude - b.longitude) < 0.00001 &&
                kotlin.math.abs(a.altitudeMeters - b.altitudeMeters) < 0.75 &&
                kotlin.math.abs(a.headingDegrees - b.headingDegrees) < 1.5 &&
                kotlin.math.abs(a.horizontalAccuracyMeters - b.horizontalAccuracyMeters) < 1.0 &&
                kotlin.math.abs(a.headingAccuracyDegrees - b.headingAccuracyDegrees) < 2.0
        }
    }
}

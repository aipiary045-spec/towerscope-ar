package com.towerscope.ar.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.towerscope.ar.data.CsvTowerParser
import com.towerscope.ar.data.KmlParser
import com.towerscope.ar.data.LosProfile
import com.towerscope.ar.data.LosProfileDiskCache
import com.towerscope.ar.data.LosProfileService
import com.towerscope.ar.data.Tower
import com.towerscope.ar.data.TowerFileStore
import com.towerscope.ar.location.DeviceHeadingClient
import com.towerscope.ar.location.HeadingFilter
import com.towerscope.ar.location.HighAccuracyLocationClient
import com.towerscope.ar.location.UserLocation
import com.towerscope.ar.ui.AppTheme
import com.towerscope.ar.ui.HudTheme
import com.towerscope.ar.util.CelestialBodies
import com.towerscope.ar.util.CoordinateFormat
import com.towerscope.ar.util.DisplayUnits
import com.towerscope.ar.util.DistanceUnitSystem
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.util.LinkEstimate
import kotlinx.coroutines.delay
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

    fun fresnelClearanceMeters(clutterHeightMeters: Double, frequencyGhz: Double): Double? =
        profile?.minFresnelClearanceMeters(clutterHeightMeters, frequencyGhz)
}

data class TowerUiState(
    val towers: List<Tower> = emptyList(),
    val hiddenTowerIds: Set<String> = emptySet(),
    val maxDistanceMeters: Float = DEFAULT_MAX_DISTANCE_METERS,
    /**
     * Locate map only: when true, show every loaded site (ignore distance range).
     * Compass / LOS keep using [maxDistanceMeters].
     */
    val mapShowAllSites: Boolean = false,
    val searchQuery: String = "",
    val selectedTowerId: String? = null,
    val userLocation: UserLocation? = null,
    /**
     * Optional fixed install / customer site. When set, bearings, range, and LOS
     * use this instead of live GPS (GPS marker still available on the map).
     */
    val installLatitude: Double? = null,
    val installLongitude: Double? = null,
    /** Whether to use live GPS or a custom pinned location for checks. */
    val locationMode: LocationMode = LocationMode.CURRENT_GPS,
    /** Link frequency for Fresnel (GHz). */
    val frequencyGhz: Float = DEFAULT_FREQUENCY_GHZ,
    /** CPE / customer antenna height above ground (meters). */
    val cpeAntennaAglMeters: Float = DEFAULT_CPE_ANTENNA_AGL_METERS,
    /** Transmit power at the AP (dBm). */
    val txPowerDbm: Float = LinkEstimate.DEFAULT_TX_POWER_DBM,
    /** Access-point antenna gain (dBi). */
    val apAntennaGainDbi: Float = LinkEstimate.DEFAULT_AP_GAIN_DBI,
    /** Customer radio antenna gain (dBi). */
    val cpeAntennaGainDbi: Float = LinkEstimate.DEFAULT_CPE_GAIN_DBI,
    val distanceUnitSystem: DistanceUnitSystem = DistanceUnitSystem.IMPERIAL,
    val coordinateFormat: CoordinateFormat = CoordinateFormat.DECIMAL,
    val deviceHeadingDegrees: Double? = null,
    val compassSensorAccuracy: Int = android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
    val compassPitchDegrees: Double? = null,
    val compassRollDegrees: Double? = null,
    val compassTilted: Boolean = false,
    val compassMagneticInterference: Boolean = false,
    val compassRotationRateDps: Double = 0.0,
    val compassSightingActive: Boolean = false,
    val compassSightingProgress: Float = 0f,
    val hudTheme: HudTheme = HudTheme.DARK,
    /** Bottom HUD search/range/controls expanded. */
    val hudExpanded: Boolean = true,
    /**
     * Degrees added to device compass heading (manual fine-tune).
     * Null = no offset applied.
     */
    val headingCalibrationOffsetDegrees: Double? = null,
    /** Figure-8 / tilt coaching overlay visible on Aim. */
    val compassImproveActive: Boolean = false,
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

    val hasInstallSite: Boolean
        get() = installLatitude != null && installLongitude != null

    /**
     * Location used for bearings, distances, and LOS.
     * Follows [locationMode]: custom pin when [LocationMode.CUSTOM], else live GPS.
     */
    fun positioningLocation(): UserLocation? {
        return when (locationMode) {
            LocationMode.CUSTOM -> {
                val lat = installLatitude ?: return null
                val lon = installLongitude ?: return null
                UserLocation(
                    latitude = lat,
                    longitude = lon,
                    altitudeMeters = userLocation?.altitudeMeters,
                    accuracyMeters = userLocation?.accuracyMeters ?: 0f,
                    bearingDegrees = userLocation?.bearingDegrees,
                    speedMps = userLocation?.speedMps
                )
            }
            LocationMode.CURRENT_GPS -> userLocation
        }
    }

    fun usesCustomLocation(): Boolean =
        locationMode == LocationMode.CUSTOM && hasInstallSite

    /**
     * Facing heading from the device compass, plus optional manual offset.
     * Never uses GPS course — that is travel direction, not facing.
     */
    fun effectiveHeadingDegrees(): Double? {
        val device = deviceHeadingDegrees ?: return null
        val offset = headingCalibrationOffsetDegrees ?: 0.0
        return GeoUtils.normalizeBearing(device + offset)
    }

    /** Signed error to focus tower: positive = turn right. */
    fun focusTowerHeadingErrorDegrees(): Double? {
        val heading = effectiveHeadingDegrees() ?: return null
        val bearing = focusTower()?.let { bearingTo(it) } ?: return null
        return GeoUtils.relativeBearingDegrees(heading, bearing)
    }

    val compassQualityIssue: CompassQualityIssue
        get() = when {
            compassMagneticInterference -> CompassQualityIssue.METAL
            compassTilted -> CompassQualityIssue.TILT
            needsCompassCalibration -> CompassQualityIssue.LOW_ACCURACY
            else -> CompassQualityIssue.NONE
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

    fun visibleTowers(ignoreDistanceRange: Boolean = false): List<Tower> {
        val location = positioningLocation()
        val query = searchQuery.trim()
        return towers.filter { tower ->
            if (tower.id in hiddenTowerIds) return@filter false
            if (query.isNotEmpty() && !tower.name.contains(query, ignoreCase = true)) {
                return@filter false
            }
            if (ignoreDistanceRange || location == null) return@filter true
            val distance = GeoUtils.haversineMeters(
                location.latitude,
                location.longitude,
                tower.latitude,
                tower.longitude
            )
            distance <= maxDistanceMeters
        }
    }

    /** Locate map markers / chips — respects [mapShowAllSites] only in this view. */
    fun mapVisibleTowers(): List<Tower> = visibleTowers(ignoreDistanceRange = mapShowAllSites)

    fun mapNearestMatches(limit: Int = 8): List<Tower> {
        val location = positioningLocation()
        val visible = mapVisibleTowers()
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

    /** Focus site for Locate — when showing all sites, nearest can be outside saved range. */
    fun mapFocusTower(): Tower? {
        val selected = selectedTowerId?.let { id -> towers.firstOrNull { it.id == id } }
        if (selected != null && selected.id !in hiddenTowerIds) {
            if (mapShowAllSites || selected in visibleTowers()) return selected
        }
        return if (mapShowAllSites) mapNearestMatches(1).firstOrNull() else focusTower()
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
        const val DEFAULT_FREQUENCY_GHZ = 5.8f
        const val MIN_FREQUENCY_GHZ = 0.9f
        const val MAX_FREQUENCY_GHZ = 80f
        const val DEFAULT_CPE_ANTENNA_AGL_METERS = 4f
        const val MIN_CPE_ANTENNA_AGL_METERS = 1f
        const val MAX_CPE_ANTENNA_AGL_METERS = 30f
    }
}

class TowerScopeViewModel(application: Application) : AndroidViewModel(application) {

    private val locationClient = HighAccuracyLocationClient(application)
    private val headingClient = DeviceHeadingClient(application)
    private val fileStore = TowerFileStore(application)
    private val losProfileService = LosProfileService()
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
        migrateHeadingOffsetIfNeeded(it)
    }
    private val _uiState = MutableStateFlow(
        TowerUiState(
            maxDistanceMeters = loadMaxDistanceMeters(),
            hudTheme = loadHudTheme(),
            hudExpanded = prefs.getBoolean(KEY_HUD_EXPANDED, true),
            headingCalibrationOffsetDegrees = loadHeadingOffset(),
            clutterHeightMeters = prefs.getFloat(KEY_CLUTTER_HEIGHT, TowerUiState.DEFAULT_CLUTTER_METERS),
            showElevationProfile = prefs.getBoolean(KEY_SHOW_ELEVATION_PROFILE, true),
            frequencyGhz = prefs.getFloat(KEY_FREQUENCY_GHZ, TowerUiState.DEFAULT_FREQUENCY_GHZ),
            cpeAntennaAglMeters = prefs.getFloat(
                KEY_CPE_ANTENNA_AGL,
                TowerUiState.DEFAULT_CPE_ANTENNA_AGL_METERS
            ),
            txPowerDbm = prefs.getFloat(KEY_TX_POWER_DBM, LinkEstimate.DEFAULT_TX_POWER_DBM),
            apAntennaGainDbi = prefs.getFloat(KEY_AP_GAIN_DBI, LinkEstimate.DEFAULT_AP_GAIN_DBI),
            cpeAntennaGainDbi = prefs.getFloat(KEY_CPE_GAIN_DBI, LinkEstimate.DEFAULT_CPE_GAIN_DBI),
            distanceUnitSystem = loadDistanceUnitSystem(),
            coordinateFormat = loadCoordinateFormat(),
            installLatitude = prefs.getFloat(KEY_INSTALL_LAT, Float.NaN)
                .takeIf { !it.isNaN() }?.toDouble(),
            installLongitude = prefs.getFloat(KEY_INSTALL_LON, Float.NaN)
                .takeIf { !it.isNaN() }?.toDouble(),
            locationMode = loadLocationMode()
        )
    ).also { flow ->
        DisplayUnits.apply(flow.value.distanceUnitSystem, flow.value.coordinateFormat)
    }
    val uiState: StateFlow<TowerUiState> = _uiState.asStateFlow()

    private var locationJob: Job? = null
    private var headingJob: Job? = null
    private var sightingJob: Job? = null
    private var losJob: Job? = null
    private var losRangeJob: Job? = null
    private var losLoadingTowerId: String? = null

    init {
        // Drop any leftover on-device LOS profile files from older builds.
        runCatching { LosProfileDiskCache(application).clearAll() }
        restorePersistedTowers()
    }

    private fun loadHudTheme(): HudTheme {
        val raw = prefs.getString(KEY_HUD_THEME, HudTheme.DARK.name)
        return HudTheme.fromStored(raw)
    }

    private fun loadMaxDistanceMeters(): Float {
        if (!prefs.contains(KEY_MAX_DISTANCE)) return TowerUiState.DEFAULT_MAX_DISTANCE_METERS
        return prefs.getFloat(KEY_MAX_DISTANCE, TowerUiState.DEFAULT_MAX_DISTANCE_METERS)
            .coerceIn(TowerUiState.MIN_DISTANCE_METERS, TowerUiState.MAX_DISTANCE_METERS)
    }

    private fun migrateHeadingOffsetIfNeeded(prefs: android.content.SharedPreferences) {
        val version = prefs.getInt(KEY_HEADING_REMAP_VERSION, 0)
        if (version < HEADING_REMAP_VERSION) {
            prefs.edit()
                .remove(KEY_HEADING_OFFSET)
                .putInt(KEY_HEADING_REMAP_VERSION, HEADING_REMAP_VERSION)
                .apply()
        }
    }

    private fun loadHeadingOffset(): Double? {
        if (!prefs.contains(KEY_HEADING_OFFSET)) return null
        val value = prefs.getFloat(KEY_HEADING_OFFSET, 0f).toDouble()
        return if (value.isFinite()) value else null
    }

    private fun loadDistanceUnitSystem(): DistanceUnitSystem {
        val raw = prefs.getString(KEY_DISTANCE_UNITS, DistanceUnitSystem.IMPERIAL.name)
            ?: DistanceUnitSystem.IMPERIAL.name
        return runCatching { DistanceUnitSystem.valueOf(raw) }.getOrDefault(DistanceUnitSystem.IMPERIAL)
    }

    private fun loadCoordinateFormat(): CoordinateFormat {
        val raw = prefs.getString(KEY_COORD_FORMAT, CoordinateFormat.DECIMAL.name)
            ?: CoordinateFormat.DECIMAL.name
        return runCatching { CoordinateFormat.valueOf(raw) }.getOrDefault(CoordinateFormat.DECIMAL)
    }

    private fun loadLocationMode(): LocationMode {
        if (!prefs.contains(KEY_LOCATION_MODE)) {
            val hasInstall = !prefs.getFloat(KEY_INSTALL_LAT, Float.NaN).isNaN() &&
                !prefs.getFloat(KEY_INSTALL_LON, Float.NaN).isNaN()
            return if (hasInstall) LocationMode.CUSTOM else LocationMode.CURRENT_GPS
        }
        return LocationMode.fromStored(prefs.getString(KEY_LOCATION_MODE, null))
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

    fun startLocationUpdates(includeHeading: Boolean = true) {
        if (locationJob?.isActive == true) {
            if (includeHeading) startDeviceHeadingUpdates()
            return
        }
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
        if (includeHeading) {
            startDeviceHeadingUpdates()
        }
    }

    fun startDeviceHeadingUpdates() {
        if (headingJob?.isActive == true) return
        headingJob = viewModelScope.launch {
            headingClient.headingUpdates { _uiState.value.positioningLocation() }.collect { heading ->
                _uiState.update {
                    it.copy(
                        deviceHeadingDegrees = heading.degrees,
                        compassSensorAccuracy = heading.sensorAccuracy,
                        compassPitchDegrees = heading.pitchDegrees,
                        compassRollDegrees = heading.rollDegrees,
                        compassTilted = heading.tilted,
                        compassMagneticInterference = heading.magneticInterference,
                        compassRotationRateDps = heading.rotationRateDps
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

    fun setMapShowAllSites(showAll: Boolean) {
        _uiState.update { it.copy(mapShowAllSites = showAll) }
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
                losRangeRows = rankLosRangeRows(
                    state.losRangeRows,
                    clamped.toDouble(),
                    state.frequencyGhz.toDouble()
                )
            )
        }
    }

    fun setFrequencyGhz(ghz: Float) {
        val clamped = ghz.coerceIn(TowerUiState.MIN_FREQUENCY_GHZ, TowerUiState.MAX_FREQUENCY_GHZ)
        prefs.edit().putFloat(KEY_FREQUENCY_GHZ, clamped).apply()
        _uiState.update { state ->
            state.copy(
                frequencyGhz = clamped,
                losRangeRows = rankLosRangeRows(
                    state.losRangeRows,
                    state.clutterHeightMeters.toDouble(),
                    clamped.toDouble()
                )
            )
        }
    }

    fun setCpeAntennaAglMeters(meters: Float) {
        val clamped = meters.coerceIn(
            TowerUiState.MIN_CPE_ANTENNA_AGL_METERS,
            TowerUiState.MAX_CPE_ANTENNA_AGL_METERS
        )
        prefs.edit().putFloat(KEY_CPE_ANTENNA_AGL, clamped).apply()
        _uiState.update {
            it.copy(
                cpeAntennaAglMeters = clamped,
                // Eye height changes profiles — force Check LOS to re-scan.
                losRangeRows = emptyList(),
                losRangeStatus = "CPE height updated — refreshing…"
            )
        }
        clearLosProfile()
        _uiState.value.selectedTowerId?.let { loadLosProfile(it) }
    }

    fun setTxPowerDbm(dbm: Float) {
        val clamped = dbm.coerceIn(LinkEstimate.MIN_TX_POWER_DBM, LinkEstimate.MAX_TX_POWER_DBM)
        prefs.edit().putFloat(KEY_TX_POWER_DBM, clamped).apply()
        _uiState.update { it.copy(txPowerDbm = clamped) }
    }

    fun setApAntennaGainDbi(dbi: Float) {
        val clamped = dbi.coerceIn(LinkEstimate.MIN_ANTENNA_GAIN_DBI, LinkEstimate.MAX_ANTENNA_GAIN_DBI)
        prefs.edit().putFloat(KEY_AP_GAIN_DBI, clamped).apply()
        _uiState.update { it.copy(apAntennaGainDbi = clamped) }
    }

    fun setCpeAntennaGainDbi(dbi: Float) {
        val clamped = dbi.coerceIn(LinkEstimate.MIN_ANTENNA_GAIN_DBI, LinkEstimate.MAX_ANTENNA_GAIN_DBI)
        prefs.edit().putFloat(KEY_CPE_GAIN_DBI, clamped).apply()
        _uiState.update { it.copy(cpeAntennaGainDbi = clamped) }
    }

    fun setInstallSite(latitude: Double, longitude: Double) {
        prefs.edit()
            .putFloat(KEY_INSTALL_LAT, latitude.toFloat())
            .putFloat(KEY_INSTALL_LON, longitude.toFloat())
            .putString(KEY_LOCATION_MODE, LocationMode.CUSTOM.name)
            .apply()
        _uiState.update {
            it.copy(
                installLatitude = latitude,
                installLongitude = longitude,
                locationMode = LocationMode.CUSTOM,
                statusMessage = "Custom location set"
            )
        }
        clearLosProfile()
        clearLosRangeProfiles()
    }

    fun setLocationMode(mode: LocationMode) {
        if (_uiState.value.locationMode == mode) return
        prefs.edit().putString(KEY_LOCATION_MODE, mode.name).apply()
        val message = when (mode) {
            LocationMode.CURRENT_GPS -> "Using your location for checks"
            LocationMode.CUSTOM -> if (_uiState.value.hasInstallSite) {
                "Using custom location for checks"
            } else {
                "Set a custom location on the map"
            }
        }
        _uiState.update { it.copy(locationMode = mode, statusMessage = message) }
        clearLosProfile()
        clearLosRangeProfiles()
        _uiState.value.selectedTowerId?.let { loadLosProfile(it) }
    }

    fun setInstallSiteFromGps() {
        val user = _uiState.value.userLocation ?: return
        setInstallSite(user.latitude, user.longitude)
    }

    fun clearInstallSite() {
        prefs.edit()
            .remove(KEY_INSTALL_LAT)
            .remove(KEY_INSTALL_LON)
            .apply()
        val nextMode = if (_uiState.value.locationMode == LocationMode.CUSTOM) {
            prefs.edit().putString(KEY_LOCATION_MODE, LocationMode.CURRENT_GPS.name).apply()
            LocationMode.CURRENT_GPS
        } else {
            _uiState.value.locationMode
        }
        _uiState.update {
            it.copy(
                installLatitude = null,
                installLongitude = null,
                locationMode = nextMode,
                statusMessage = "Custom location cleared — using your location"
            )
        }
        clearLosProfile()
        clearLosRangeProfiles()
    }

    fun cycleDistanceUnitSystem() {
        val next = when (_uiState.value.distanceUnitSystem) {
            DistanceUnitSystem.IMPERIAL -> DistanceUnitSystem.METRIC
            DistanceUnitSystem.METRIC -> DistanceUnitSystem.IMPERIAL
        }
        prefs.edit().putString(KEY_DISTANCE_UNITS, next.name).apply()
        _uiState.update { it.copy(distanceUnitSystem = next) }
        DisplayUnits.apply(next, _uiState.value.coordinateFormat)
    }

    fun cycleCoordinateFormat() {
        val next = when (_uiState.value.coordinateFormat) {
            CoordinateFormat.DECIMAL -> CoordinateFormat.DMS
            CoordinateFormat.DMS -> CoordinateFormat.DECIMAL
        }
        prefs.edit().putString(KEY_COORD_FORMAT, next.name).apply()
        _uiState.update { it.copy(coordinateFormat = next) }
        // Avoid stale DisplayUnits if cycleCoordinateFormat runs after reading distance from old state
        DisplayUnits.apply(_uiState.value.distanceUnitSystem, next)
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
     * Sample geodesic points to [towerId], query live LiDAR/DEM elevations, apply Earth curvature.
     * Always fetches fresh data (no profile disk cache).
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
                    losProfileError = if (_uiState.value.locationMode == LocationMode.CUSTOM) {
                        "Set a custom location on the Locate map"
                    } else {
                        "Waiting for GPS fix"
                    }
                )
            }
            return
        }
        if (losLoadingTowerId == towerId && losJob?.isActive == true) return

        losJob?.cancel()
        losLoadingTowerId = towerId
        _uiState.update {
            it.copy(
                losProfileLoading = true,
                losProfileError = null,
                losProfile = null
            )
        }
        losJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    losProfileService.buildProfile(
                        tower = tower,
                        observerLat = location.latitude,
                        observerLon = location.longitude,
                        eyeHeightAboveGroundMeters = _uiState.value.cpeAntennaAglMeters.toDouble()
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
                    losRangeStatus = if (it.locationMode == LocationMode.CUSTOM) {
                        "Set a custom location on the Locate map"
                    } else {
                        "Waiting for GPS fix"
                    }
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
                    losRangeStatus = "No sites in range (${GeoUtils.formatDistance(it.maxDistanceMeters.toDouble())})"
                )
            }
            return
        }

        val seed = targets.map { (tower, distance) ->
            LosRangeRow(tower = tower, distanceMeters = distance, loading = true)
        }
        val originLabel = when (_uiState.value.locationMode) {
            LocationMode.CUSTOM -> "custom location"
            LocationMode.CURRENT_GPS -> "your location"
        }
        _uiState.update {
            it.copy(
                losRangeRows = seed,
                losRangeLoading = true,
                losRangeStatus = "Ranking ${seed.size} APs from $originLabel…"
            )
        }

        losRangeJob = viewModelScope.launch {
            val gate = Semaphore(LOS_RANGE_CONCURRENCY)
            val clutter = _uiState.value.clutterHeightMeters.toDouble()
            val freq = _uiState.value.frequencyGhz.toDouble()
            val eye = _uiState.value.cpeAntennaAglMeters.toDouble()
            coroutineScope {
                targets.map { (tower, distance) ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val result = runCatching {
                                losProfileService.buildProfile(
                                    tower = tower,
                                    observerLat = location.latitude,
                                    observerLon = location.longitude,
                                    eyeHeightAboveGroundMeters = eye
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
                                    val ranked = rankLosRangeRows(updated, clutter, freq)
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
                val ranked = rankLosRangeRows(
                    state.losRangeRows,
                    state.clutterHeightMeters.toDouble(),
                    state.frequencyGhz.toDouble()
                )
                state.copy(
                    losRangeRows = ranked,
                    losRangeLoading = false,
                    losRangeStatus = "Done · ${ranked.size} APs · best Fresnel first"
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
            AppTheme.apply(next)
            state.copy(hudTheme = next)
        }
    }

    fun applyPersistedTheme() {
        AppTheme.apply(_uiState.value.hudTheme)
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

    /** Show figure-8 / tilt coaching overlay on Aim. */
    fun beginCompassImprove() {
        _uiState.update {
            it.copy(
                compassImproveActive = true,
                statusMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissCompassImprove() {
        _uiState.update { it.copy(compassImproveActive = false) }
    }

    fun calibrateHeadingToKnownAzimuth(
        trueAzimuthDegrees: Double,
        deviceSamples: List<Double>? = null
    ) {
        val state = _uiState.value
        val device = deviceSamples?.let { HeadingFilter.circularMean(it) }
            ?: state.deviceHeadingDegrees
            ?: run {
                _uiState.update { it.copy(statusMessage = "Waiting for compass…") }
                return
            }
        val currentOffset = state.headingCalibrationOffsetDegrees ?: 0.0
        val effective = GeoUtils.normalizeBearing(device + currentOffset)
        val correction = CelestialBodies.signedDeltaDegrees(effective, trueAzimuthDegrees)
        setHeadingOffsetDegrees(currentOffset + correction)
    }

    fun startCompassSighting(target: CompassSightingTarget) {
        if (sightingJob?.isActive == true) return
        val trueAzimuth = resolveSightingAzimuth(target) ?: return
        sightingJob = viewModelScope.launch {
            val samples = mutableListOf<Double>()
            val startedAt = System.currentTimeMillis()
            _uiState.update { it.copy(compassSightingActive = true, compassSightingProgress = 0f) }
            while (true) {
                val elapsed = System.currentTimeMillis() - startedAt
                val progress = (elapsed.toFloat() / SIGHTING_DURATION_MS).coerceIn(0f, 1f)
                _uiState.value.deviceHeadingDegrees?.let { samples += it }
                _uiState.update { it.copy(compassSightingProgress = progress) }
                if (elapsed >= SIGHTING_DURATION_MS) break
                delay(SIGHTING_SAMPLE_MS)
            }
            _uiState.update { it.copy(compassSightingActive = false, compassSightingProgress = 0f) }
            if (samples.size < SIGHTING_MIN_SAMPLES) {
                _uiState.update { it.copy(statusMessage = "Hold steady a bit longer…") }
                return@launch
            }
            calibrateHeadingToKnownAzimuth(trueAzimuth, samples)
            val label = when (target) {
                CompassSightingTarget.SUN -> "sun"
                CompassSightingTarget.TOWER -> _uiState.value.focusTower()?.name ?: "tower"
            }
            _uiState.update {
                it.copy(statusMessage = "Calibrated to $label (${samples.size} samples)")
            }
        }
    }

    fun cancelCompassSighting() {
        sightingJob?.cancel()
        sightingJob = null
        _uiState.update { it.copy(compassSightingActive = false, compassSightingProgress = 0f) }
    }

    private fun resolveSightingAzimuth(target: CompassSightingTarget): Double? {
        return when (target) {
            CompassSightingTarget.SUN -> {
                val location = _uiState.value.positioningLocation() ?: run {
                    _uiState.update { it.copy(statusMessage = "Need GPS fix for sun calibration") }
                    return null
                }
                CelestialBodies.preferredCalibrationTarget(
                    location.latitude,
                    location.longitude
                )?.azimuthDegrees ?: run {
                    _uiState.update { it.copy(statusMessage = "Sun/moon too low for calibration right now") }
                    null
                }
            }
            CompassSightingTarget.TOWER -> {
                val tower = _uiState.value.focusTower() ?: run {
                    _uiState.update { it.copy(statusMessage = "No tower in range to calibrate against") }
                    return null
                }
                _uiState.value.bearingTo(tower) ?: run {
                    _uiState.update { it.copy(statusMessage = "Need location to calibrate to tower") }
                    null
                }
            }
        }
    }

    fun calibrateHeadingToSun(): Boolean {
        val location = _uiState.value.positioningLocation() ?: run {
            _uiState.update { it.copy(statusMessage = "Need GPS fix for sun calibration") }
            return false
        }
        val target = CelestialBodies.preferredCalibrationTarget(
            location.latitude,
            location.longitude
        ) ?: run {
            _uiState.update { it.copy(statusMessage = "Sun/moon too low for calibration right now") }
            return false
        }
        calibrateHeadingToKnownAzimuth(target.azimuthDegrees)
        val label = if (target.body == CelestialBodies.Body.SUN) "sun" else "moon"
        _uiState.update {
            it.copy(statusMessage = "Calibrated to $label · hold phone top toward it")
        }
        return true
    }

    fun calibrateHeadingToFocusTower(): Boolean {
        val tower = _uiState.value.focusTower() ?: run {
            _uiState.update { it.copy(statusMessage = "No tower in range to calibrate against") }
            return false
        }
        val bearing = _uiState.value.bearingTo(tower) ?: run {
            _uiState.update { it.copy(statusMessage = "Need location to calibrate to tower") }
            return false
        }
        calibrateHeadingToKnownAzimuth(bearing)
        _uiState.update {
            it.copy(statusMessage = "Calibrated to ${tower.name} — face the site, then tap Done")
        }
        return true
    }

    fun setHeadingOffsetDegrees(offsetDegrees: Double) {
        val clamped = offsetDegrees.coerceIn(-180.0, 180.0)
        prefs.edit().putFloat(KEY_HEADING_OFFSET, clamped.toFloat()).apply()
        _uiState.update {
            it.copy(
                headingCalibrationOffsetDegrees = clamped,
                statusMessage = String.format(
                    java.util.Locale.US,
                    "Heading offset %+.0f°",
                    clamped
                )
            )
        }
    }

    fun nudgeHeadingOffset(deltaDegrees: Double) {
        val current = _uiState.value.headingCalibrationOffsetDegrees ?: 0.0
        setHeadingOffsetDegrees(current + deltaDegrees)
    }

    fun clearHeadingOffset() {
        prefs.edit().remove(KEY_HEADING_OFFSET).apply()
        _uiState.update {
            it.copy(
                headingCalibrationOffsetDegrees = null,
                statusMessage = "Heading offset cleared"
            )
        }
    }

    /** @deprecated Use [clearHeadingOffset]. */
    fun clearHeadingCalibration() = clearHeadingOffset()

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
                    } ?: uri.lastPathSegment ?: "import"
                    val lower = displayName.lowercase()
                    val mime = resolver.getType(uri).orEmpty().lowercase()
                    val isCsv = lower.endsWith(".csv") ||
                        mime.contains("text/csv") ||
                        mime.contains("comma-separated")
                    val towers = if (isCsv || looksLikeCsv(bytes)) {
                        CsvTowerParser.parseUri(getApplication(), uri)
                    } else {
                        KmlParser.parseUri(getApplication(), uri)
                    }
                    if (towers.isNotEmpty()) {
                        fileStore.saveImport(displayName, uri, towers, bytes)
                    }
                    displayName to towers
                }
                val (name, towers) = result
                if (towers.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            errorMessage = "No sites found — use KML/KMZ or CSV with name, lat, lon",
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
                            statusMessage = "Loaded ${towers.size} sites from $name (saved)",
                            isLoadingFile = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message ?: "Failed to parse file",
                        isLoadingFile = false
                    )
                }
            }
        }
    }

    private fun looksLikeCsv(bytes: ByteArray): Boolean {
        val head = bytes.decodeToString(0, minOf(bytes.size, 200)).lowercase()
        return head.contains("lat") && (head.contains("lon") || head.contains("lng"))
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
        private const val KEY_HEADING_REMAP_VERSION = "heading_remap_version"
        private const val HEADING_REMAP_VERSION = 2
        private const val SIGHTING_DURATION_MS = 2_500L
        private const val SIGHTING_SAMPLE_MS = 50L
        private const val SIGHTING_MIN_SAMPLES = 20
        private const val KEY_CLUTTER_HEIGHT = "clutter_height_meters"
        private const val KEY_SHOW_ELEVATION_PROFILE = "show_elevation_profile"
        private const val KEY_MAX_DISTANCE = "max_distance_meters"
        private const val KEY_FREQUENCY_GHZ = "frequency_ghz"
        private const val KEY_CPE_ANTENNA_AGL = "cpe_antenna_agl_meters"
        private const val KEY_TX_POWER_DBM = "tx_power_dbm"
        private const val KEY_AP_GAIN_DBI = "ap_antenna_gain_dbi"
        private const val KEY_CPE_GAIN_DBI = "cpe_antenna_gain_dbi"
        private const val KEY_INSTALL_LAT = "install_latitude"
        private const val KEY_INSTALL_LON = "install_longitude"
        private const val KEY_LOCATION_MODE = "location_mode"
        private const val KEY_DISTANCE_UNITS = "distance_unit_system"
        private const val KEY_COORD_FORMAT = "coordinate_format"
        private const val MAX_LOS_RANGE_TOWERS = 40
        private const val LOS_RANGE_CONCURRENCY = 2

        /**
         * Best Fresnel margin first; Fresnel-clear above geometric-only / blocked; failures last.
         */
        fun rankLosRangeRows(
            rows: List<LosRangeRow>,
            clutterHeightMeters: Double,
            frequencyGhz: Double
        ): List<LosRangeRow> {
            return rows.sortedWith { a, b ->
                val fa = a.fresnelClearanceMeters(clutterHeightMeters, frequencyGhz)
                val fb = b.fresnelClearanceMeters(clutterHeightMeters, frequencyGhz)
                when {
                    fa == null && fb == null -> a.distanceMeters.compareTo(b.distanceMeters)
                    fa == null -> 1
                    fb == null -> -1
                    fa > 0 && fb <= 0 -> -1
                    fa <= 0 && fb > 0 -> 1
                    else -> {
                        val byFresnel = fb.compareTo(fa)
                        if (byFresnel != 0) byFresnel
                        else a.distanceMeters.compareTo(b.distanceMeters)
                    }
                }
            }
        }
    }
}

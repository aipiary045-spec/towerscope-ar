package com.towerscope.ar.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.towerscope.ar.data.KmlParser
import com.towerscope.ar.data.Tower
import com.towerscope.ar.data.TowerFileStore
import com.towerscope.ar.location.HighAccuracyLocationClient
import com.towerscope.ar.location.UserLocation
import com.towerscope.ar.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TowerUiState(
    val towers: List<Tower> = emptyList(),
    val hiddenTowerIds: Set<String> = emptySet(),
    val maxDistanceMeters: Float = DEFAULT_MAX_DISTANCE_METERS,
    val userLocation: UserLocation? = null,
    val cameraHeadingDegrees: Double? = null,
    val earthTracking: Boolean = false,
    val sourceName: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isLoadingFile: Boolean = false
) {
    fun visibleTowers(): List<Tower> {
        val location = userLocation
        return towers.filter { tower ->
            if (tower.id in hiddenTowerIds) return@filter false
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
        val location = userLocation ?: return null
        return GeoUtils.haversineMeters(
            location.latitude,
            location.longitude,
            tower.latitude,
            tower.longitude
        )
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
    private val fileStore = TowerFileStore(application)
    private val _uiState = MutableStateFlow(TowerUiState())
    val uiState: StateFlow<TowerUiState> = _uiState.asStateFlow()

    private var locationJob: Job? = null

    init {
        restorePersistedTowers()
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
        locationJob = viewModelScope.launch {
            locationClient.locationUpdates().collect { location ->
                _uiState.update { it.copy(userLocation = location) }
            }
        }
    }

    fun stopLocationUpdates() {
        locationJob?.cancel()
        locationJob = null
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

    fun setEarthTracking(tracking: Boolean) {
        _uiState.update { it.copy(earthTracking = tracking) }
    }

    fun setCameraHeadingDegrees(heading: Double?) {
        _uiState.update { it.copy(cameraHeadingDegrees = heading) }
    }

    fun hideTower(towerId: String) {
        _uiState.update {
            it.copy(
                hiddenTowerIds = it.hiddenTowerIds + towerId,
                statusMessage = "Tower filtered out of the scene"
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
                    sourceName = null,
                    statusMessage = "Cleared saved tower data"
                )
            }
        }
    }

    override fun onCleared() {
        stopLocationUpdates()
        super.onCleared()
    }
}

package com.towerscope.ar.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.towerscope.ar.data.KmlParser
import com.towerscope.ar.data.Tower
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
    val earthTracking: Boolean = false,
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
        const val MAX_DISTANCE_METERS = 10_000f
        const val DEFAULT_MAX_DISTANCE_METERS = 2_000f
    }
}

class TowerScopeViewModel(application: Application) : AndroidViewModel(application) {

    private val locationClient = HighAccuracyLocationClient(application)
    private val _uiState = MutableStateFlow(TowerUiState())
    val uiState: StateFlow<TowerUiState> = _uiState.asStateFlow()

    private var locationJob: Job? = null

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
                    KmlParser.parseAsset(getApplication(), "sample_towers.kml")
                }
                _uiState.update {
                    it.copy(
                        towers = towers,
                        hiddenTowerIds = emptySet(),
                        statusMessage = "Loaded ${towers.size} sample towers",
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
                val towers = withContext(Dispatchers.IO) {
                    KmlParser.parseUri(getApplication(), uri)
                }
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
                            statusMessage = "Loaded ${towers.size} towers",
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

    override fun onCleared() {
        stopLocationUpdates()
        super.onCleared()
    }
}

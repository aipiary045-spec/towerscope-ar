package com.towerscope.ar.viewmodel

import com.towerscope.ar.data.Tower
import com.towerscope.ar.location.UserLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallHubStateTest {

    private val gps = UserLocation(
        latitude = 35.0,
        longitude = -96.0,
        altitudeMeters = 200.0,
        accuracyMeters = 5f,
        bearingDegrees = null
    )

    private fun tower(id: String, name: String, lat: Double, lon: Double) = Tower(
        id = id,
        name = name,
        latitude = lat,
        longitude = lon,
        altitudeMeters = 30.0
    )

    @Test
    fun allTowersSortedByDistance_ordersNearestFirst() {
        val state = TowerUiState(
            userLocation = gps,
            towers = listOf(
                tower("far", "far", 36.0, -96.0),
                tower("near", "near", 35.01, -96.0),
                tower("mid", "mid", 35.5, -96.0)
            )
        )
        val names = state.allTowersSortedByDistance().map { it.first.name }
        assertEquals(listOf("near", "mid", "far"), names)
    }

    @Test
    fun isTowerInRange_respectsSavedRange() {
        val state = TowerUiState(
            userLocation = gps,
            maxDistanceMeters = 5_000f,
            towers = listOf(
                tower("near", "near", 35.01, -96.0),
                tower("far", "far", 36.0, -96.0)
            )
        )
        assertTrue(state.isTowerInRange(state.towers[0]))
        assertFalse(state.isTowerInRange(state.towers[1]))
    }
}

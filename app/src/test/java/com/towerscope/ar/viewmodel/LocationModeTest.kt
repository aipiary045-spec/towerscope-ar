package com.towerscope.ar.viewmodel

import com.towerscope.ar.location.UserLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationModeTest {

    private val gps = UserLocation(
        latitude = 30.2672,
        longitude = -97.7431,
        altitudeMeters = 150.0,
        accuracyMeters = 8f,
        bearingDegrees = 45f
    )

    @Test
    fun positioningLocation_usesGpsWhenModeIsCurrentGps() {
        val state = TowerUiState(
            userLocation = gps,
            installLatitude = 35.0,
            installLongitude = -96.0,
            locationMode = LocationMode.CURRENT_GPS
        )
        assertEquals(gps.latitude, state.positioningLocation()?.latitude!!, 0.0001)
        assertEquals(gps.longitude, state.positioningLocation()?.longitude!!, 0.0001)
    }

    @Test
    fun positioningLocation_usesCustomPinWhenModeIsCustom() {
        val state = TowerUiState(
            userLocation = gps,
            installLatitude = 35.0,
            installLongitude = -96.0,
            locationMode = LocationMode.CUSTOM
        )
        val location = state.positioningLocation()
        assertEquals(35.0, location?.latitude!!, 0.0001)
        assertEquals(-96.0, location.longitude, 0.0001)
    }

    @Test
    fun positioningLocation_customModeWithoutPinReturnsNull() {
        val state = TowerUiState(
            userLocation = gps,
            locationMode = LocationMode.CUSTOM
        )
        assertNull(state.positioningLocation())
    }

    @Test
    fun fromStored_defaultsToCurrentGpsForUnknownValue() {
        assertEquals(LocationMode.CURRENT_GPS, LocationMode.fromStored("invalid"))
        assertEquals(LocationMode.CUSTOM, LocationMode.fromStored("CUSTOM"))
    }
}

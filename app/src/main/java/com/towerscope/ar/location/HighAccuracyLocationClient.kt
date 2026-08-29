package com.towerscope.ar.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val bearingDegrees: Float?,
    val speedMps: Float? = null
)

/**
 * High-accuracy fused GPS updates via Google Play Services Location.
 */
class HighAccuracyLocationClient(context: Context) {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)

    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLocationPermission(): Boolean {
        if (hasFineLocationPermission()) return true
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return coarse
    }

    @SuppressLint("MissingPermission")
    fun locationUpdates(): Flow<UserLocation> = callbackFlow {
        if (!hasLocationPermission()) {
            close(IllegalStateException("Location permission not granted"))
            return@callbackFlow
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(1f)
            .setWaitForAccurateLocation(true)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                trySend(location.toUserLocation())
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        fusedClient.lastLocation.addOnSuccessListener { location ->
            location?.let { trySend(it.toUserLocation()) }
        }

        awaitClose {
            fusedClient.removeLocationUpdates(callback)
        }
    }

    private fun Location.toUserLocation(): UserLocation = UserLocation(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) altitude else null,
        accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        bearingDegrees = if (hasBearing()) bearing else null,
        speedMps = if (hasSpeed()) speed else null
    )

    companion object {
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val MIN_UPDATE_INTERVAL_MS = 500L
    }
}

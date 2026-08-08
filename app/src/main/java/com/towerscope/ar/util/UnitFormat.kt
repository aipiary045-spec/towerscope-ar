package com.towerscope.ar.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

enum class DistanceUnitSystem {
    IMPERIAL,
    METRIC
}

enum class CoordinateFormat {
    DECIMAL,
    DMS
}

/**
 * App-wide display units. Updated from Settings prefs via [DisplayUnits.apply].
 */
object DisplayUnits {
    @Volatile
    var distance: DistanceUnitSystem = DistanceUnitSystem.IMPERIAL
        private set

    @Volatile
    var coordinates: CoordinateFormat = CoordinateFormat.DECIMAL
        private set

    fun apply(distance: DistanceUnitSystem, coordinates: CoordinateFormat) {
        this.distance = distance
        this.coordinates = coordinates
    }
}

object UnitFormat {

    fun formatDistance(
        meters: Double,
        system: DistanceUnitSystem = DisplayUnits.distance
    ): String {
        return when (system) {
            DistanceUnitSystem.IMPERIAL -> {
                val miles = meters / GeoUtils.METERS_PER_MILE
                val feet = meters / 0.3048
                when {
                    miles >= 0.1 -> String.format(Locale.US, "%.1f mi", miles)
                    feet >= 1.0 -> String.format(Locale.US, "%.0f ft", feet)
                    else -> String.format(Locale.US, "%.1f ft", feet)
                }
            }
            DistanceUnitSystem.METRIC -> {
                val km = meters / 1000.0
                when {
                    km >= 1.0 -> String.format(Locale.US, "%.2f km", km)
                    meters >= 1.0 -> String.format(Locale.US, "%.0f m", meters)
                    else -> String.format(Locale.US, "%.1f m", meters)
                }
            }
        }
    }

    fun formatCoordinates(
        latitude: Double,
        longitude: Double,
        format: CoordinateFormat = DisplayUnits.coordinates
    ): String {
        return when (format) {
            CoordinateFormat.DECIMAL ->
                String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
            CoordinateFormat.DMS ->
                "${toDms(latitude, true)}, ${toDms(longitude, false)}"
        }
    }

    private fun toDms(value: Double, latitude: Boolean): String {
        val hemi = when {
            latitude && value >= 0 -> "N"
            latitude -> "S"
            value >= 0 -> "E"
            else -> "W"
        }
        val abs = abs(value)
        val deg = floor(abs).toInt()
        val minFloat = (abs - deg) * 60.0
        val min = floor(minFloat).toInt()
        val sec = (minFloat - min) * 60.0
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", deg, min, sec, hemi)
    }
}

/**
 * Free-space path loss (Friis) estimate — not a full link budget.
 */
object LinkEstimate {

    /**
     * FSPL in dB for [distanceMeters] at [frequencyGhz].
     * Formula: 20·log10(d_km) + 20·log10(f_GHz) + 92.45
     */
    fun freeSpacePathLossDb(distanceMeters: Double, frequencyGhz: Double): Double {
        val dKm = (distanceMeters / 1000.0).coerceAtLeast(1e-6)
        val f = frequencyGhz.coerceAtLeast(0.1)
        return 20.0 * log10(dKm) + 20.0 * log10(f) + 92.45
    }

    fun formatEstimate(distanceMeters: Double, frequencyGhz: Double): String {
        val db = freeSpacePathLossDb(distanceMeters, frequencyGhz)
        return String.format(
            Locale.US,
            "Est. FSPL  %.0f dB · %.1f GHz",
            db,
            frequencyGhz
        )
    }
}

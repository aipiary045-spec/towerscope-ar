package com.towerscope.ar.util

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    const val METERS_PER_MILE = 1609.344

    /** Great-circle distance between two WGS84 points in meters. */
    fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Initial bearing from point 1 to point 2, degrees clockwise from true north [0, 360).
     */
    fun bearingDegrees(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
    }

    /** Signed relative bearing: positive = target is to the right of heading. */
    fun relativeBearingDegrees(headingDegrees: Double, targetBearingDegrees: Double): Double {
        var delta = targetBearingDegrees - headingDegrees
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        return delta
    }

    fun formatDistance(meters: Double): String {
        val miles = meters / METERS_PER_MILE
        return when {
            miles >= 0.1 -> String.format(Locale.US, "%.1f mi", miles)
            meters >= 1.0 -> String.format(Locale.US, "%.0f m", meters)
            else -> String.format(Locale.US, "%.1f m", meters)
        }
    }

    fun cardinalFromDegrees(degrees: Double): String {
        val normalized = ((degrees % 360.0) + 360.0) % 360.0
        val index = ((normalized + 22.5) / 45.0).toInt() % 8
        return CARDINALS[index]
    }

    fun formatBearing(degrees: Double): String {
        val normalized = ((degrees % 360.0) + 360.0) % 360.0
        return String.format(Locale.US, "%s %.0f°", cardinalFromDegrees(normalized), normalized)
    }

    /** Relative turn cue: ahead / L xx° / R xx°. */
    fun formatRelativeTurn(relativeBearingDegrees: Double): String {
        val abs = kotlin.math.abs(relativeBearingDegrees)
        return when {
            abs <= 12.0 -> "ahead"
            relativeBearingDegrees < 0 -> String.format(Locale.US, "L %.0f°", abs)
            else -> String.format(Locale.US, "R %.0f°", abs)
        }
    }

    private val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
}

package com.towerscope.ar.util

import java.util.Locale
import kotlin.math.abs
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
 * App-wide display prefs applied from [TowerScopeViewModel] so formatters stay consistent.
 */
object DisplayUnits {
    @Volatile
    var distanceUnitSystem: DistanceUnitSystem = DistanceUnitSystem.IMPERIAL
        private set

    @Volatile
    var coordinateFormat: CoordinateFormat = CoordinateFormat.DECIMAL
        private set

    fun apply(distance: DistanceUnitSystem, coordinates: CoordinateFormat) {
        distanceUnitSystem = distance
        coordinateFormat = coordinates
    }
}

object UnitFormat {
    fun formatDistance(
        meters: Double,
        system: DistanceUnitSystem = DisplayUnits.distanceUnitSystem
    ): String {
        if (!meters.isFinite() || meters < 0) return "—"
        return when (system) {
            DistanceUnitSystem.IMPERIAL -> {
                val miles = meters / GeoUtils.METERS_PER_MILE
                if (miles >= 0.1) {
                    String.format(Locale.US, "%.2f mi", miles)
                } else {
                    String.format(Locale.US, "%.0f ft", meters / GeoUtils.METERS_PER_FOOT)
                }
            }
            DistanceUnitSystem.METRIC -> {
                if (meters >= 1000.0) {
                    String.format(Locale.US, "%.2f km", meters / 1000.0)
                } else {
                    String.format(Locale.US, "%.0f m", meters)
                }
            }
        }
    }

    fun formatCoordinates(
        latitude: Double,
        longitude: Double,
        format: CoordinateFormat = DisplayUnits.coordinateFormat
    ): String = when (format) {
        CoordinateFormat.DECIMAL ->
            String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
        CoordinateFormat.DMS ->
            "${toDms(latitude, true)}, ${toDms(longitude, false)}"
    }

    private fun toDms(value: Double, isLat: Boolean): String {
        val hemi = when {
            isLat && value >= 0 -> "N"
            isLat -> "S"
            value >= 0 -> "E"
            else -> "W"
        }
        val abs = abs(value)
        val deg = abs.toInt()
        val minFloat = (abs - deg) * 60.0
        val min = minFloat.toInt()
        val sec = (minFloat - min) * 60.0
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", deg, min, sec, hemi)
    }
}

/**
 * Simple WISP link estimate: free-space path loss plus optional terrain/Fresnel penalty.
 * Not a substitute for a full radio survey.
 */
object LinkEstimate {

    const val DEFAULT_TX_POWER_DBM = 20f
    const val MIN_TX_POWER_DBM = 0f
    const val MAX_TX_POWER_DBM = 30f

    const val DEFAULT_AP_GAIN_DBI = 20f
    const val DEFAULT_CPE_GAIN_DBI = 20f
    const val MIN_ANTENNA_GAIN_DBI = 0f
    const val MAX_ANTENNA_GAIN_DBI = 40f

    /**
     * FSPL in dB for [distanceMeters] at [frequencyGhz].
     * Formula: 20·log10(d_km) + 20·log10(f_GHz) + 92.45
     */
    fun freeSpacePathLossDb(distanceMeters: Double, frequencyGhz: Double): Double {
        val dKm = (distanceMeters / 1000.0).coerceAtLeast(1e-6)
        val f = frequencyGhz.coerceAtLeast(0.1)
        return 20.0 * log10(dKm) + 20.0 * log10(f) + 92.45
    }

    /**
     * Extra loss when the path is Fresnel-tight or geometrically blocked.
     * [geometricClearanceMeters] / [fresnelClearanceMeters] may be null when unknown.
     */
    fun obstructionLossDb(
        geometricClearanceMeters: Double?,
        fresnelClearanceMeters: Double?
    ): Double {
        if (geometricClearanceMeters != null && geometricClearanceMeters <= 0.0) {
            // Rough diffraction / blockage penalty — enough to look unusable.
            return 25.0 + (abs(geometricClearanceMeters).coerceAtMost(20.0) * 0.5)
        }
        if (fresnelClearanceMeters != null && fresnelClearanceMeters <= 0.0) {
            // Partial first-Fresnel intrusion.
            return (6.0 + abs(fresnelClearanceMeters).coerceAtMost(15.0)).coerceAtMost(20.0)
        }
        return 0.0
    }

    /**
     * Estimated receive level at the CPE (dBm):
     * Tx + AP gain + CPE gain − FSPL − obstruction loss.
     */
    fun estimatedReceiveLevelDbm(
        distanceMeters: Double,
        frequencyGhz: Double,
        txPowerDbm: Double,
        apGainDbi: Double,
        cpeGainDbi: Double,
        geometricClearanceMeters: Double? = null,
        fresnelClearanceMeters: Double? = null
    ): Double {
        val fspl = freeSpacePathLossDb(distanceMeters, frequencyGhz)
        val obstruction = obstructionLossDb(geometricClearanceMeters, fresnelClearanceMeters)
        return txPowerDbm + apGainDbi + cpeGainDbi - fspl - obstruction
    }

    fun formatReceiveLevel(dbm: Double): String =
        String.format(Locale.US, "Est.  %+.0f dBm", dbm)

    fun formatReceiveLevelDetailed(
        dbm: Double,
        frequencyGhz: Double,
        obstructionLossDb: Double
    ): String {
        val pathNote = when {
            obstructionLossDb >= 20.0 -> " · path blocked"
            obstructionLossDb >= 6.0 -> " · Fresnel tight"
            else -> ""
        }
        return String.format(
            Locale.US,
            "Est.  %+.0f dBm · %.1f GHz%s",
            dbm,
            frequencyGhz,
            pathNote
        )
    }

    /** Field-facing quality bucket from estimated dBm. */
    fun signalQuality(dbm: Double): SignalQuality = when {
        dbm >= -65.0 -> SignalQuality.STRONG
        dbm >= -75.0 -> SignalQuality.OK
        dbm >= -85.0 -> SignalQuality.WEAK
        else -> SignalQuality.POOR
    }

    enum class SignalQuality(val label: String) {
        STRONG("STRONG"),
        OK("OK"),
        WEAK("WEAK"),
        POOR("POOR")
    }
}

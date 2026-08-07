package com.towerscope.ar.data

/**
 * How a KML altitude should be interpreted when present in the file.
 * Used for display / LOS; not for AR anchors.
 */
enum class AltitudeMode {
    /** WGS84 meters above ellipsoid (HAE). */
    ABSOLUTE,
    /** Ground level at the tower lat/lng. */
    CLAMP_TO_GROUND,
    /** Meters above ground at the tower lat/lng. */
    RELATIVE_TO_GROUND
}

/**
 * A tower / placemark loaded from KML or KMZ.
 */
data class Tower(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val altitudeMode: AltitudeMode = AltitudeMode.CLAMP_TO_GROUND
)

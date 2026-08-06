package com.towerscope.ar.data

/**
 * How a KML altitude should be interpreted for Geospatial anchors.
 */
enum class AltitudeMode {
    /** WGS84 meters above ellipsoid (HAE) — pass through to Earth.createAnchor. */
    ABSOLUTE,
    /** Pin to Google terrain at the tower lat/lng. */
    CLAMP_TO_GROUND,
    /** Meters above Google terrain at the tower lat/lng. */
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

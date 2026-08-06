package com.towerscope.ar.data

/**
 * How a KML altitude should be interpreted for Geospatial anchors.
 */
enum class AltitudeMode {
    /** WGS84 meters above ellipsoid — pass through to Earth.createAnchor. */
    ABSOLUTE,
    /** Treat as ground-level; use Earth camera altitude. */
    CLAMP_TO_GROUND,
    /** Offset above ground; without terrain, approximate with camera altitude + offset. */
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

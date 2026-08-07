package com.towerscope.ar.util

/**
 * Assumed AP coverage when real sector data is unavailable:
 * four 90° pies facing N / E / S / W (true north).
 */
enum class CardinalSector(
    val shortLabel: String,
    val fullLabel: String,
    /** Center azimuth degrees clockwise from true north. */
    val centerAzimuthDegrees: Double
) {
    NORTH("N", "North", 0.0),
    EAST("E", "East", 90.0),
    SOUTH("S", "South", 180.0),
    WEST("W", "West", 270.0);

    /** Half of the 90° beam. */
    val halfBeamDegrees: Double get() = BEAMWIDTH_DEGREES / 2.0

    val startAzimuthDegrees: Double
        get() = GeoUtils.normalizeBearing(centerAzimuthDegrees - halfBeamDegrees)

    val endAzimuthDegrees: Double
        get() = GeoUtils.normalizeBearing(centerAzimuthDegrees + halfBeamDegrees)

    companion object {
        const val BEAMWIDTH_DEGREES = 90.0

        val ALL: List<CardinalSector> = entries.toList()

        /**
         * Which assumed sector faces a site, given bearing **from tower → site**.
         */
        fun facingSite(bearingFromTowerDegrees: Double): CardinalSector {
            val b = GeoUtils.normalizeBearing(bearingFromTowerDegrees)
            return when {
                b >= 315.0 || b < 45.0 -> NORTH
                b < 135.0 -> EAST
                b < 225.0 -> SOUTH
                else -> WEST
            }
        }
    }
}

package com.towerscope.ar.ui

/**
 * Outdoor HUD appearance. Cycles Day → High contrast → Night.
 */
enum class HudTheme {
    DAY,
    HIGH_CONTRAST,
    NIGHT;

    fun next(): HudTheme = when (this) {
        DAY -> HIGH_CONTRAST
        HIGH_CONTRAST -> NIGHT
        NIGHT -> DAY
    }

    val label: String
        get() = when (this) {
            DAY -> "Day"
            HIGH_CONTRAST -> "HC"
            NIGHT -> "Night"
        }
}

/** Geospatial Earth tracking quality for HUD chips. */
enum class EarthTrackingQuality {
    TRACKING,
    LIMITED,
    NONE
}

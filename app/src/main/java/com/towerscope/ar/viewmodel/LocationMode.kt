package com.towerscope.ar.viewmodel

/**
 * Which observer location to use for distance, bearing, and LOS calculations.
 *
 * Mode is session-only. Every screen starts on [CURRENT_GPS] so a saved pin
 * cannot silently take over after relaunch.
 */
enum class LocationMode {
    /** Live GPS from the device. */
    CURRENT_GPS,
    /** A pinned or entered custom location (install site). */
    CUSTOM;

    companion object {
        fun fromStored(raw: String?): LocationMode {
            return runCatching { valueOf(raw ?: "") }.getOrDefault(CURRENT_GPS)
        }
    }
}

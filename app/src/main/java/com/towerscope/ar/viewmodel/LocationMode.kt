package com.towerscope.ar.viewmodel

/**
 * Which observer location to use for distance, bearing, and LOS calculations.
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

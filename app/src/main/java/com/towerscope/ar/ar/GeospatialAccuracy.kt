package com.towerscope.ar.ar

/**
 * Thresholds for ARCore Geospatial pose quality used when placing tower markers.
 */
object GeospatialAccuracy {
    /**
     * Max Earth horizontal accuracy (meters) for "Ready" / survey-feel markers.
     * Tighter than ARCore's loose tracking so the HUD does not over-promise.
     */
    const val MARKER_HORIZONTAL_METERS = 12f

    /** Max Earth vertical accuracy (meters) required for "Ready". */
    const val MARKER_VERTICAL_METERS = 15f

    /**
     * Still create Geospatial / terrain anchors while Earth is tracking but not yet Ready.
     * Better than compass theater for horizontal placement.
     */
    const val PLACE_HORIZONTAL_METERS = 50f

    /** Recreate an anchor when Earth horizontal accuracy improves by at least this much. */
    const val ANCHOR_REFRESH_IMPROVEMENT_METERS = 3f

    /** Recreate when resolved altitude differs by at least this many meters. */
    const val ALTITUDE_REFRESH_METERS = 1.0

    /** Max heading accuracy (degrees) to trust Geospatial heading over compass. */
    const val HEADING_ACCURACY_DEGREES = 15f

    /** Max fused GPS accuracy (meters) before showing amber GPS-fallback markers. */
    const val GPS_FALLBACK_MAX_ACCURACY_METERS = 15f
}

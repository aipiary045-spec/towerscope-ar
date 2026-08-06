package com.towerscope.ar.ar

/**
 * Thresholds for ARCore Geospatial pose quality used when placing tower markers.
 */
object GeospatialAccuracy {
    /** Max Earth horizontal accuracy (meters) before we place / keep AR markers. */
    const val MARKER_HORIZONTAL_METERS = 15f

    /** Recreate an anchor when Earth horizontal accuracy improves by at least this much. */
    const val ANCHOR_REFRESH_IMPROVEMENT_METERS = 5f

    /** Recreate when resolved altitude differs by at least this many meters. */
    const val ALTITUDE_REFRESH_METERS = 1.0

    /** Max heading accuracy (degrees) to trust Geospatial heading over compass. */
    const val HEADING_ACCURACY_DEGREES = 25f
}

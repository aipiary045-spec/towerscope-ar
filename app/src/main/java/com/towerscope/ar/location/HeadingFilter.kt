package com.towerscope.ar.location

import android.hardware.SensorManager
import com.towerscope.ar.util.CelestialBodies
import com.towerscope.ar.util.GeoUtils

/**
 * Circular heading smoothing and light GPS-course blending.
 */
object HeadingFilter {

    fun smooth(previous: Double?, raw: Double, alpha: Double): Double {
        if (previous == null) return GeoUtils.normalizeBearing(raw)
        val clampedAlpha = alpha.coerceIn(0.05, 1.0)
        val delta = CelestialBodies.signedDeltaDegrees(previous, raw)
        return GeoUtils.normalizeBearing(previous + clampedAlpha * delta)
    }

    /** Blend compass [heading] toward [other] by [otherWeight] in [0, 1]. */
    fun blend(heading: Double, other: Double, otherWeight: Double): Double {
        val weight = otherWeight.coerceIn(0.0, 1.0)
        val delta = CelestialBodies.signedDeltaDegrees(heading, other)
        return GeoUtils.normalizeBearing(heading + weight * delta)
    }

    fun alphaForAccuracy(accuracy: Int): Double = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 0.38
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 0.24
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0.14
        else -> 0.08
    }
}

package com.towerscope.ar.location

import android.hardware.SensorManager
import com.towerscope.ar.util.CelestialBodies
import com.towerscope.ar.util.GeoUtils
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circular heading smoothing for compass display.
 */
object HeadingFilter {

    fun smooth(previous: Double?, raw: Double, alpha: Double): Double {
        if (previous == null) return GeoUtils.normalizeBearing(raw)
        val clampedAlpha = alpha.coerceIn(0.08, 0.55)
        val delta = CelestialBodies.signedDeltaDegrees(previous, raw)
        return GeoUtils.normalizeBearing(previous + clampedAlpha * delta)
    }

    fun alphaForAccuracy(accuracy: Int): Double = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 0.42
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 0.30
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0.20
        else -> 0.12
    }

    fun circularMean(degrees: List<Double>): Double? {
        if (degrees.isEmpty()) return null
        var sinSum = 0.0
        var cosSum = 0.0
        degrees.forEach { deg ->
            val rad = Math.toRadians(deg)
            sinSum += sin(rad)
            cosSum += cos(rad)
        }
        return GeoUtils.normalizeBearing(Math.toDegrees(atan2(sinSum, cosSum)))
    }
}

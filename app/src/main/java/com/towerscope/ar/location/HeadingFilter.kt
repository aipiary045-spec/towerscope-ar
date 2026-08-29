package com.towerscope.ar.location

import android.hardware.SensorManager
import com.towerscope.ar.util.CelestialBodies
import com.towerscope.ar.util.GeoUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circular heading smoothing, GPS blending, and motion-aware filtering.
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

    /**
     * Lower alpha while the phone is rotating quickly so the needle follows turns;
     * higher alpha when still to damp jitter.
     */
    fun alphaForMotion(baseAlpha: Double, rotationRateDps: Double): Double {
        val rate = rotationRateDps.coerceAtLeast(0.0)
        val motionFactor = when {
            rate >= 90.0 -> 0.55
            rate >= 45.0 -> 0.72
            rate >= 20.0 -> 0.88
            rate <= 4.0 -> 1.18
            rate <= 10.0 -> 1.05
            else -> 1.0
        }
        return (baseAlpha * motionFactor).coerceIn(0.06, 0.95)
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

    fun isTilted(pitchDegrees: Double, rollDegrees: Double, maxTiltDegrees: Double = MAX_TILT_DEGREES): Boolean =
        abs(pitchDegrees) > maxTiltDegrees || abs(rollDegrees) > maxTiltDegrees

    const val MAX_TILT_DEGREES = 28.0
}

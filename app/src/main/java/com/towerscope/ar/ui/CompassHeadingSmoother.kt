package com.towerscope.ar.ui

import com.towerscope.ar.util.GeoUtils
import kotlin.math.abs

/**
 * Frame-step heading smoothing for compass views. Avoids restarting animations on
 * every sensor tick while staying responsive during fast pans.
 */
object CompassHeadingSmoother {
    const val HEADING_DEADBAND_DPS = 0.35
    const val STILL_ROTATION_DPS = 7.0

    fun alphaForRotationRate(rotationRateDps: Double): Double = when {
        rotationRateDps > 45.0 -> 0.38
        rotationRateDps > 20.0 -> 0.24
        rotationRateDps > 8.0 -> 0.14
        else -> 0.07
    }

    fun stepToward(current: Double, target: Double, rotationRateDps: Double): Double {
        val delta = GeoUtils.relativeBearingDegrees(current, target)
        if (abs(delta) < HEADING_DEADBAND_DPS && rotationRateDps < STILL_ROTATION_DPS) {
            return current
        }
        val alpha = alphaForRotationRate(rotationRateDps)
        return GeoUtils.normalizeBearing(current + alpha * delta)
    }
}

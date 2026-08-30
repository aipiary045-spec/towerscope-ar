package com.towerscope.ar.location

import kotlin.math.abs

/**
 * Chooses between the fused rotation-vector heading and an independent
 * accelerometer + magnetometer heading.
 *
 * Some devices report a fused rotation vector whose north reference is stale or
 * arbitrary (game-rotation fallback, bad fusion state after reboot, aggressive
 * vendor filtering). The raw magnetometer heading is noisier but always references
 * the real magnetic field, so persistent disagreement means the fused yaw cannot
 * be trusted and the magnetometer heading wins.
 */
class HeadingSourceArbiter(
    private val distrustThresholdDegrees: Double = 25.0,
    private val retrustThresholdDegrees: Double = 10.0,
    private val divergenceAlpha: Double = 0.05
) {
    enum class Source { FUSED, MAGNETOMETER }

    data class Choice(val headingDegrees: Double, val source: Source)

    var smoothedDivergenceDegrees: Double? = null
        private set
    var usingMagnetometer = false
        private set

    fun choose(
        fusedHeadingDegrees: Double?,
        magnetometerHeadingDegrees: Double?,
        fusedHasMagneticReference: Boolean
    ): Choice? {
        if (fusedHeadingDegrees == null) {
            return magnetometerHeadingDegrees?.let { Choice(it, Source.MAGNETOMETER) }
        }
        if (magnetometerHeadingDegrees == null) {
            // Without a magnetic cross-check, a non-magnetic fused yaw is arbitrary — worse
            // than showing no heading at all.
            return if (fusedHasMagneticReference) {
                Choice(fusedHeadingDegrees, Source.FUSED)
            } else {
                null
            }
        }
        if (!fusedHasMagneticReference) {
            return Choice(magnetometerHeadingDegrees, Source.MAGNETOMETER)
        }

        val delta = signedDelta(fusedHeadingDegrees, magnetometerHeadingDegrees)
        val smoothed = smoothSigned(smoothedDivergenceDegrees, delta)
        smoothedDivergenceDegrees = smoothed

        if (usingMagnetometer) {
            if (abs(smoothed) < retrustThresholdDegrees) usingMagnetometer = false
        } else {
            if (abs(smoothed) > distrustThresholdDegrees) usingMagnetometer = true
        }
        return if (usingMagnetometer) {
            Choice(magnetometerHeadingDegrees, Source.MAGNETOMETER)
        } else {
            Choice(fusedHeadingDegrees, Source.FUSED)
        }
    }

    private fun smoothSigned(previous: Double?, next: Double): Double {
        if (previous == null) return next
        var diff = next - previous
        while (diff > 180.0) diff -= 360.0
        while (diff < -180.0) diff += 360.0
        var out = previous + divergenceAlpha * diff
        while (out > 180.0) out -= 360.0
        while (out < -180.0) out += 360.0
        return out
    }

    private fun signedDelta(fromDegrees: Double, toDegrees: Double): Double {
        var delta = toDegrees - fromDegrees
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        return delta
    }
}

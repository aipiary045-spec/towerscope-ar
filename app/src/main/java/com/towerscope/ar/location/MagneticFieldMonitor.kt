package com.towerscope.ar.location

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tracks local geomagnetic field strength and flags likely metal interference.
 */
internal class MagneticFieldMonitor {

    private var baselineUt: Float? = null
    private var sampleCount = 0

    fun observe(microTeslaX: Float, microTeslaY: Float, microTeslaZ: Float): Boolean {
        val magnitude = sqrt(
            microTeslaX * microTeslaX +
                microTeslaY * microTeslaY +
                microTeslaZ * microTeslaZ
        )
        if (!magnitude.isFinite() || magnitude < 5f) return false

        val baseline = baselineUt
        if (baseline == null || sampleCount < WARMUP_SAMPLES) {
            baselineUt = if (baseline == null) {
                magnitude
            } else {
                baseline + (magnitude - baseline) * 0.12f
            }
            sampleCount++
            return false
        }

        val updated = baseline + (magnitude - baseline) * 0.04f
        baselineUt = updated
        sampleCount++

        val deviation = abs(magnitude - updated) / updated.coerceAtLeast(1f)
        return magnitude > HARD_LIMIT_UT || deviation > DEVIATION_RATIO
    }

    companion object {
        private const val WARMUP_SAMPLES = 12
        private const val DEVIATION_RATIO = 0.35f
        private const val HARD_LIMIT_UT = 110f
    }
}

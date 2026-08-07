package com.towerscope.ar.util

import kotlin.math.sqrt

/**
 * First Fresnel zone helpers for WISP / microwave path checks.
 *
 * Radius at distance [d1] from observer along a path of length [pathLength]:
 * r = √(λ · d1 · d2 / D) where d2 = D − d1, λ = c / f.
 */
object Fresnel {
    private const val SPEED_OF_LIGHT_M_S = 299_792_458.0

    /** Wavelength in meters for [frequencyGhz]. */
    fun wavelengthMeters(frequencyGhz: Double): Double {
        val ghz = frequencyGhz.coerceAtLeast(0.1)
        return SPEED_OF_LIGHT_M_S / (ghz * 1_000_000_000.0)
    }

    /**
     * First Fresnel radius (meters) at [distanceFromObserverMeters] along the path.
     */
    fun radiusMeters(
        frequencyGhz: Double,
        pathLengthMeters: Double,
        distanceFromObserverMeters: Double
    ): Double {
        val d = pathLengthMeters.coerceAtLeast(1.0)
        val d1 = distanceFromObserverMeters.coerceIn(0.0, d)
        val d2 = d - d1
        if (d1 <= 0.0 || d2 <= 0.0) return 0.0
        return sqrt(wavelengthMeters(frequencyGhz) * d1 * d2 / d)
    }

    /** Common outdoor WISP bands (GHz). */
    val PRESET_GHZ = listOf(2.4, 3.65, 5.2, 5.8, 6.0, 24.0, 60.0)

    const val DEFAULT_FREQUENCY_GHZ = 5.8
    /** Industry rule of thumb: keep 60% of first Fresnel clear. */
    const val CLEARANCE_FRACTION = 0.6
}

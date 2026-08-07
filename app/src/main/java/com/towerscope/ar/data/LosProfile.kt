package com.towerscope.ar.data

import com.towerscope.ar.util.GeoUtils
import kotlin.math.max

/**
 * One sample along a line-of-sight path (user → tower).
 */
data class LosSample(
    val index: Int,
    val distanceMeters: Double,
    val latitude: Double,
    val longitude: Double,
    /** Orthometric / surface elevation (meters). */
    val groundElevationMeters: Double,
    /** Earth curvature bulge at this distance from the observer (meters). */
    val curvatureDropMeters: Double,
    /** LiDAR first-return vs bare-earth DEM. */
    val source: ElevationSource = ElevationSource.DEM
) {
    /**
     * Terrain height used on the flat chart.
     * Clutter is added only for DEM samples (LiDAR already includes canopy).
     */
    fun effectiveTerrainMeters(clutterHeightMeters: Double): Double {
        val clutter = if (source == ElevationSource.DEM) clutterHeightMeters else 0.0
        return groundElevationMeters + clutter + curvatureDropMeters
    }
}

/**
 * Built LOS elevation profile between observer and a tower.
 */
data class LosProfile(
    val towerId: String,
    val towerName: String,
    val samples: List<LosSample>,
    /** Absolute orthometric elevation of the device camera / eye. */
    val observerEyeElevationMeters: Double,
    /** Absolute orthometric elevation of the tower tip. */
    val towerTipElevationMeters: Double,
    val totalDistanceMeters: Double,
    val sampleCount: Int = samples.size,
    /** Fraction of samples that came from LiDAR (0–1). */
    val lidarCoverageFraction: Double = 0.0
) {
    fun losElevationAt(distanceMeters: Double): Double {
        if (totalDistanceMeters <= 0.0) return observerEyeElevationMeters
        val t = (distanceMeters / totalDistanceMeters).coerceIn(0.0, 1.0)
        return observerEyeElevationMeters +
            t * (towerTipElevationMeters - observerEyeElevationMeters)
    }

    /** Minimum clearance (LOS − effective terrain). Negative = blocked. */
    fun minClearanceMeters(clutterHeightMeters: Double): Double {
        if (samples.isEmpty()) return 0.0
        return samples.minOf { sample ->
            losElevationAt(sample.distanceMeters) -
                sample.effectiveTerrainMeters(clutterHeightMeters)
        }
    }

    /**
     * Sample with the worst clearance (obstruction peak for blocked paths).
     * Null if no samples.
     */
    fun worstClearanceSample(clutterHeightMeters: Double): Pair<LosSample, Double>? {
        if (samples.isEmpty()) return null
        return samples
            .map { sample ->
                sample to (
                    losElevationAt(sample.distanceMeters) -
                        sample.effectiveTerrainMeters(clutterHeightMeters)
                    )
            }
            .minByOrNull { it.second }
    }

    fun isClear(clutterHeightMeters: Double): Boolean =
        minClearanceMeters(clutterHeightMeters) > 0.0

    val usesLidar: Boolean get() = lidarCoverageFraction > 0.0
}

object LosProfileBuilder {

    const val DEFAULT_SAMPLE_COUNT = 50
    const val DEFAULT_TOWER_HEIGHT_METERS = 60.0
    const val DEFAULT_EYE_HEIGHT_METERS = 1.5

    fun build(
        towerId: String,
        towerName: String,
        samples: List<LosSample>,
        observerEyeElevationMeters: Double,
        towerTipElevationMeters: Double,
        lidarCoverageFraction: Double = 0.0
    ): LosProfile {
        val total = samples.lastOrNull()?.distanceMeters ?: 0.0
        return LosProfile(
            towerId = towerId,
            towerName = towerName,
            samples = samples,
            observerEyeElevationMeters = observerEyeElevationMeters,
            towerTipElevationMeters = towerTipElevationMeters,
            totalDistanceMeters = total,
            lidarCoverageFraction = lidarCoverageFraction
        )
    }

    /**
     * Resolve tower tip absolute elevation from ground at the tower and KML altitude.
     */
    fun resolveTowerTipElevationMeters(
        towerGroundElevationMeters: Double,
        altitudeMeters: Double?,
        altitudeMode: AltitudeMode
    ): Double {
        if (altitudeMeters == null) {
            return towerGroundElevationMeters + DEFAULT_TOWER_HEIGHT_METERS
        }
        return when (altitudeMode) {
            AltitudeMode.ABSOLUTE -> max(altitudeMeters, towerGroundElevationMeters)
            AltitudeMode.RELATIVE_TO_GROUND ->
                towerGroundElevationMeters + max(altitudeMeters, 0.0)
            AltitudeMode.CLAMP_TO_GROUND ->
                towerGroundElevationMeters + DEFAULT_TOWER_HEIGHT_METERS
        }
    }
}

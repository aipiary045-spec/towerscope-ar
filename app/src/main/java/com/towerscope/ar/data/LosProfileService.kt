package com.towerscope.ar.data

import com.towerscope.ar.util.GeoUtils

/**
 * Builds a 50-point LOS elevation profile using USGS EPQS + Earth curvature.
 */
class LosProfileService(
    private val elevationService: UsgsElevationService = UsgsElevationService()
) {

    suspend fun buildProfile(
        tower: Tower,
        observerLat: Double,
        observerLon: Double,
        eyeHeightAboveGroundMeters: Double = LosProfileBuilder.DEFAULT_EYE_HEIGHT_METERS,
        sampleCount: Int = LosProfileBuilder.DEFAULT_SAMPLE_COUNT
    ): LosProfile {
        val points = GeoUtils.sampleGeodesic(
            startLat = observerLat,
            startLon = observerLon,
            endLat = tower.latitude,
            endLon = tower.longitude,
            count = sampleCount
        )
        val totalDistance = GeoUtils.haversineMeters(
            observerLat,
            observerLon,
            tower.latitude,
            tower.longitude
        )
        val elevations = elevationService.elevationsMeters(points)
        if (elevations.all { it == null }) {
            error("USGS elevation unavailable (network or outside US coverage)")
        }
        val filled = fillMissingElevations(elevations)

        val samples = points.mapIndexed { index, point ->
            val distance = if (sampleCount <= 1) {
                0.0
            } else {
                totalDistance * index.toDouble() / (sampleCount - 1).toDouble()
            }
            LosSample(
                index = index,
                distanceMeters = distance,
                latitude = point.latitude,
                longitude = point.longitude,
                groundElevationMeters = filled[index],
                curvatureDropMeters = GeoUtils.earthCurvatureDropMeters(distance)
            )
        }

        val observerGround = samples.first().groundElevationMeters
        val towerGround = samples.last().groundElevationMeters
        val observerEye = observerGround + eyeHeightAboveGroundMeters
        val towerTip = LosProfileBuilder.resolveTowerTipElevationMeters(
            towerGroundElevationMeters = towerGround,
            altitudeMeters = tower.altitudeMeters,
            altitudeMode = tower.altitudeMode
        )

        return LosProfileBuilder.build(
            towerId = tower.id,
            towerName = tower.name,
            samples = samples,
            observerEyeElevationMeters = observerEye,
            towerTipElevationMeters = towerTip
        )
    }

    /** Linear interpolate across null USGS failures. */
    internal fun fillMissingElevations(raw: List<Double?>): List<Double> {
        if (raw.isEmpty()) return emptyList()
        val out = MutableList(raw.size) { i -> raw[i] }
        var lastKnown: Double? = null
        for (i in out.indices) {
            val v = out[i]
            if (v != null) {
                lastKnown = v
            } else if (lastKnown != null) {
                out[i] = lastKnown
            }
        }
        lastKnown = null
        for (i in out.indices.reversed()) {
            val v = out[i]
            if (v != null) {
                lastKnown = v
            } else if (lastKnown != null) {
                out[i] = lastKnown
            }
        }
        // Still null (all failed) → sea level placeholder so UI can show an error path.
        return out.map { it ?: 0.0 }
    }
}

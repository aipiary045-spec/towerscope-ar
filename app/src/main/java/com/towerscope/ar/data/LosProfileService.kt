package com.towerscope.ar.data

import com.towerscope.ar.BuildConfig
import com.towerscope.ar.util.GeoUtils

/**
 * Builds a LOS elevation profile via elevation API (LiDAR + DEM)
 * and on-device 3DEP DEM fallback. Always live — no disk cache.
 */
class LosProfileService(
    private val apiClient: LosElevationApiClient? = defaultApiClient(),
    private val demService: DemElevationService = DemElevationService()
) {

    suspend fun buildProfile(
        tower: Tower,
        observerLat: Double,
        observerLon: Double,
        eyeHeightAboveGroundMeters: Double = LosProfileBuilder.DEFAULT_EYE_HEIGHT_METERS,
        sampleCount: Int = LosProfileBuilder.DEFAULT_SAMPLE_COUNT
    ): LosProfile {
        return try {
            if (apiClient != null) {
                buildFromApi(
                    tower = tower,
                    observerLat = observerLat,
                    observerLon = observerLon,
                    eyeHeightAboveGroundMeters = eyeHeightAboveGroundMeters,
                    sampleCount = sampleCount
                )
            } else {
                buildFromDemFallback(
                    tower = tower,
                    observerLat = observerLat,
                    observerLon = observerLon,
                    eyeHeightAboveGroundMeters = eyeHeightAboveGroundMeters,
                    sampleCount = sampleCount
                )
            }
        } catch (apiError: Exception) {
            // Last resort: direct 3DEP DEM from the device.
            runCatching {
                buildFromDemFallback(
                    tower = tower,
                    observerLat = observerLat,
                    observerLon = observerLon,
                    eyeHeightAboveGroundMeters = eyeHeightAboveGroundMeters,
                    sampleCount = sampleCount
                )
            }.getOrElse {
                throw apiError
            }
        }
    }

    private suspend fun buildFromApi(
        tower: Tower,
        observerLat: Double,
        observerLon: Double,
        eyeHeightAboveGroundMeters: Double,
        sampleCount: Int
    ): LosProfile {
        val client = apiClient ?: error("Elevation API not configured")
        val api = client.fetchProfile(
            observerLat = observerLat,
            observerLon = observerLon,
            towerLat = tower.latitude,
            towerLon = tower.longitude,
            sampleCount = sampleCount
        )
        val samples = api.samples.map { s ->
            LosSample(
                index = s.index,
                distanceMeters = s.distanceMeters,
                latitude = s.latitude,
                longitude = s.longitude,
                groundElevationMeters = s.groundElevationMeters,
                curvatureDropMeters = GeoUtils.earthCurvatureDropMeters(s.distanceMeters),
                source = s.source
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
            towerTipElevationMeters = towerTip,
            lidarCoverageFraction = api.lidarCoverageFraction
        )
    }

    private suspend fun buildFromDemFallback(
        tower: Tower,
        observerLat: Double,
        observerLon: Double,
        eyeHeightAboveGroundMeters: Double,
        sampleCount: Int
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
        val elevations = demService.elevationsMeters(points)
        if (elevations.all { it == null }) {
            error("Elevation unavailable (configure LOS_ELEVATION_API_BASE_URL or check network)")
        }
        val filled = fillMissingElevations(elevations)
        rejectDegenerateTerrain(filled, elevations)
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
                curvatureDropMeters = GeoUtils.earthCurvatureDropMeters(distance),
                source = ElevationSource.DEM
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
            towerTipElevationMeters = towerTip,
            lidarCoverageFraction = 0.0
        )
    }

    /** Reject profiles where gap-filling collapsed terrain to a flat line. */
    internal fun rejectDegenerateTerrain(filled: List<Double>, raw: List<Double?>) {
        if (filled.isEmpty() || !raw.any { it == null }) return
        if (filled.distinct().size <= 1) {
            error("Elevation data incomplete along path")
        }
    }

    /** Forward/back fill across null elevation failures. */
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
        return out.map { it ?: 0.0 }
    }

    companion object {
        fun defaultApiClient(): LosElevationApiClient? {
            val base = BuildConfig.LOS_ELEVATION_API_BASE_URL.trim()
            if (base.isEmpty()) return null
            return LosElevationApiClient(base)
        }
    }
}

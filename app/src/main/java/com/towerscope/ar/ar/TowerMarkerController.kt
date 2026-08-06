package com.towerscope.ar.ar

import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.TrackingState
import com.towerscope.ar.data.Tower
import kotlin.math.abs

/**
 * Creates and caches Geospatial [Anchor]s for visible towers.
 * Recreates anchors when Earth localization or altitude resolution improves.
 */
class TowerMarkerController {

    private data class CachedAnchor(
        val anchor: Anchor,
        val altitudeUsed: Double,
        val accuracyAtCreate: Float
    )

    private val anchorsByTowerId = linkedMapOf<String, CachedAnchor>()

        fun syncAnchors(
        earth: Earth?,
        visibleTowers: List<Tower>,
        earthHorizontalAccuracyMeters: Double?
    ): Map<String, Anchor> {
        val accuracy = earthHorizontalAccuracyMeters
        if (
            earth == null ||
            earth.trackingState != TrackingState.TRACKING ||
            accuracy == null ||
            !accuracy.isFinite() ||
            accuracy > GeospatialAccuracy.MARKER_HORIZONTAL_METERS
        ) {
            detachAll()
            return emptyMap()
        }

        val cameraAltitude = earth.cameraGeospatialPose.altitude
        val visibleIds = visibleTowers.map { it.id }.toSet()
        anchorsByTowerId.keys.filter { it !in visibleIds }.forEach { id ->
            anchorsByTowerId.remove(id)?.anchor?.detach()
        }

        visibleTowers.forEach { tower ->
            val altitude = resolveAltitudeMeters(tower, cameraAltitude)
            val existing = anchorsByTowerId[tower.id]
            if (existing != null) {
                val accuracyImproved =
                    existing.accuracyAtCreate - accuracy >=
                        GeospatialAccuracy.ANCHOR_REFRESH_IMPROVEMENT_METERS
                val altitudeChanged =
                    abs(existing.altitudeUsed - altitude) >=
                        GeospatialAccuracy.ALTITUDE_REFRESH_METERS
                if (!accuracyImproved && !altitudeChanged) return@forEach
                existing.anchor.detach()
                anchorsByTowerId.remove(tower.id)
            }

            val anchor = earth.createAnchor(
                tower.latitude,
                tower.longitude,
                altitude,
                0f,
                0f,
                0f,
                1f
            )
            anchorsByTowerId[tower.id] = CachedAnchor(
                anchor = anchor,
                altitudeUsed = altitude,
                accuracyAtCreate = accuracy.toFloat()
            )
        }

        return anchorsByTowerId.mapValues { it.value.anchor }
    }

    fun detachAll() {
        anchorsByTowerId.values.forEach { it.anchor.detach() }
        anchorsByTowerId.clear()
    }

    /**
     * Pin anchors at estimated ground level (camera altitude minus typical phone
     * height) so the visual tower rises from the ground, not from eye height.
     */
    internal fun resolveAltitudeMeters(tower: Tower, cameraAltitudeMeters: Double): Double {
        return cameraAltitudeMeters - DEVICE_HEIGHT_ABOVE_GROUND_METERS
    }

    companion object {
        /** Approximate handset height above terrain while held for outdoor AR. */
        const val DEVICE_HEIGHT_ABOVE_GROUND_METERS = 1.5
    }
}

package com.towerscope.ar.ar

import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.TrackingState
import com.towerscope.ar.data.AltitudeMode
import com.towerscope.ar.data.Tower
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Creates and caches Geospatial / terrain [Anchor]s for visible towers.
 * Prefers [Earth.resolveAnchorOnTerrainAsync] so markers sit on real terrain
 * instead of a fake plane at the camera's altitude.
 */
class TowerMarkerController {

    private data class CachedAnchor(
        val anchor: Anchor,
        /** WGS84 altitude for absolute anchors, or altitude-above-terrain for terrain. */
        val altitudeUsed: Double,
        val accuracyAtCreate: Float,
        val usedTerrain: Boolean
    )

    private val anchorsByTowerId = ConcurrentHashMap<String, CachedAnchor>()
    private val pendingTerrainIds = ConcurrentHashMap.newKeySet<String>()

    fun syncAnchors(
        earth: Earth?,
        visibleTowers: List<Tower>,
        earthHorizontalAccuracyMeters: Double?,
        useKmlAltitude: Boolean = false
    ): Map<String, Anchor> {
        val accuracy = earthHorizontalAccuracyMeters
        if (
            earth == null ||
            earth.trackingState != TrackingState.TRACKING ||
            accuracy == null ||
            !accuracy.isFinite() ||
            accuracy > GeospatialAccuracy.PLACE_HORIZONTAL_METERS
        ) {
            cancelPending()
            detachAll()
            return emptyMap()
        }

        val cameraAltitude = earth.cameraGeospatialPose.altitude
        val visibleIds = visibleTowers.map { it.id }.toSet()
        anchorsByTowerId.keys.filter { it !in visibleIds }.forEach { id ->
            anchorsByTowerId.remove(id)?.anchor?.detach()
            pendingTerrainIds.remove(id)
        }

        visibleTowers.forEach { tower ->
            val preferTerrain = shouldUseTerrain(tower, useKmlAltitude)
            val altitudeKey = if (preferTerrain) {
                terrainAltitudeAboveGround(tower, useKmlAltitude)
            } else {
                resolveAbsoluteAltitudeMeters(tower, cameraAltitude, useKmlAltitude)
            }
            val existing = anchorsByTowerId[tower.id]
            if (existing != null) {
                val accuracyImproved =
                    existing.accuracyAtCreate - accuracy >=
                        GeospatialAccuracy.ANCHOR_REFRESH_IMPROVEMENT_METERS
                val altitudeChanged =
                    abs(existing.altitudeUsed - altitudeKey) >=
                        GeospatialAccuracy.ALTITUDE_REFRESH_METERS
                val modeChanged = existing.usedTerrain != preferTerrain
                if (!accuracyImproved && !altitudeChanged && !modeChanged) return@forEach
                existing.anchor.detach()
                anchorsByTowerId.remove(tower.id)
            }

            if (preferTerrain) {
                if (!pendingTerrainIds.add(tower.id)) return@forEach
                val altAbove = altitudeKey
                try {
                    earth.resolveAnchorOnTerrainAsync(
                        tower.latitude,
                        tower.longitude,
                        altAbove,
                        0f,
                        0f,
                        0f,
                        1f
                    ) { anchor, state ->
                        pendingTerrainIds.remove(tower.id)
                        if (state == Anchor.TerrainAnchorState.SUCCESS && anchor != null) {
                            anchorsByTowerId[tower.id]?.anchor?.detach()
                            anchorsByTowerId[tower.id] = CachedAnchor(
                                anchor = anchor,
                                altitudeUsed = altAbove,
                                accuracyAtCreate = accuracy.toFloat(),
                                usedTerrain = true
                            )
                        } else {
                            // Terrain unavailable — fall back to camera-relative ground stub.
                            placeWgs84Anchor(
                                earth = earth,
                                tower = tower,
                                altitude = cameraAltitude -
                                    DEVICE_HEIGHT_ABOVE_GROUND_METERS + altAbove,
                                accuracy = accuracy.toFloat(),
                                usedTerrain = false
                            )
                        }
                    }
                } catch (_: Exception) {
                    pendingTerrainIds.remove(tower.id)
                    placeWgs84Anchor(
                        earth = earth,
                        tower = tower,
                        altitude = cameraAltitude -
                            DEVICE_HEIGHT_ABOVE_GROUND_METERS + altAbove,
                        accuracy = accuracy.toFloat(),
                        usedTerrain = false
                    )
                }
            } else {
                placeWgs84Anchor(
                    earth = earth,
                    tower = tower,
                    altitude = altitudeKey,
                    accuracy = accuracy.toFloat(),
                    usedTerrain = false
                )
            }
        }

        return anchorsByTowerId.mapValues { it.value.anchor }
    }

    private fun placeWgs84Anchor(
        earth: Earth,
        tower: Tower,
        altitude: Double,
        accuracy: Float,
        usedTerrain: Boolean
    ) {
        val anchor = earth.createAnchor(
            tower.latitude,
            tower.longitude,
            altitude,
            0f,
            0f,
            0f,
            1f
        )
        anchorsByTowerId[tower.id]?.anchor?.detach()
        anchorsByTowerId[tower.id] = CachedAnchor(
            anchor = anchor,
            altitudeUsed = altitude,
            accuracyAtCreate = accuracy,
            usedTerrain = usedTerrain
        )
    }

    private fun cancelPending() {
        pendingTerrainIds.clear()
    }

    fun detachAll() {
        cancelPending()
        anchorsByTowerId.values.forEach { it.anchor.detach() }
        anchorsByTowerId.clear()
    }

    /**
     * Ground / clamp / relative modes use Google's terrain mesh.
     * Absolute KML (HAE) still uses [Earth.createAnchor].
     */
    internal fun shouldUseTerrain(tower: Tower, useKmlAltitude: Boolean): Boolean {
        if (!useKmlAltitude) return true
        return when (tower.altitudeMode) {
            AltitudeMode.ABSOLUTE -> false
            AltitudeMode.CLAMP_TO_GROUND,
            AltitudeMode.RELATIVE_TO_GROUND -> true
        }
    }

    internal fun terrainAltitudeAboveGround(tower: Tower, useKmlAltitude: Boolean): Double {
        if (!useKmlAltitude) return 0.0
        return when (tower.altitudeMode) {
            AltitudeMode.CLAMP_TO_GROUND -> 0.0
            AltitudeMode.RELATIVE_TO_GROUND -> tower.altitudeMeters ?: 0.0
            AltitudeMode.ABSOLUTE -> 0.0
        }
    }

    /**
     * Absolute WGS84 ellipsoid altitude for [Earth.createAnchor].
     * KML absolute is treated as HAE; if missing, fall back to camera ground stub.
     */
    internal fun resolveAbsoluteAltitudeMeters(
        tower: Tower,
        cameraAltitudeMeters: Double,
        useKmlAltitude: Boolean
    ): Double {
        val ground = cameraAltitudeMeters - DEVICE_HEIGHT_ABOVE_GROUND_METERS
        if (!useKmlAltitude) return ground
        val kmlAlt = tower.altitudeMeters
        return if (kmlAlt != null && abs(kmlAlt) > 0.01) kmlAlt else ground
    }

    companion object {
        /** Approximate handset height above terrain while held for outdoor AR. */
        const val DEVICE_HEIGHT_ABOVE_GROUND_METERS = 1.5
    }
}

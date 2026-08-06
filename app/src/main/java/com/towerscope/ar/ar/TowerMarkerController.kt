package com.towerscope.ar.ar

import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.TrackingState
import com.towerscope.ar.data.Tower

/**
 * Creates and caches Geospatial [Anchor]s for visible towers.
 * Detaches anchors when towers leave the distance filter or are hidden.
 */
class TowerMarkerController {

    private val anchorsByTowerId = linkedMapOf<String, Anchor>()

    fun syncAnchors(
        earth: Earth?,
        visibleTowers: List<Tower>,
        fallbackAltitudeMeters: Double?
    ): Map<String, Anchor> {
        if (earth == null || earth.trackingState != TrackingState.TRACKING) {
            detachAll()
            return emptyMap()
        }

        val visibleIds = visibleTowers.map { it.id }.toSet()
        val toRemove = anchorsByTowerId.keys.filter { it !in visibleIds }
        toRemove.forEach { id ->
            anchorsByTowerId.remove(id)?.detach()
        }

        val cameraAltitude = earth.cameraGeospatialPose.altitude
        visibleTowers.forEach { tower ->
            if (anchorsByTowerId.containsKey(tower.id)) return@forEach
            val altitude = tower.altitudeMeters
                ?: fallbackAltitudeMeters
                ?: (cameraAltitude - 1.0)
            val anchor = earth.createAnchor(
                tower.latitude,
                tower.longitude,
                altitude,
                0f,
                0f,
                0f,
                1f
            )
            anchorsByTowerId[tower.id] = anchor
        }
        return anchorsByTowerId.toMap()
    }

    fun detachAll() {
        anchorsByTowerId.values.forEach { it.detach() }
        anchorsByTowerId.clear()
    }
}

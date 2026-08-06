package com.towerscope.ar.ui

import android.content.Context
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.towerscope.ar.ar.GeospatialAccuracy
import com.towerscope.ar.ar.TowerMarkerController
import com.towerscope.ar.data.Tower
import com.towerscope.ar.viewmodel.EarthCameraPose
import com.towerscope.ar.viewmodel.TowerUiState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import java.util.concurrent.ConcurrentHashMap

/**
 * Hosts SceneView [ARSceneView] and keeps Geospatial tower markers in sync.
 */
class TowerArSceneBinding(
    context: Context,
    private val markerController: TowerMarkerController = TowerMarkerController()
) {
    private val markerNodes = ConcurrentHashMap<String, AnchorNode>()
    private var visibleTowers: List<Tower> = emptyList()
    private var towersById: Map<String, Tower> = emptyMap()
    private var onEarthTrackingQualityChanged: (EarthTrackingQuality) -> Unit = {}
    private var onEarthCameraPoseChanged: (EarthCameraPose?) -> Unit = {}
    private var onCameraHeadingChanged: (Double?) -> Unit = {}
    private var onTowerTapped: (Tower) -> Unit = {}

    val view: ARSceneView = ARSceneView(context).apply {
        planeRenderer.isEnabled = false
        configureSession { _: Session, config: Config ->
            config.geospatialMode = Config.GeospatialMode.ENABLED
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        }
        onSessionUpdated = { session: Session, _: Frame ->
            sync(session)
        }
    }

    fun update(
        uiState: TowerUiState,
        onEarthTrackingQualityChanged: (EarthTrackingQuality) -> Unit,
        onEarthCameraPoseChanged: (EarthCameraPose?) -> Unit,
        onCameraHeadingChanged: (Double?) -> Unit,
        onTowerTapped: (Tower) -> Unit
    ) {
        this.visibleTowers = uiState.visibleTowers()
        this.towersById = uiState.towers.associateBy { it.id }
        this.onEarthTrackingQualityChanged = onEarthTrackingQualityChanged
        this.onEarthCameraPoseChanged = onEarthCameraPoseChanged
        this.onCameraHeadingChanged = onCameraHeadingChanged
        this.onTowerTapped = onTowerTapped
    }

    private fun sync(session: Session) {
        val earth = session.earth
        val tracking = earth?.trackingState == TrackingState.TRACKING
        val pose = if (tracking) {
            try {
                val geo = earth!!.cameraGeospatialPose
                EarthCameraPose(
                    latitude = geo.latitude,
                    longitude = geo.longitude,
                    altitudeMeters = geo.altitude,
                    headingDegrees = geo.heading,
                    horizontalAccuracyMeters = geo.horizontalAccuracy,
                    headingAccuracyDegrees = geo.headingAccuracy
                )
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val quality = when {
            !tracking || pose == null -> EarthTrackingQuality.NONE
            pose.horizontalAccuracyMeters <= GeospatialAccuracy.MARKER_HORIZONTAL_METERS ->
                EarthTrackingQuality.TRACKING
            else -> EarthTrackingQuality.LIMITED
        }
        onEarthTrackingQualityChanged(quality)
        onEarthCameraPoseChanged(pose)

        val headingTrusted = pose != null &&
            quality == EarthTrackingQuality.TRACKING &&
            pose.headingAccuracyDegrees.isFinite() &&
            pose.headingAccuracyDegrees <= GeospatialAccuracy.HEADING_ACCURACY_DEGREES
        onCameraHeadingChanged(if (headingTrusted) pose!!.headingDegrees else null)

        val synced = markerController.syncAnchors(
            earth = earth,
            visibleTowers = visibleTowers,
            earthHorizontalAccuracyMeters = pose?.horizontalAccuracyMeters
        )

        markerNodes.keys.filter { id ->
            val expected = synced[id]
            val node = markerNodes[id] ?: return@filter true
            expected == null || node.anchor !== expected
        }.forEach { id ->
            markerNodes.remove(id)?.let { node ->
                view.childNodes -= node
                node.destroy()
            }
        }

        synced.forEach { (towerId, anchor) ->
            if (markerNodes.containsKey(towerId)) return@forEach
            val tower = towersById[towerId] ?: return@forEach

            val anchorNode = AnchorNode(engine = view.engine, anchor = anchor).apply {
                onSingleTapConfirmed = { _ ->
                    onTowerTapped(tower)
                    true
                }
            }

            anchorNode.addChildNode(
                CubeNode(
                    engine = view.engine,
                    // Compact ground marker — towers are treated as zero height.
                    size = Size(0.8f, 0.8f, 0.8f),
                    center = Position(y = 0f)
                )
            )

            view.addChildNode(anchorNode)
            markerNodes[towerId] = anchorNode
        }
    }

    fun destroy() {
        markerNodes.values.forEach { it.destroy() }
        markerNodes.clear()
        markerController.detachAll()
        view.destroy()
    }
}

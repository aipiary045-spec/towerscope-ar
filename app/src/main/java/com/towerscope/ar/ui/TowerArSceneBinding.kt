package com.towerscope.ar.ui

import android.content.Context
import com.google.android.filament.MaterialInstance
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
import io.github.sceneview.node.CylinderNode
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
    private var towerMaterial: MaterialInstance? = null

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

    private fun towerMaterialInstance(): MaterialInstance {
        towerMaterial?.let { return it }
        // Bright yellow, low metal — readable outdoors against sky/trees.
        return view.materialLoader.createColorInstance(
            color = 0xFFFFD60A.toInt(),
            metallic = 0.05f,
            roughness = 0.35f,
            reflectance = 0.45f
        ).also { towerMaterial = it }
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

            // Ground-pinned cylinder: base at anchor (ground), rises upward.
            val height = TOWER_MARKER_HEIGHT_METERS
            anchorNode.addChildNode(
                CylinderNode(
                    engine = view.engine,
                    radius = TOWER_MARKER_RADIUS_METERS,
                    height = height,
                    center = Position(y = height / 2f),
                    materialInstance = towerMaterialInstance()
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
        towerMaterial?.let { view.materialLoader.destroyMaterialInstance(it) }
        towerMaterial = null
        view.destroy()
    }

    companion object {
        /** Tall enough to read as a tower; thick enough to see from hundreds of meters. */
        private const val TOWER_MARKER_HEIGHT_METERS = 55f
        private const val TOWER_MARKER_RADIUS_METERS = 4f
    }
}

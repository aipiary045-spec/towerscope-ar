package com.towerscope.ar.ui

import android.content.Context
import android.widget.TextView
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import com.towerscope.ar.R
import com.towerscope.ar.ar.GeospatialAccuracy
import com.towerscope.ar.ar.TowerMarkerController
import com.towerscope.ar.data.Tower
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.EarthCameraPose
import com.towerscope.ar.viewmodel.TowerUiState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hosts SceneView [ARSceneView], Geospatial anchors when Earth is ready,
 * and GPS+compass camera-relative fallback markers otherwise.
 */
class TowerArSceneBinding(
    private val context: Context,
    private val markerController: TowerMarkerController = TowerMarkerController()
) {
    private data class GeoMarker(
        val anchorNode: AnchorNode,
        var nameView: TextView? = null,
        var distanceView: TextView? = null
    )

    private data class GpsMarker(
        val root: Node,
        var nameView: TextView? = null,
        var distanceView: TextView? = null
    )

    private val geoMarkers = ConcurrentHashMap<String, GeoMarker>()
    private val gpsMarkers = ConcurrentHashMap<String, GpsMarker>()
    private var visibleTowers: List<Tower> = emptyList()
    private var towersById: Map<String, Tower> = emptyMap()
    private var uiState: TowerUiState = TowerUiState()
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

    private val viewAttachmentManager = ViewAttachmentManager(context, view)

    fun onResume() {
        viewAttachmentManager.onResume()
    }

    fun onPause() {
        viewAttachmentManager.onPause()
    }

    fun update(
        uiState: TowerUiState,
        onEarthTrackingQualityChanged: (EarthTrackingQuality) -> Unit,
        onEarthCameraPoseChanged: (EarthCameraPose?) -> Unit,
        onCameraHeadingChanged: (Double?) -> Unit,
        onTowerTapped: (Tower) -> Unit
    ) {
        this.uiState = uiState
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
                    verticalAccuracyMeters = geo.verticalAccuracy,
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
            pose.isAccurateForMarkers -> EarthTrackingQuality.TRACKING
            pose.isGoodEnoughToPlace -> EarthTrackingQuality.LIMITED
            else -> EarthTrackingQuality.NONE
        }
        onEarthTrackingQualityChanged(quality)
        onEarthCameraPoseChanged(pose)

        // Trust Geospatial heading whenever Earth reports a tight yaw — even before Ready.
        val headingTrusted = pose != null &&
            pose.isGoodEnoughToPlace &&
            pose.headingAccuracyDegrees.isFinite() &&
            pose.headingAccuracyDegrees <= GeospatialAccuracy.HEADING_ACCURACY_DEGREES
        onCameraHeadingChanged(if (headingTrusted) pose!!.headingDegrees else null)

        // Prefer Geospatial/terrain anchors whenever Earth can place — never demote a
        // 30–50 m Earth solution to compass theater.
        if (pose != null && pose.isGoodEnoughToPlace) {
            clearGpsMarkers()
            syncGeospatialMarkers(earth, pose.horizontalAccuracyMeters)
        } else {
            clearGeoMarkers()
            syncGpsFallbackMarkers()
        }
    }

    private fun syncGeospatialMarkers(earth: com.google.ar.core.Earth?, accuracy: Double?) {
        val synced = markerController.syncAnchors(
            earth = earth,
            visibleTowers = visibleTowers,
            earthHorizontalAccuracyMeters = accuracy,
            useKmlAltitude = uiState.useKmlAltitude
        )

        geoMarkers.keys.filter { id ->
            val expected = synced[id]
            val marker = geoMarkers[id] ?: return@filter true
            expected == null || marker.anchorNode.anchor !== expected
        }.forEach { id ->
            geoMarkers.remove(id)?.anchorNode?.let { node ->
                view.childNodes -= node
                node.destroy()
            }
        }

        synced.forEach { (towerId, anchor) ->
            val existing = geoMarkers[towerId]
            if (existing != null) {
                updateLabelText(existing.nameView, existing.distanceView, towerId)
                return@forEach
            }
            val tower = towersById[towerId] ?: return@forEach
            val anchorNode = AnchorNode(engine = view.engine, anchor = anchor).apply {
                onSingleTapConfirmed = { _ ->
                    onTowerTapped(tower)
                    true
                }
            }
            val marker = GeoMarker(anchorNode = anchorNode)
            attachLabel(anchorNode) { nameView, distanceView ->
                marker.nameView = nameView
                marker.distanceView = distanceView
                updateLabelText(nameView, distanceView, towerId)
            }
            view.addChildNode(anchorNode)
            geoMarkers[towerId] = marker
        }
    }

    private fun syncGpsFallbackMarkers() {
        val heading = uiState.effectiveHeadingDegrees()
        val location = uiState.userLocation
        val gpsOk = location != null &&
            location.accuracyMeters.isFinite() &&
            location.accuracyMeters <= GeospatialAccuracy.GPS_FALLBACK_MAX_ACCURACY_METERS
        val compassOk = !uiState.needsCompassCalibration &&
            uiState.compassSensorAccuracy >= android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        // GPS path is camera-relative theater — only show when GPS + compass are trustworthy.
        if (heading == null || !gpsOk || !compassOk) {
            clearGpsMarkers()
            return
        }

        val targets = visibleTowers
            .mapNotNull { tower ->
                val distance = uiState.distanceTo(tower) ?: return@mapNotNull null
                val bearing = uiState.bearingTo(tower) ?: return@mapNotNull null
                Triple(tower, distance, bearing)
            }
            .sortedBy { it.second }
            .take(MAX_GPS_FALLBACK_MARKERS)

        val keepIds = targets.map { it.first.id }.toSet()
        gpsMarkers.keys.filter { it !in keepIds }.forEach { id ->
            gpsMarkers.remove(id)?.root?.let { node ->
                view.cameraNode.childNodes -= node
                node.destroy()
            }
        }

        targets.forEach { (tower, distance, bearing) ->
            val relative = GeoUtils.relativeBearingDegrees(heading, bearing)
            val rad = Math.toRadians(relative)
            val x = (sin(rad) * distance).toFloat()
            val z = (-cos(rad) * distance).toFloat()
            val groundY = -TowerMarkerController.DEVICE_HEIGHT_ABOVE_GROUND_METERS.toFloat()

            val marker = gpsMarkers[tower.id]
            if (marker != null) {
                marker.root.position = Position(x = x, y = groundY, z = z)
                updateLabelText(marker.nameView, marker.distanceView, tower.id)
                return@forEach
            }

            val root = Node(view.engine).apply {
                position = Position(x = x, y = groundY, z = z)
                onSingleTapConfirmed = { _ ->
                    onTowerTapped(tower)
                    true
                }
            }
            val created = GpsMarker(root = root)
            attachLabel(root) { nameView, distanceView ->
                created.nameView = nameView
                created.distanceView = distanceView
                updateLabelText(nameView, distanceView, tower.id)
            }
            view.cameraNode.addChildNode(root)
            gpsMarkers[tower.id] = created
        }
    }

    private fun attachLabel(
        parent: Node,
        onReady: (TextView, TextView) -> Unit
    ) {
        val labelNode = ViewNode(view.engine, view.modelLoader, viewAttachmentManager)
        labelNode.position = Position(y = LABEL_HEIGHT_METERS)
        // Flip Y so the Android view faces the camera correctly in Filament.
        labelNode.scale = Scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE)
        labelNode.loadView(
            context = context,
            layoutResId = R.layout.ar_tower_label,
            onLoaded = { _, loadedView ->
                val nameView = loadedView.findViewById<TextView>(R.id.arLabelName)
                val distanceView = loadedView.findViewById<TextView>(R.id.arLabelDistance)
                onReady(nameView, distanceView)
                parent.addChildNode(labelNode)
            }
        )
    }

    private fun updateLabelText(nameView: TextView?, distanceView: TextView?, towerId: String) {
        val tower = towersById[towerId] ?: return
        nameView?.text = tower.name
        val distance = uiState.distanceTo(tower)
        distanceView?.text = if (distance != null) {
            GeoUtils.formatDistance(distance)
        } else {
            "—"
        }
    }

    private fun clearGeoMarkers() {
        geoMarkers.values.forEach { marker ->
            view.childNodes -= marker.anchorNode
            marker.anchorNode.destroy()
        }
        geoMarkers.clear()
        markerController.detachAll()
    }

    private fun clearGpsMarkers() {
        gpsMarkers.values.forEach { marker ->
            view.cameraNode.childNodes -= marker.root
            marker.root.destroy()
        }
        gpsMarkers.clear()
    }

    fun destroy() {
        clearGeoMarkers()
        clearGpsMarkers()
        view.destroy()
    }

    companion object {
        /** Float name/distance labels just above ground — no 3D column mesh. */
        private const val LABEL_HEIGHT_METERS = 2.5f
        private const val LABEL_SCALE = 1.2f
        private const val MAX_GPS_FALLBACK_MARKERS = 8
    }
}

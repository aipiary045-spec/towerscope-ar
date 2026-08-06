package com.towerscope.ar.ui

import android.content.Context
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.towerscope.ar.ar.TowerMarkerController
import com.towerscope.ar.data.Tower
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
    private var fallbackAltitude: Double? = null
    private var onEarthTrackingChanged: (Boolean) -> Unit = {}
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
        onEarthTrackingChanged: (Boolean) -> Unit,
        onTowerTapped: (Tower) -> Unit
    ) {
        this.visibleTowers = uiState.visibleTowers()
        this.towersById = uiState.towers.associateBy { it.id }
        this.fallbackAltitude = uiState.userLocation?.altitudeMeters
        this.onEarthTrackingChanged = onEarthTrackingChanged
        this.onTowerTapped = onTowerTapped
    }

    private fun sync(session: Session) {
        val earth = session.earth
        val tracking = earth?.trackingState == TrackingState.TRACKING
        onEarthTrackingChanged(tracking)

        val synced = markerController.syncAnchors(earth, visibleTowers, fallbackAltitude)

        markerNodes.keys.filter { it !in synced.keys }.forEach { id ->
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
                    size = Size(0.6f, 2.4f, 0.6f),
                    center = Position(y = 1.2f)
                )
            )

            // Tower name is shown on the outdoor HUD chips; cube marks the geospatial anchor.
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

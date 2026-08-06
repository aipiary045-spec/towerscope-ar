package com.towerscope.ar.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import com.towerscope.ar.ar.TowerMarkerController
import com.towerscope.ar.data.Tower
import com.towerscope.ar.ui.theme.AccentCyan
import com.towerscope.ar.ui.theme.AccentYellow
import com.towerscope.ar.ui.theme.DangerRed
import com.towerscope.ar.ui.theme.HudNavy
import com.towerscope.ar.ui.theme.SuccessGreen
import com.towerscope.ar.ui.theme.TextPrimary
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.gesture.rememberOnGestureListener
import io.github.sceneview.math.Position
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.TextNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

@Composable
fun ArScreen(
    viewModel: TowerScopeViewModel,
    onOpenFilePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val markerController = remember { TowerMarkerController() }
    val anchors = remember { mutableStateMapOf<String, Anchor>() }
    var selectedTower by remember { mutableStateOf<Tower?>(null) }

    DisposableEffect(Unit) {
        onDispose { markerController.detachAll() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        TowerArScene(
            uiState = uiState,
            anchors = anchors,
            markerController = markerController,
            onEarthTrackingChanged = viewModel::setEarthTracking,
            onTowerTapped = { selectedTower = it }
        )

        OutdoorHud(
            uiState = uiState,
            onDistanceChanged = viewModel::setMaxDistanceMeters,
            onOpenFilePicker = onOpenFilePicker,
            onLoadSample = viewModel::loadSampleTowers,
            onClearHidden = viewModel::clearHiddenTowers,
            onDismissMessage = viewModel::clearMessages,
            onSelectTower = { selectedTower = it }
        )

        selectedTower?.let { tower ->
            AlertDialog(
                onDismissRequest = { selectedTower = null },
                title = {
                    Text(
                        text = tower.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                text = {
                    val distance = uiState.distanceTo(tower)
                    Text(
                        text = buildString {
                            append("Filter this tower out of the AR scene?")
                            if (distance != null) {
                                append("\n\nDistance: ${GeoUtils.formatDistance(distance)}")
                            }
                        },
                        fontSize = 16.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.hideTower(tower.id)
                            selectedTower = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DangerRed,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("Hide tower", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedTower = null }) {
                        Text("Cancel", fontWeight = FontWeight.Bold, color = AccentYellow)
                    }
                },
                containerColor = HudNavy,
                titleContentColor = TextPrimary,
                textContentColor = TextPrimary
            )
        }
    }
}

@Composable
private fun TowerArScene(
    uiState: TowerUiState,
    anchors: MutableMap<String, Anchor>,
    markerController: TowerMarkerController,
    onEarthTrackingChanged: (Boolean) -> Unit,
    onTowerTapped: (Tower) -> Unit
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val markerMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(AndroidColor.parseColor("#FFD60A"))
    }
    val visibleTowers = remember(uiState) { uiState.visibleTowers() }
    val towersById = remember(uiState.towers) { uiState.towers.associateBy { it.id } }
    val visibleTowersUpdated = rememberUpdatedState(visibleTowers)
    val altitudeUpdated = rememberUpdatedState(uiState.userLocation?.altitudeMeters)
    val towersByIdUpdated = rememberUpdatedState(towersById)
    val hiddenUpdated = rememberUpdatedState(uiState.hiddenTowerIds)
    val trackingCallback = rememberUpdatedState(onEarthTrackingChanged)
    val tapCallback = rememberUpdatedState(onTowerTapped)

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        planeRenderer = false,
        geospatialMode = Config.GeospatialMode.ENABLED,
        onSessionUpdated = { session, _ ->
            val earth = session.earth
            val tracking = earth?.trackingState == TrackingState.TRACKING
            trackingCallback.value(tracking)
            val synced = markerController.syncAnchors(
                earth = earth,
                visibleTowers = visibleTowersUpdated.value,
                fallbackAltitudeMeters = altitudeUpdated.value
            )
            if (anchors.keys != synced.keys) {
                anchors.keys.retainAll(synced.keys)
                synced.forEach { (id, anchor) -> anchors[id] = anchor }
            }
        },
        onGestureListener = rememberOnGestureListener(
            onSingleTapUp = { _, node ->
                val towerId = node?.name?.takeIf { it.isNotBlank() }
                    ?: node?.parent?.name?.takeIf { it.isNotBlank() }
                val tower = towerId?.let { towersByIdUpdated.value[it] }
                if (tower != null && tower.id !in hiddenUpdated.value) {
                    tapCallback.value(tower)
                }
            }
        )
    ) {
        anchors.forEach { (towerId, anchor) ->
            val tower = towersById[towerId] ?: return@forEach
            AnchorNode(anchor = anchor) {
                CylinderNode(
                    radius = 0.35f,
                    height = 2.4f,
                    materialInstance = markerMaterial,
                    position = Position(y = 1.2f),
                    apply = {
                        name = tower.id
                    }
                )
                TextNode(
                    text = tower.name,
                    fontSize = 64f,
                    textColor = AndroidColor.WHITE,
                    backgroundColor = AndroidColor.parseColor("#CC0B1C2C"),
                    widthMeters = 2.4f,
                    heightMeters = 0.7f,
                    position = Position(y = 3.0f),
                    apply = {
                        name = tower.id
                    }
                )
            }
        }
    }
}

@Composable
private fun OutdoorHud(
    uiState: TowerUiState,
    onDistanceChanged: (Float) -> Unit,
    onOpenFilePicker: () -> Unit,
    onLoadSample: () -> Unit,
    onClearHidden: () -> Unit,
    onDismissMessage: () -> Unit,
    onSelectTower: (Tower) -> Unit
) {
    val visible = uiState.visibleTowers()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .background(HudNavy, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TowerScope AR",
                color = AccentYellow,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                label = gpsLabel(uiState),
                color = if ((uiState.userLocation?.accuracyMeters ?: Float.MAX_VALUE) <= 20f) {
                    SuccessGreen
                } else {
                    AccentCyan
                }
            )
            StatusChip(
                label = if (uiState.earthTracking) "EARTH OK" else "EARTH…",
                color = if (uiState.earthTracking) SuccessGreen else AccentYellow
            )
        }

        uiState.errorMessage?.let { message ->
            MessageBanner(
                text = message,
                color = DangerRed,
                onDismiss = onDismissMessage
            )
        }
        uiState.statusMessage?.let { message ->
            MessageBanner(
                text = message,
                color = AccentCyan,
                onDismiss = onDismissMessage
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .background(HudNavy, RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "Visible ${visible.size} / ${uiState.towers.size}" +
                    if (uiState.hiddenTowerIds.isNotEmpty()) {
                        "  ·  ${uiState.hiddenTowerIds.size} hidden"
                    } else {
                        ""
                    },
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Max distance  ${GeoUtils.formatDistance(uiState.maxDistanceMeters.toDouble())}",
                color = AccentYellow,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            Slider(
                value = uiState.maxDistanceMeters,
                onValueChange = onDistanceChanged,
                valueRange = TowerUiState.MIN_DISTANCE_METERS..TowerUiState.MAX_DISTANCE_METERS,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = SliderDefaults.colors(
                    thumbColor = AccentYellow,
                    activeTrackColor = AccentYellow,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOpenFilePicker,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentYellow,
                        contentColor = Color(0xFF0B1C2C)
                    )
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Load KML", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Button(
                    onClick = onLoadSample,
                    modifier = Modifier.height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = Color(0xFF0B1C2C)
                    )
                ) {
                    Text("Sample", fontWeight = FontWeight.Bold)
                }
                if (uiState.hiddenTowerIds.isNotEmpty()) {
                    IconButton(onClick = onClearHidden) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Show hidden towers",
                            tint = AccentYellow
                        )
                    }
                }
            }

            if (visible.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Tap a marker to hide it, or pick one:",
                    color = TextPrimary.copy(alpha = 0.9f),
                    fontSize = 13.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    visible.take(3).forEach { tower ->
                        TextButton(onClick = { onSelectTower(tower) }) {
                            Text(
                                text = tower.name,
                                color = AccentYellow,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Text(
        text = label,
        color = Color(0xFF0B1C2C),
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier
            .background(color, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun MessageBanner(
    text: String,
    color: Color,
    onDismiss: () -> Unit
) {
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(4_000)
        onDismiss()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(HudNavy, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextPrimary)
        }
    }
}

private fun gpsLabel(uiState: TowerUiState): String {
    val accuracy = uiState.userLocation?.accuracyMeters ?: return "GPS…"
    return if (accuracy.isFinite()) {
        "GPS ±${accuracy.toInt()}m"
    } else {
        "GPS…"
    }
}

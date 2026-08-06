package com.towerscope.ar

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.towerscope.ar.ui.TowerArSceneBinding
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private var arBinding: TowerArSceneBinding? = null

    private lateinit var permissionGate: View
    private lateinit var gpsChip: TextView
    private lateinit var earthChip: TextView
    private lateinit var messageBanner: TextView
    private lateinit var visibleCount: TextView
    private lateinit var distanceLabel: TextView
    private lateinit var distanceSlider: SeekBar
    private lateinit var showHiddenButton: Button
    private lateinit var towerChips: LinearLayout
    private lateinit var arContainer: FrameLayout

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraOk = result[Manifest.permission.CAMERA] == true
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (cameraOk && (fineOk || coarseOk)) {
            onPermissionsGranted()
        } else {
            permissionGate.isVisible = true
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let(viewModel::loadTowersFromUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]
        bindViews()
        wireActions()
        observeState()
        ensurePermissions()
    }

    private fun bindViews() {
        permissionGate = findViewById(R.id.permissionGate)
        gpsChip = findViewById(R.id.gpsChip)
        earthChip = findViewById(R.id.earthChip)
        messageBanner = findViewById(R.id.messageBanner)
        visibleCount = findViewById(R.id.visibleCount)
        distanceLabel = findViewById(R.id.distanceLabel)
        distanceSlider = findViewById(R.id.distanceSlider)
        showHiddenButton = findViewById(R.id.showHiddenButton)
        towerChips = findViewById(R.id.towerChips)
        arContainer = findViewById(R.id.arContainer)

        distanceSlider.max = (TowerUiState.MAX_DISTANCE_METERS - TowerUiState.MIN_DISTANCE_METERS).toInt()
        distanceSlider.progress =
            (TowerUiState.DEFAULT_MAX_DISTANCE_METERS - TowerUiState.MIN_DISTANCE_METERS).toInt()
    }

    private fun wireActions() {
        findViewById<Button>(R.id.grantPermissionsButton).setOnClickListener { requestPermissions() }
        findViewById<Button>(R.id.loadKmlButton).setOnClickListener {
            filePickerLauncher.launch(
                arrayOf(
                    "application/vnd.google-earth.kml+xml",
                    "application/vnd.google-earth.kmz",
                    "application/xml",
                    "text/xml",
                    "application/zip",
                    "*/*"
                )
            )
        }
        findViewById<Button>(R.id.sampleButton).setOnClickListener { viewModel.loadSampleTowers() }
        showHiddenButton.setOnClickListener { viewModel.clearHiddenTowers() }
        distanceSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val meters = TowerUiState.MIN_DISTANCE_METERS + progress
                viewModel.setMaxDistanceMeters(meters)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: TowerUiState) {
        val visible = state.visibleTowers()
        val accuracy = state.userLocation?.accuracyMeters
        gpsChip.text = if (accuracy != null && accuracy.isFinite()) {
            "GPS ±${accuracy.toInt()}m"
        } else {
            "GPS…"
        }
        earthChip.text = if (state.earthTracking) "EARTH OK" else "EARTH…"
        earthChip.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (state.earthTracking) android.R.color.holo_green_light else android.R.color.holo_orange_light
            )
        )
        visibleCount.text = buildString {
            append("Visible ${visible.size} / ${state.towers.size}")
            if (state.hiddenTowerIds.isNotEmpty()) {
                append("  ·  ${state.hiddenTowerIds.size} hidden")
            }
        }
        distanceLabel.text =
            "Max distance  ${GeoUtils.formatDistance(state.maxDistanceMeters.toDouble())}"
        showHiddenButton.isVisible = state.hiddenTowerIds.isNotEmpty()

        val message = state.errorMessage ?: state.statusMessage
        messageBanner.isVisible = message != null
        messageBanner.text = message.orEmpty()
        messageBanner.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.errorMessage != null) android.R.color.holo_red_light
                else android.R.color.holo_blue_light
            )
        )

        towerChips.removeAllViews()
        visible.take(3).forEach { tower ->
            val chip = Button(this).apply {
                text = tower.name
                setTextColor(0xFFFFD60A.toInt())
                setBackgroundColor(0x00000000)
                setOnClickListener { confirmHide(tower.id, tower.name, state.distanceTo(tower)) }
            }
            towerChips.addView(chip)
        }

        arBinding?.update(
            uiState = state,
            onEarthTrackingChanged = viewModel::setEarthTracking,
            onTowerTapped = { tower ->
                confirmHide(tower.id, tower.name, state.distanceTo(tower))
            }
        )
    }

    private fun confirmHide(towerId: String, name: String, distance: Double?) {
        val distanceText = distance?.let { "\n\nDistance: ${GeoUtils.formatDistance(it)}" }.orEmpty()
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Filter this tower out of the AR scene?$distanceText")
            .setPositiveButton("Hide tower") { _, _ -> viewModel.hideTower(towerId) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensurePermissions() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && (fineGranted || coarseGranted)) {
            onPermissionsGranted()
        } else {
            permissionGate.isVisible = true
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun onPermissionsGranted() {
        permissionGate.isVisible = false
        viewModel.startLocationUpdates()
        if (arBinding == null) {
            val binding = TowerArSceneBinding(this)
            arBinding = binding
            arContainer.removeAllViews()
            arContainer.addView(binding.view)
        }
    }

    override fun onDestroy() {
        arBinding?.destroy()
        arBinding = null
        super.onDestroy()
    }
}

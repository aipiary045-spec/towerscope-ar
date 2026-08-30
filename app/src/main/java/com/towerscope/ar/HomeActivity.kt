package com.towerscope.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.towerscope.ar.ui.BottomNav
import com.towerscope.ar.ui.BottomNavTab
import com.towerscope.ar.ui.LocationSourceChip
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.util.DisplayUnits
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.util.UnitFormat
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch

/**
 * WispEaze field dashboard: live site/GPS status and one-tap access to core tools.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel

    private lateinit var sitesCount: TextView
    private lateinit var inRangeCount: TextView
    private lateinit var locationChip: TextView
    private lateinit var nearestLabel: TextView
    private lateinit var nearestDetail: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var importButton: View

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOk || coarseOk) {
            viewModel.startLocationUpdates(includeHeading = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        SystemBars.apply(
            root = findViewById(R.id.homeRoot),
            alsoBottom = findViewById(R.id.homeBottomNav)
        )

        bindViews()
        wireActions()
        observeState()
        BottomNav.bind(this, BottomNavTab.HOME)
        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates(includeHeading = false)
        }
    }

    override fun onPause() {
        viewModel.stopLocationUpdates()
        super.onPause()
    }

    private fun bindViews() {
        sitesCount = findViewById(R.id.homeSitesCount)
        inRangeCount = findViewById(R.id.homeInRangeCount)
        locationChip = findViewById(R.id.homeLocationChip)
        nearestLabel = findViewById(R.id.homeNearestLabel)
        nearestDetail = findViewById(R.id.homeNearestDetail)
        gpsStatus = findViewById(R.id.homeGpsStatus)
        importButton = findViewById(R.id.homeImportButton)
    }

    private fun wireActions() {
        LocationSourceChip(
            chip = locationChip,
            fragmentManager = supportFragmentManager,
            viewModel = viewModel
        )

        importButton.setOnClickListener {
            startActivity(Intent(this, DataMenuActivity::class.java))
        }

        bindQuickAction(
            rowId = R.id.homeQuickCompass,
            icon = R.drawable.ic_compass_rose,
            iconTint = R.color.accent_yellow,
            title = R.string.home_job_aim,
            subtitle = R.string.home_job_aim_sub
        ) { startActivity(Intent(this, MainActivity::class.java)) }

        bindQuickAction(
            rowId = R.id.homeQuickLocate,
            icon = R.drawable.ic_satellite_map,
            iconTint = R.color.accent_teal,
            title = R.string.home_job_locate,
            subtitle = R.string.home_job_locate_sub
        ) { startActivity(Intent(this, MapActivity::class.java)) }

        bindQuickAction(
            rowId = R.id.homeQuickLos,
            icon = R.drawable.ic_terrain_profile,
            iconTint = R.color.accent_teal,
            title = R.string.home_job_los,
            subtitle = R.string.home_job_los_sub
        ) { startActivity(Intent(this, LosProfilesActivity::class.java)) }

        bindQuickAction(
            rowId = R.id.homeQuickWifi,
            icon = R.drawable.ic_wifi_signal,
            iconTint = R.color.accent_yellow,
            title = R.string.home_job_wifi,
            subtitle = R.string.home_job_wifi_sub
        ) { startActivity(Intent(this, WifiMonitorActivity::class.java)) }

        bindQuickAction(
            rowId = R.id.homeQuickSpeed,
            icon = R.drawable.ic_speed_test,
            iconTint = R.color.accent_yellow,
            title = R.string.home_job_speed,
            subtitle = R.string.home_job_speed_sub
        ) { startActivity(Intent(this, SpeedTestActivity::class.java)) }

        bindQuickAction(
            rowId = R.id.homeQuickDiagnose,
            icon = R.drawable.ic_network_diagnose,
            iconTint = R.color.accent_teal,
            title = R.string.home_job_diagnose,
            subtitle = R.string.home_job_diagnose_sub
        ) { startActivity(Intent(this, NetworkDiagnoseActivity::class.java)) }

        bindHubLink(
            rowId = R.id.homeNetworkHubButton,
            icon = R.drawable.ic_wifi_signal,
            iconTint = R.color.accent_teal,
            title = R.string.home_hub_network,
            subtitle = R.string.home_hub_network_sub
        ) { startActivity(Intent(this, NetworkHubActivity::class.java)) }

        bindHubLink(
            rowId = R.id.homeInstallHubButton,
            icon = R.drawable.ic_compass_rose,
            iconTint = R.color.accent_yellow,
            title = R.string.home_hub_install,
            subtitle = R.string.home_hub_install_sub
        ) { startActivity(Intent(this, InstallDashboardActivity::class.java)) }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    DisplayUnits.apply(state.distanceUnitSystem, state.coordinateFormat)
                    renderDashboard(state)
                }
            }
        }
    }

    private fun renderDashboard(state: TowerUiState) {
        val towerCount = state.towers.size
        val inRange = state.visibleTowers().size
        val hasSites = towerCount > 0

        importButton.isVisible = !hasSites
        sitesCount.text = if (hasSites) {
            getString(R.string.home_sites_loaded, towerCount)
        } else {
            getString(R.string.home_no_sites)
        }

        inRangeCount.isVisible = hasSites
        if (hasSites) {
            inRangeCount.text = getString(R.string.home_sites_in_range, inRange)
        }

        LocationSourceChip.chipLabel(state, this).let { label ->
            if (locationChip.text.toString() != label) {
                locationChip.text = label
            }
        }

        val nearest = state.nearestVisibleTower()
        if (nearest != null && state.positioningLocation() != null) {
            val distance = state.distanceTo(nearest)
            val bearing = state.bearingTo(nearest)
            nearestLabel.text = getString(R.string.home_nearest_tower, nearest.name)
            if (distance != null && bearing != null) {
                val distText = UnitFormat.formatDistance(distance, state.distanceUnitSystem)
                val bearingText = GeoUtils.formatBearing(bearing)
                nearestDetail.text = getString(R.string.home_nearest_detail, distText, bearingText)
                nearestDetail.isVisible = true
            } else {
                nearestDetail.isVisible = false
            }
        } else if (hasSites) {
            nearestLabel.text = getString(R.string.home_nearest_waiting)
            nearestDetail.isVisible = false
        } else {
            nearestLabel.text = getString(R.string.home_nearest_none)
            nearestDetail.isVisible = false
        }

        gpsStatus.text = when {
            state.usesCustomLocation() -> {
                getString(
                    R.string.location_mode_custom_set,
                    GeoUtils.formatCoordinates(state.installLatitude!!, state.installLongitude!!)
                )
            }
            state.userLocation != null -> {
                val accuracy = state.userLocation!!.accuracyMeters
                if (accuracy > 0f) {
                    getString(
                        R.string.home_gps_accuracy,
                        UnitFormat.formatDistance(accuracy.toDouble(), state.distanceUnitSystem)
                    )
                } else {
                    getString(R.string.location_chip_gps)
                }
            }
            else -> getString(R.string.home_gps_waiting)
        }
    }

    private fun bindQuickAction(
        rowId: Int,
        icon: Int,
        iconTint: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
        val row = findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.homeQuickIcon).apply {
            setImageResource(icon)
            setColorFilter(ContextCompat.getColor(this@HomeActivity, iconTint))
        }
        row.findViewById<TextView>(R.id.homeQuickTitle).setText(title)
        row.findViewById<TextView>(R.id.homeQuickSubtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }

    private fun bindHubLink(
        rowId: Int,
        icon: Int,
        iconTint: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
        val row = findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.homeHubIcon).apply {
            setImageResource(icon)
            setColorFilter(ContextCompat.getColor(this@HomeActivity, iconTint))
        }
        row.findViewById<TextView>(R.id.homeHubTitle).setText(title)
        row.findViewById<TextView>(R.id.homeHubSubtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates(includeHeading = false)
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}

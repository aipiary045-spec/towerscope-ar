package com.towerscope.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.towerscope.ar.network.NetworkSession
import com.towerscope.ar.ui.LocationSourceChip
import com.towerscope.ar.util.DisplayUnits
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.util.UnitFormat
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.activity_home) {

    private val viewModel: TowerScopeViewModel by activityViewModels()

    private lateinit var sitesCount: TextView
    private lateinit var inRangeCount: TextView
    private lateinit var locationChip: TextView
    private lateinit var nearestLabel: TextView
    private lateinit var nearestDetail: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var importButton: View
    private var networkStatus: TextView? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOk || coarseOk) {
            viewModel.startLocationUpdates(includeHeading = false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        networkStatus = view.findViewById(R.id.homeNetworkStatus)

        sitesCount = view.findViewById(R.id.homeSitesCount)
        inRangeCount = view.findViewById(R.id.homeInRangeCount)
        locationChip = view.findViewById(R.id.homeLocationChip)
        nearestLabel = view.findViewById(R.id.homeNearestLabel)
        nearestDetail = view.findViewById(R.id.homeNearestDetail)
        gpsStatus = view.findViewById(R.id.homeGpsStatus)
        importButton = view.findViewById(R.id.homeImportButton)

        LocationSourceChip(
            chip = locationChip,
            fragmentManager = parentFragmentManager,
            viewModel = viewModel
        )

        importButton.setOnClickListener {
            startActivity(Intent(requireContext(), DataMenuActivity::class.java))
        }

        bindQuickAction(view, R.id.homeQuickCompass, R.drawable.ic_compass_rose, R.color.accent_yellow,
            R.string.home_job_aim, R.string.home_job_aim_sub) {
            startActivity(Intent(requireContext(), MainActivity::class.java))
        }
        bindQuickAction(view, R.id.homeQuickLocate, R.drawable.ic_satellite_map, R.color.accent_teal,
            R.string.home_job_locate, R.string.home_job_locate_sub) {
            startActivity(Intent(requireContext(), MapActivity::class.java))
        }
        bindQuickAction(view, R.id.homeQuickLos, R.drawable.ic_terrain_profile, R.color.accent_teal,
            R.string.home_job_los, R.string.home_job_los_sub) {
            startActivity(Intent(requireContext(), LosProfilesActivity::class.java))
        }
        bindQuickAction(view, R.id.homeQuickWifi, R.drawable.ic_wifi_signal, R.color.accent_yellow,
            R.string.home_job_wifi, R.string.home_job_wifi_sub) {
            startActivity(Intent(requireContext(), WifiMonitorActivity::class.java))
        }
        bindQuickAction(view, R.id.homeQuickSpeed, R.drawable.ic_speed_test, R.color.accent_yellow,
            R.string.home_job_speed, R.string.home_job_speed_sub) {
            startActivity(Intent(requireContext(), SpeedTestActivity::class.java))
        }
        bindQuickAction(view, R.id.homeQuickDiagnose, R.drawable.ic_network_diagnose, R.color.accent_teal,
            R.string.home_job_diagnose, R.string.home_job_diagnose_sub) {
            startActivity(Intent(requireContext(), NetworkDiagnoseActivity::class.java))
        }

        view.findViewById<View>(R.id.homeNetworkHubButton).setOnClickListener {
            (activity as? MainHostActivity)?.showTab(com.towerscope.ar.ui.BottomNavTab.NETWORK)
        }
        view.findViewById<View>(R.id.homeInstallHubButton).setOnClickListener {
            (activity as? MainHostActivity)?.showTab(com.towerscope.ar.ui.BottomNavTab.INSTALL)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    DisplayUnits.apply(state.distanceUnitSystem, state.coordinateFormat)
                    renderDashboard(state)
                }
            }
        }

        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates(includeHeading = false)
        }
        renderNetworkSession()
    }

    override fun onPause() {
        viewModel.stopLocationUpdates()
        super.onPause()
    }

    private fun renderNetworkSession() {
        val ctx = context ?: return
        val speed = NetworkSession.speedSummary(ctx)
        val ping = NetworkSession.pingSummary(ctx)
        val label = listOfNotNull(speed, ping).joinToString("  ·  ")
        networkStatus?.apply {
            isVisible = label.isNotBlank()
            text = label
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

        LocationSourceChip.chipLabel(state, requireContext()).let { label ->
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
                nearestDetail.text = getString(
                    R.string.home_nearest_detail,
                    UnitFormat.formatDistance(distance, state.distanceUnitSystem),
                    GeoUtils.formatBearing(bearing)
                )
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
            state.usesCustomLocation() -> getString(
                R.string.location_mode_custom_set,
                GeoUtils.formatCoordinates(state.installLatitude!!, state.installLongitude!!)
            )
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
        root: View,
        rowId: Int,
        icon: Int,
        iconTint: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
        val row = root.findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.homeQuickIcon).apply {
            setImageResource(icon)
            setColorFilter(ContextCompat.getColor(requireContext(), iconTint))
        }
        row.findViewById<TextView>(R.id.homeQuickTitle).setText(title)
        row.findViewById<TextView>(R.id.homeQuickSubtitle).setText(subtitle)
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
        val ctx = requireContext()
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}

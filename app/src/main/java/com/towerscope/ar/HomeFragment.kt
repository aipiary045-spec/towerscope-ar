package com.towerscope.ar

import android.Manifest
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.towerscope.ar.network.WifiMonitor
import com.towerscope.ar.ui.BottomNavTab
import com.towerscope.ar.ui.HomeLiveMetrics
import com.towerscope.ar.ui.LocationSourceChip
import com.towerscope.ar.ui.SwipeRefreshHelper
import com.towerscope.ar.util.DisplayUnits
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.util.UnitFormat
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.activity_home) {

    private val viewModel: TowerScopeViewModel by activityViewModels()

    private lateinit var sitesCount: TextView
    private lateinit var inRangeCount: TextView
    private lateinit var locationChip: TextView
    private lateinit var nearestLabel: TextView
    private lateinit var nearestDetail: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var wifiMetric: HomeLiveMetrics.MetricViews
    private lateinit var internetMetric: HomeLiveMetrics.MetricViews
    private lateinit var speedMetric: HomeLiveMetrics.MetricViews
    private var networkTopology: View? = null

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
        networkTopology = view.findViewById(R.id.homeNetworkTopology)
        wifiMetric = HomeLiveMetrics.views(view, R.id.homeMetricWifi)
        internetMetric = HomeLiveMetrics.views(view, R.id.homeMetricInternet)
        speedMetric = HomeLiveMetrics.views(view, R.id.homeMetricSpeed)

        sitesCount = view.findViewById(R.id.homeSitesCount)
        inRangeCount = view.findViewById(R.id.homeInRangeCount)
        locationChip = view.findViewById(R.id.homeLocationChip)
        nearestLabel = view.findViewById(R.id.homeNearestLabel)
        nearestDetail = view.findViewById(R.id.homeNearestDetail)
        gpsStatus = view.findViewById(R.id.homeGpsStatus)

        LocationSourceChip(
            chip = locationChip,
            fragmentManager = parentFragmentManager,
            viewModel = viewModel
        )

        bindHubLink(view, R.id.homeNetworkHubButton, R.drawable.ic_wifi_signal, R.color.accent_teal,
            R.string.home_hub_network, R.string.home_hub_network_sub) {
            (activity as? MainHostActivity)?.showTab(BottomNavTab.NETWORK)
        }
        bindHubLink(view, R.id.homeInstallHubButton, R.drawable.ic_compass_rose, R.color.accent_yellow,
            R.string.home_hub_install, R.string.home_hub_install_sub) {
            (activity as? MainHostActivity)?.showTab(BottomNavTab.INSTALL)
        }

        SwipeRefreshHelper.bind(
            view.findViewById<SwipeRefreshLayout>(R.id.homeSwipeRefresh),
            viewLifecycleOwner.lifecycleScope
        ) {
            refreshHomeMetrics()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    DisplayUnits.apply(state.distanceUnitSystem, state.coordinateFormat)
                    renderDashboard(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val wifiMonitor = WifiMonitor(requireContext())
                while (isActive) {
                    refreshHomeMetrics(wifiMonitor)
                    delay(4_000L)
                }
            }
        }

        ensureLocationPermission()
    }

    private suspend fun refreshHomeMetrics(wifiMonitor: WifiMonitor? = null) {
        val topo = networkTopology ?: return
        val monitor = wifiMonitor ?: WifiMonitor(requireContext())
        HomeLiveMetrics.refresh(
            context = requireContext(),
            wifiMonitor = monitor,
            topologyRoot = topo,
            wifi = wifiMetric,
            internet = internetMetric,
            speed = speedMetric
        )
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates(includeHeading = false)
        }
        renderDashboard(viewModel.uiState.value)
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

    private fun renderDashboard(state: TowerUiState) {
        val towerCount = state.towers.size
        val inRange = state.visibleTowers().size
        val hasSites = towerCount > 0

        sitesCount.text = if (hasSites) {
            towerCount.toString()
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
            nearestLabel.text = nearest.name
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

    private fun bindHubLink(
        root: View,
        rowId: Int,
        icon: Int,
        iconTint: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
        val row = root.findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.homeHubIcon).apply {
            setImageResource(icon)
            setColorFilter(ContextCompat.getColor(requireContext(), iconTint))
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
        val ctx = requireContext()
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}

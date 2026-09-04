package com.towerscope.ar

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.towerscope.ar.ui.FieldGreeting
import com.towerscope.ar.ui.BottomNavTab
import com.towerscope.ar.ui.HeroActionCardBinder
import com.towerscope.ar.ui.HomeLiveMetrics
import com.towerscope.ar.ui.InternetLiveMonitor
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
import java.util.Locale

class HomeFragment : Fragment(R.layout.activity_home) {

    private val viewModel: TowerScopeViewModel by activityViewModels()

    private lateinit var sitesSummary: TextView
    private lateinit var nearbySitesEmpty: TextView
    private lateinit var nearbySitesList: LinearLayout
    private lateinit var locationChip: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var homeGreeting: TextView
    private lateinit var wifiMetric: HomeLiveMetrics.MetricViews
    private lateinit var internetMetric: HomeLiveMetrics.MetricViews
    private lateinit var speedMetric: HomeLiveMetrics.MetricViews
    private val internetMonitor = InternetLiveMonitor()
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

        sitesSummary = view.findViewById(R.id.homeSitesSummary)
        nearbySitesEmpty = view.findViewById(R.id.homeNearbySitesEmpty)
        nearbySitesList = view.findViewById(R.id.homeNearbySitesList)
        locationChip = view.findViewById(R.id.homeLocationChip)
        gpsStatus = view.findViewById(R.id.homeGpsStatus)
        homeGreeting = view.findViewById(R.id.homeGreeting)

        HeroActionCardBinder.bind(
            heroRoot = view.findViewById(R.id.homeHeroCompass),
            context = requireContext(),
            state = viewModel.uiState.value,
            onClick = { startActivity(Intent(requireContext(), MainActivity::class.java)) }
        )

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

        view.findViewById<View>(R.id.homeMetricSpeed).setOnClickListener {
            (activity as? MainHostActivity)?.showTab(BottomNavTab.NETWORK)
        }

        SwipeRefreshHelper.bind(
            view.findViewById<SwipeRefreshLayout>(R.id.homeSwipeRefresh),
            viewLifecycleOwner.lifecycleScope
        ) {
            refreshHomeMetrics(forceInternetSpeed = true)
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

    private suspend fun refreshHomeMetrics(
        wifiMonitor: WifiMonitor? = null,
        forceInternetSpeed: Boolean = false
    ) {
        val topo = networkTopology ?: return
        val monitor = wifiMonitor ?: WifiMonitor(requireContext())
        val live = internetMonitor.tick(requireContext(), forceQuickSpeed = forceInternetSpeed)
        HomeLiveMetrics.refresh(
            context = requireContext(),
            wifiMonitor = monitor,
            topologyRoot = topo,
            wifi = wifiMetric,
            internet = internetMetric,
            speed = speedMetric,
            internetLive = live
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
        homeGreeting.text = FieldGreeting.headline(requireContext())
        HeroActionCardBinder.bind(
            heroRoot = requireView().findViewById(R.id.homeHeroCompass),
            context = requireContext(),
            state = state,
            onClick = { startActivity(Intent(requireContext(), MainActivity::class.java)) }
        )

        val towerCount = state.towers.size
        val inRange = state.visibleTowers().size
        val hasSites = towerCount > 0

        sitesSummary.text = when {
            !hasSites -> getString(R.string.home_no_sites)
            else -> getString(R.string.home_sites_summary, towerCount, inRange)
        }

        LocationSourceChip.chipLabel(state, requireContext()).let { label ->
            if (locationChip.text.toString() != label) {
                locationChip.text = label
            }
        }

        renderNearbySites(state)

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

    private fun renderNearbySites(state: TowerUiState) {
        val sorted = state.visibleTowersSortedByDistance()
        when {
            state.towers.isEmpty() -> {
                nearbySitesEmpty.isVisible = true
                nearbySitesEmpty.text = getString(R.string.home_nearest_none)
                nearbySitesList.isVisible = false
                nearbySitesList.removeAllViews()
            }
            state.positioningLocation() == null -> {
                nearbySitesEmpty.isVisible = true
                nearbySitesEmpty.text = getString(R.string.home_nearby_waiting)
                nearbySitesList.isVisible = false
                nearbySitesList.removeAllViews()
            }
            sorted.isEmpty() -> {
                nearbySitesEmpty.isVisible = true
                nearbySitesEmpty.text = getString(R.string.home_nearby_none_in_range)
                nearbySitesList.isVisible = false
                nearbySitesList.removeAllViews()
            }
            else -> {
                nearbySitesEmpty.isVisible = false
                nearbySitesList.isVisible = true
                val newIds = sorted.map { it.id }
                val currentIds = (0 until nearbySitesList.childCount).map {
                    nearbySitesList.getChildAt(it).tag as String
                }
                if (currentIds != newIds) {
                    nearbySitesList.removeAllViews()
                    val inflater = LayoutInflater.from(requireContext())
                    sorted.forEach { tower ->
                        val row = inflater.inflate(R.layout.item_home_nearby_site_row, nearbySitesList, false)
                        row.tag = tower.id
                        row.setOnClickListener {
                            viewModel.selectTower(tower.id)
                            (activity as? MainHostActivity)?.showTab(BottomNavTab.INSTALL)
                        }
                        nearbySitesList.addView(row)
                    }
                }
                for (index in sorted.indices) {
                    val tower = sorted[index]
                    val row = nearbySitesList.getChildAt(index)
                    row.findViewById<TextView>(R.id.homeNearbySiteName).text = tower.name
                    val distance = state.distanceTo(tower)
                    val bearing = state.bearingTo(tower)
                    val distText = distance?.let {
                        UnitFormat.formatDistance(it, state.distanceUnitSystem)
                    } ?: "—"
                    val azText = bearing?.let { GeoUtils.formatBearing(it) } ?: "—"
                    row.findViewById<TextView>(R.id.homeNearbySiteMeta).text =
                        String.format(Locale.US, "%s · %s", distText, azText)
                }
            }
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

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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.towerscope.ar.ui.InstallHubPreviews
import com.towerscope.ar.ui.LocationSourceChip
import com.towerscope.ar.ui.NetworkHubPreviews
import com.towerscope.ar.ui.SwipeRefreshHelper
import com.towerscope.ar.ui.WfmSegmentTabs
import com.towerscope.ar.util.DisplayUnits
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch
import java.util.Locale

class InstallDashboardFragment : Fragment(R.layout.activity_install_dashboard) {

    private val viewModel: TowerScopeViewModel by activityViewModels()

    private lateinit var sitesSummary: TextView
    private lateinit var nearestList: TextView
    private lateinit var losPreview: NetworkHubPreviews.PreviewViews
    private lateinit var locationSourceChip: LocationSourceChip
    private var startedLosScan = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOk || coarseOk) {
            viewModel.startLocationUpdates(includeHeading = false)
            maybeStartLosScan()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sitesSummary = view.findViewById(R.id.installDashboardSitesSummary)
        nearestList = view.findViewById(R.id.installDashboardNearestList)
        losPreview = NetworkHubPreviews.views(view, R.id.installDashboardLosPreview)

        locationSourceChip = LocationSourceChip(
            chip = view.findViewById(R.id.installDashboardLocationChip),
            fragmentManager = parentFragmentManager,
            viewModel = viewModel,
            onModeChanged = { maybeStartLosScan(force = true) }
        )

        view.findViewById<View>(R.id.installTabOverview).findViewById<TextView>(R.id.wfmTabLabel)
            .setText(R.string.install_tab_overview)
        view.findViewById<View>(R.id.installTabTools).findViewById<TextView>(R.id.wfmTabLabel)
            .setText(R.string.install_tab_tools)
        WfmSegmentTabs.bindInstallHub(view)

        SwipeRefreshHelper.bind(
            view.findViewById<SwipeRefreshLayout>(R.id.installDashboardSwipeRefresh),
            viewLifecycleOwner.lifecycleScope
        ) {
            refreshInstallDashboard()
        }

        view.findViewById<View>(R.id.installDashboardLosPreview).setOnClickListener {
            startActivity(Intent(requireContext(), LosProfilesActivity::class.java))
        }
        losPreview.chart?.setOnClickListener {
            startActivity(Intent(requireContext(), LosProfilesActivity::class.java))
        }

        view.findViewById<View>(R.id.installDashboardCompassButton).setOnClickListener {
            startActivity(Intent(requireContext(), MainActivity::class.java))
        }
        bindToolRow(view, R.id.installToolLocate, R.drawable.ic_satellite_map, R.color.accent_teal,
            R.string.home_job_locate, R.string.home_job_locate_sub) {
            startActivity(Intent(requireContext(), MapActivity::class.java))
        }
        bindToolRow(view, R.id.installToolLos, R.drawable.ic_terrain_profile, R.color.accent_teal,
            R.string.home_job_los, R.string.home_job_los_sub) {
            startActivity(Intent(requireContext(), LosProfilesActivity::class.java))
        }
        bindToolRow(view, R.id.installToolRefreshLos, R.drawable.ic_terrain_profile, R.color.accent_yellow,
            R.string.install_dashboard_refresh_los, R.string.install_dashboard_los_label) {
            startedLosScan = false
            maybeStartLosScan(force = true)
        }
        bindToolRow(view, R.id.installToolSiteBrowser, R.drawable.ic_compass_rose, R.color.accent_teal,
            R.string.site_browser_title, R.string.site_browser_sub) {
            startActivity(Intent(requireContext(), SiteBrowserActivity::class.java))
        }
        bindToolRow(view, R.id.installToolImport, R.drawable.ic_install_pin, R.color.accent_yellow,
            R.string.install_dashboard_import, R.string.home_import_hint) {
            startActivity(Intent(requireContext(), DataMenuActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    DisplayUnits.apply(state.distanceUnitSystem, state.coordinateFormat)
                    render(state)
                }
            }
        }

        ensureLocationPermission()
        render(viewModel.uiState.value)
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncFromFileStore()
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates(includeHeading = false)
        }
        maybeStartLosScan()
    }

    override fun onPause() {
        viewModel.stopLocationUpdates()
        super.onPause()
    }

    private fun refreshInstallDashboard() {
        viewModel.syncFromFileStore()
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates(includeHeading = false)
        }
        startedLosScan = false
        maybeStartLosScan(force = true)
        render(viewModel.uiState.value)
    }

    private fun bindToolRow(
        root: View,
        rowId: Int,
        icon: Int,
        iconTint: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
        val row = root.findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.hubToolIcon).apply {
            setImageResource(icon)
            setColorFilter(ContextCompat.getColor(requireContext(), iconTint))
        }
        row.findViewById<TextView>(R.id.hubToolTitle).setText(title)
        row.findViewById<TextView>(R.id.hubToolSubtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates(includeHeading = false)
            maybeStartLosScan()
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

    private fun maybeStartLosScan(force: Boolean = false) {
        val state = viewModel.uiState.value
        if (!force && (startedLosScan || state.losRangeLoading || state.losRangeRows.isNotEmpty())) return
        if (state.positioningLocation() == null || state.towers.isEmpty()) return
        startedLosScan = true
        viewModel.refreshLosRangeProfiles()
    }

    private fun render(state: TowerUiState) {
        locationSourceChip.render(state, requireContext())
        val towerCount = state.towers.size
        val inRange = state.visibleTowers().size
        sitesSummary.text = when {
            towerCount == 0 -> getString(R.string.install_dashboard_no_sites)
            else -> getString(R.string.install_dashboard_sites_summary, towerCount, inRange)
        }

        val nearest = state.nearestMatches(limit = 3)
        nearestList.text = when {
            towerCount == 0 -> getString(R.string.install_dashboard_nearest_empty)
            state.positioningLocation() == null -> getString(R.string.install_dashboard_nearest_waiting)
            nearest.isEmpty() -> getString(R.string.install_dashboard_nearest_none_in_range)
            else -> nearest.joinToString("\n") { tower ->
                val distance = state.distanceTo(tower)
                val bearing = state.bearingTo(tower)
                val distText = distance?.let(GeoUtils::formatDistance) ?: "—"
                val azText = bearing?.let { GeoUtils.formatAzimuthPadded(it) } ?: "—"
                String.format(Locale.US, "%-12s  %8s  Az %s", tower.name, distText, azText)
            }
        }

        InstallHubPreviews.bindLos(requireContext(), state, losPreview)
    }
}

package com.towerscope.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.towerscope.ar.ui.LocationSourceChip
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.util.DisplayUnits
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.util.LinkEstimate
import com.towerscope.ar.viewmodel.LocationMode
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Install job overview: location, nearest APs, best LOS pick, and one-tap tool links.
 */
class InstallDashboardActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var sitesSummary: TextView
    private lateinit var focusTitle: TextView
    private lateinit var focusMeta: TextView
    private lateinit var nearestList: TextView
    private lateinit var losSummary: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_install_dashboard)
        SystemBars.apply(
            root = findViewById(R.id.installDashboardRoot),
            alsoBottom = findViewById(R.id.installDashboardFooter)
        )
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        sitesSummary = findViewById(R.id.installDashboardSitesSummary)
        focusTitle = findViewById(R.id.installDashboardFocusTitle)
        focusMeta = findViewById(R.id.installDashboardFocusMeta)
        nearestList = findViewById(R.id.installDashboardNearestList)
        losSummary = findViewById(R.id.installDashboardLosSummary)

        locationSourceChip = LocationSourceChip(
            chip = findViewById(R.id.installDashboardLocationChip),
            fragmentManager = supportFragmentManager,
            viewModel = viewModel,
            onModeChanged = { maybeStartLosScan(force = true) }
        )

        findViewById<android.view.View>(R.id.installDashboardCompassButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<android.view.View>(R.id.installDashboardLocateButton).setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        findViewById<android.view.View>(R.id.installDashboardLosButton).setOnClickListener {
            startActivity(Intent(this, LosProfilesActivity::class.java))
        }
        findViewById<android.view.View>(R.id.installDashboardRefreshLosButton).setOnClickListener {
            startedLosScan = false
            maybeStartLosScan(force = true)
        }
        findViewById<android.view.View>(R.id.toolBackButton).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.toolShareButton).isEnabled = false
        findViewById<android.view.View>(R.id.toolShareButton).alpha = 0.4f

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun maybeStartLosScan(force: Boolean = false) {
        val state = viewModel.uiState.value
        if (!force && (startedLosScan || state.losRangeLoading || state.losRangeRows.isNotEmpty())) return
        if (state.positioningLocation() == null || state.towers.isEmpty()) return
        startedLosScan = true
        viewModel.refreshLosRangeProfiles()
    }

    private fun render(state: TowerUiState) {
        locationSourceChip.render(state, this)
        val towerCount = state.towers.size
        val inRange = state.visibleTowers().size
        sitesSummary.text = when {
            towerCount == 0 -> getString(R.string.install_dashboard_no_sites)
            else -> getString(R.string.install_dashboard_sites_summary, towerCount, inRange)
        }

        val focus = state.focusTower()
        if (focus == null) {
            focusTitle.text = when {
                towerCount == 0 -> getString(R.string.install_dashboard_focus_none)
                state.positioningLocation() == null -> getString(R.string.install_dashboard_focus_waiting)
                inRange == 0 -> getString(R.string.install_dashboard_focus_out_of_range)
                else -> getString(R.string.install_dashboard_focus_none)
            }
            focusMeta.text = ""
        } else {
            focusTitle.text = focus.name
            val distance = state.distanceTo(focus)
            val bearing = state.bearingTo(focus)
            focusMeta.text = when {
                distance != null && bearing != null ->
                    "${GeoUtils.formatDistance(distance)}  ·  Az ${GeoUtils.formatAzimuthPadded(bearing)}"
                distance != null -> GeoUtils.formatDistance(distance)
                else -> ""
            }
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

        losSummary.text = formatLosSummary(state)
    }

    private fun formatLosSummary(state: TowerUiState): String {
        when {
            state.towers.isEmpty() -> return getString(R.string.install_dashboard_los_no_sites)
            state.positioningLocation() == null -> {
                return when (state.locationMode) {
                    LocationMode.CUSTOM -> getString(R.string.install_dashboard_los_need_pin)
                    LocationMode.CURRENT_GPS -> getString(R.string.install_dashboard_los_need_gps)
                }
            }
            state.losRangeLoading -> return state.losRangeStatus ?: getString(R.string.install_dashboard_los_loading)
            state.losRangeRows.isEmpty() -> return state.losRangeStatus
                ?: getString(R.string.install_dashboard_los_none_in_range)
        }

        val best = state.bestLosCandidate()
        if (best == null) {
            val failed = state.losRangeRows.count { it.error != null }
            return if (failed > 0) {
                getString(R.string.install_dashboard_los_failed, failed)
            } else {
                getString(R.string.install_dashboard_los_loading)
            }
        }

        val clutter = state.clutterHeightMeters.toDouble()
        val freq = state.frequencyGhz.toDouble()
        val geometric = best.profile?.minClearanceMeters(clutter)
        val fresnel = best.profile?.minFresnelClearanceMeters(clutter, freq)
        val dbm = LinkEstimate.estimatedReceiveLevelDbm(
            distanceMeters = best.distanceMeters,
            frequencyGhz = freq,
            txPowerDbm = state.txPowerDbm.toDouble(),
            apGainDbi = state.apAntennaGainDbi.toDouble(),
            cpeGainDbi = state.cpeAntennaGainDbi.toDouble(),
            geometricClearanceMeters = geometric,
            fresnelClearanceMeters = fresnel
        )
        val bearing = state.bearingTo(best.tower)
        val az = bearing?.let { GeoUtils.formatAzimuthPadded(it) } ?: "—"
        return getString(
            R.string.install_dashboard_los_best,
            best.tower.name,
            GeoUtils.formatDistance(best.distanceMeters),
            az,
            LinkEstimate.formatReceiveLevel(dbm),
            LinkEstimate.signalQuality(dbm).label
        )
    }
}

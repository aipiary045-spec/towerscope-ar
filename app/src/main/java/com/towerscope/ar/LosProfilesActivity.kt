package com.towerscope.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
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
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.ui.LosProfileChartView
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.TowerDetailsBottomSheet
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Separate (non-AR) screen: LOS elevation profiles for towers in the saved range,
 * ranked best clearance first.
 */
class LosProfilesActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var startedScan = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOk || coarseOk) {
            viewModel.startLocationUpdates()
            maybeStartScan()
        } else {
            status.text = "Location permission is required for elevation profiles"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_los_profiles)
        SystemBars.apply(
            root = findViewById(R.id.losRoot),
            alsoBottom = findViewById(R.id.losFooter)
        )
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        subtitle = findViewById(R.id.losRangeSubtitle)
        status = findViewById(R.id.losRangeStatus)
        list = findViewById(R.id.losRangeList)

        findViewById<MaterialButton>(R.id.losRangeHomeButton).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.losRangeRefreshButton).setOnClickListener {
            startedScan = false
            maybeStartScan(force = true)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    subtitle.text = String.format(
                        Locale.US,
                        "Range %s · clutter %.0f m · best LOS first",
                        GeoUtils.formatDistance(state.maxDistanceMeters.toDouble()),
                        state.clutterHeightMeters
                    )
                    status.text = state.losRangeStatus.orEmpty()
                    renderRows(state)
                    maybeStartScan()
                }
            }
        }

        ensureLocationPermission()
    }

    override fun onStart() {
        super.onStart()
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates()
        }
    }

    override fun onStop() {
        viewModel.stopLocationUpdates()
        super.onStop()
    }

    override fun onDestroy() {
        viewModel.clearLosRangeProfiles()
        super.onDestroy()
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates()
            maybeStartScan()
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

    private fun maybeStartScan(force: Boolean = false) {
        val state = viewModel.uiState.value
        if (!force && (startedScan || state.losRangeLoading || state.losRangeRows.isNotEmpty())) return
        if (state.positioningLocation() == null) {
            status.text = "Waiting for GPS…"
            return
        }
        startedScan = true
        viewModel.refreshLosRangeProfiles()
    }

    private fun renderRows(state: TowerUiState) {
        val clutter = state.clutterHeightMeters.toDouble()
        val inflater = LayoutInflater.from(this)
        list.removeAllViews()
        state.losRangeRows.forEachIndexed { index, row ->
            val view = inflater.inflate(R.layout.item_los_range_row, list, false)
            view.findViewById<TextView>(R.id.rowRank).text = "${index + 1}."
            view.findViewById<TextView>(R.id.rowName).text = row.tower.name
            view.findViewById<TextView>(R.id.rowMeta).text =
                "Distance  ${GeoUtils.formatDistance(row.distanceMeters)}"
            val clearanceView = view.findViewById<TextView>(R.id.rowClearance)
            val chart = view.findViewById<LosProfileChartView>(R.id.rowChart)
            when {
                row.loading -> {
                    clearanceView.text = "Profiling…"
                    clearanceView.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                    chart.isVisible = false
                }
                row.error != null -> {
                    clearanceView.text = row.error
                    clearanceView.setTextColor(ContextCompat.getColor(this, R.color.chip_poor))
                    chart.isVisible = false
                }
                row.profile != null -> {
                    val clearance = row.profile.minClearanceMeters(clutter)
                    val clear = clearance > 0.0
                    clearanceView.text = if (clear) {
                        String.format(Locale.US, "Clear · min clearance %.0f m", clearance)
                    } else {
                        String.format(Locale.US, "Blocked · short by %.0f m", -clearance)
                    }
                    clearanceView.setTextColor(
                        ContextCompat.getColor(
                            this,
                            if (clear) R.color.chip_good else R.color.chip_poor
                        )
                    )
                    chart.isVisible = true
                    chart.setProfile(row.profile, clutter)
                }
                else -> {
                    clearanceView.text = "—"
                    chart.isVisible = false
                }
            }
            view.setOnClickListener {
                viewModel.selectTower(row.tower.id)
                if (supportFragmentManager.findFragmentByTag(TowerDetailsBottomSheet.TAG) == null) {
                    TowerDetailsBottomSheet.newInstance(row.tower.id)
                        .show(supportFragmentManager, TowerDetailsBottomSheet.TAG)
                }
            }
            list.addView(view)
        }
    }
}

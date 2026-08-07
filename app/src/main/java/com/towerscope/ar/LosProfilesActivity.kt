package com.towerscope.ar

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
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
import com.towerscope.ar.data.LosProfileBuilder
import com.towerscope.ar.ui.LosProfileChartView
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.TowerDetailsBottomSheet
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Ranked LOS elevation profiles for towers in the saved range.
 */
class LosProfilesActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var startedScan = false
    private val shimmerAnimators = mutableListOf<ObjectAnimator>()

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
                        "%s · %.1f GHz · CPE %.0f m · clutter %.0f m",
                        if (state.hasInstallSite) "From install site" else "From GPS",
                        state.frequencyGhz,
                        state.cpeAntennaAglMeters,
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
        clearShimmers()
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

    private fun clearShimmers() {
        shimmerAnimators.forEach { it.cancel() }
        shimmerAnimators.clear()
    }

    private fun pulse(view: View) {
        val anim = ObjectAnimator.ofFloat(view, View.ALPHA, 0.35f, 1f).apply {
            duration = 700L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        shimmerAnimators.add(anim)
    }

    private fun towerHeightLabel(state: TowerUiState, row: com.towerscope.ar.viewmodel.LosRangeRow): String {
        val tip = row.profile?.towerTipElevationMeters
        val groundHint = row.tower.altitudeMeters
        return when {
            tip != null && groundHint != null && row.tower.altitudeMode.name == "RELATIVE_TO_GROUND" ->
                String.format(Locale.US, "Ht %.0f m", groundHint)
            tip != null -> String.format(Locale.US, "Tip %.0f m", tip)
            groundHint != null && row.tower.altitudeMode.name == "RELATIVE_TO_GROUND" ->
                String.format(Locale.US, "Ht %.0f m", groundHint)
            groundHint != null && row.tower.altitudeMode.name == "ABSOLUTE" ->
                String.format(Locale.US, "Alt %.0f m", groundHint)
            else -> String.format(Locale.US, "Ht ~%.0f m", LosProfileBuilder.DEFAULT_TOWER_HEIGHT_METERS)
        }
    }

    private fun renderRows(state: TowerUiState) {
        clearShimmers()
        val clutter = state.clutterHeightMeters.toDouble()
        val freq = state.frequencyGhz.toDouble()
        val inflater = LayoutInflater.from(this)
        list.removeAllViews()
        state.losRangeRows.forEachIndexed { index, row ->
            val view = inflater.inflate(R.layout.item_los_range_row, list, false)
            val statusBar = view.findViewById<View>(R.id.rowStatusBar)
            val pill = view.findViewById<TextView>(R.id.rowStatusPill)
            val shimmer1 = view.findViewById<View>(R.id.rowShimmer)
            val shimmer2 = view.findViewById<View>(R.id.rowShimmer2)
            view.findViewById<TextView>(R.id.rowRank).text = String.format(Locale.US, "%02d", index + 1)
            view.findViewById<TextView>(R.id.rowName).text = row.tower.name

            val bearing = state.bearingTo(row.tower)
            val az = bearing?.let { GeoUtils.formatAzimuthPadded(it) } ?: "—"
            val height = towerHeightLabel(state, row)
            view.findViewById<TextView>(R.id.rowMeta).text =
                "${GeoUtils.formatDistance(row.distanceMeters)}  ·  Az $az  ·  $height"

            val clearanceView = view.findViewById<TextView>(R.id.rowClearance)
            val chart = view.findViewById<LosProfileChartView>(R.id.rowChart)
            when {
                row.loading -> {
                    pill.isVisible = false
                    statusBar.setBackgroundColor(ContextCompat.getColor(this, R.color.text_dim))
                    clearanceView.text = "Profiling elevation…"
                    clearanceView.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                    chart.isVisible = false
                    shimmer1.isVisible = true
                    shimmer2.isVisible = true
                    pulse(shimmer1)
                    pulse(shimmer2)
                }
                row.error != null -> {
                    pill.isVisible = false
                    statusBar.setBackgroundColor(ContextCompat.getColor(this, R.color.status_blocked))
                    clearanceView.text = row.error
                    clearanceView.setTextColor(ContextCompat.getColor(this, R.color.status_blocked))
                    chart.isVisible = false
                    shimmer1.isVisible = false
                    shimmer2.isVisible = false
                }
                row.profile != null -> {
                    val geometric = row.profile.minClearanceMeters(clutter)
                    val fresnel = row.profile.minFresnelClearanceMeters(clutter, freq)
                    val fresnelClear = fresnel > 0.0
                    val geoClear = geometric > 0.0
                    pill.isVisible = true
                    when {
                        fresnelClear -> {
                            pill.text = "F1 OK"
                            pill.setTextColor(ContextCompat.getColor(this, R.color.status_clear))
                            pill.setBackgroundResource(R.drawable.bg_pill_clear)
                            statusBar.setBackgroundColor(ContextCompat.getColor(this, R.color.status_clear))
                            clearanceView.text = String.format(
                                Locale.US,
                                "60%% F1 +%.0f m · geo %.0f m",
                                fresnel,
                                geometric
                            )
                            clearanceView.setTextColor(ContextCompat.getColor(this, R.color.status_clear))
                        }
                        geoClear -> {
                            pill.text = "TIGHT"
                            pill.setTextColor(ContextCompat.getColor(this, R.color.accent_yellow))
                            pill.setBackgroundResource(R.drawable.bg_pill_clear)
                            statusBar.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_yellow))
                            clearanceView.text = String.format(
                                Locale.US,
                                "Geo clear %.0f m · F1 short %.0f m",
                                geometric,
                                -fresnel
                            )
                            clearanceView.setTextColor(ContextCompat.getColor(this, R.color.accent_yellow))
                        }
                        else -> {
                            pill.text = "BLOCKED"
                            pill.setTextColor(ContextCompat.getColor(this, R.color.status_blocked))
                            pill.setBackgroundResource(R.drawable.bg_pill_blocked)
                            statusBar.setBackgroundColor(ContextCompat.getColor(this, R.color.status_blocked))
                            clearanceView.text = String.format(Locale.US, "Short by  %.0f m", -geometric)
                            clearanceView.setTextColor(ContextCompat.getColor(this, R.color.status_blocked))
                        }
                    }
                    chart.isVisible = true
                    chart.setProfile(row.profile, clutter, freq)
                    shimmer1.isVisible = false
                    shimmer2.isVisible = false
                }
                else -> {
                    pill.isVisible = false
                    clearanceView.text = "—"
                    chart.isVisible = false
                    shimmer1.isVisible = false
                    shimmer2.isVisible = false
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

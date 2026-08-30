package com.towerscope.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.google.android.material.textfield.TextInputEditText
import com.towerscope.ar.data.Tower
import com.towerscope.ar.ui.LocationSourceChip
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.TowerDetailsBottomSheet
import com.towerscope.ar.util.DisplayUnits
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.launch

/**
 * Searchable list of imported sites with quick links to aim, map, and LOS tools.
 */
class SiteBrowserActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var searchField: TextInputEditText
    private lateinit var statusView: TextView
    private lateinit var list: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var locationSourceChip: LocationSourceChip
    private var searchQuery = ""
    private var lastRenderSignature: String? = null

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
        setContentView(R.layout.activity_site_browser)
        SystemBars.apply(findViewById(R.id.siteBrowserRoot))
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        searchField = findViewById(R.id.siteBrowserSearch)
        statusView = findViewById(R.id.siteBrowserStatus)
        list = findViewById(R.id.siteBrowserList)
        scrollView = findViewById(R.id.siteBrowserScroll)
        locationSourceChip = LocationSourceChip(
            chip = findViewById(R.id.siteBrowserLocationChip),
            fragmentManager = supportFragmentManager,
            viewModel = viewModel,
            onModeChanged = { render(viewModel.uiState.value, force = true) }
        )

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                render(viewModel.uiState.value, force = true)
            }
        })

        findViewById<View>(R.id.toolBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.toolShareButton).isEnabled = false
        findViewById<View>(R.id.toolShareButton).alpha = 0.4f
        findViewById<MaterialButton>(R.id.siteBrowserImportButton).setOnClickListener {
            startActivity(Intent(this, DataMenuActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    DisplayUnits.apply(state.distanceUnitSystem, state.coordinateFormat)
                    render(state)
                }
            }
        }

        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncFromFileStore()
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates(includeHeading = false)
        }
    }

    override fun onPause() {
        viewModel.stopLocationUpdates()
        super.onPause()
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
        return fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun render(state: TowerUiState, force: Boolean = false) {
        locationSourceChip.render(state, this)
        val query = searchQuery.trim()
        val rows = state.allTowersSortedByDistance().filter { (tower, _) ->
            matchesSearch(tower, query)
        }
        val signature = siteListSignature(state, rows, query)
        if (!force && signature == lastRenderSignature) return
        lastRenderSignature = signature

        val scrollY = scrollView.scrollY
        statusView.text = when {
            state.towers.isEmpty() -> getString(R.string.site_browser_status_empty)
            query.isNotEmpty() -> getString(R.string.site_browser_status_filtered, rows.size, state.towers.size)
            else -> getString(R.string.site_browser_status_count, state.towers.size)
        }

        val inflater = LayoutInflater.from(this)
        list.removeAllViews()
        if (rows.isEmpty()) {
            val empty = TextView(this).apply {
                setText(
                    if (state.towers.isEmpty()) {
                        R.string.site_browser_empty_import
                    } else {
                        R.string.site_browser_empty_search
                    }
                )
                setTextColor(ContextCompat.getColor(this@SiteBrowserActivity, R.color.text_muted))
                textSize = 14f
            }
            list.addView(empty)
            return
        }

        rows.forEach { (tower, distance) ->
            val row = inflater.inflate(R.layout.item_site_browser_row, list, false)
            row.findViewById<TextView>(R.id.siteRowName).text = tower.name
            val inRange = state.isTowerInRange(tower)
            row.findViewById<TextView>(R.id.siteRowRangeBadge).isVisible = inRange

            val bearing = state.bearingTo(tower)
            val meta = buildString {
                if (distance != null) {
                    append(GeoUtils.formatDistance(distance))
                } else {
                    append("—")
                }
                if (bearing != null) {
                    append("  ·  Az ").append(GeoUtils.formatAzimuthPadded(bearing))
                }
                if (!inRange) {
                    append("  ·  ").append(getString(R.string.site_browser_out_of_range))
                }
                append("\n")
                append(
                    GeoUtils.formatCoordinates(
                        tower.latitude,
                        tower.longitude
                    )
                )
            }
            row.findViewById<TextView>(R.id.siteRowMeta).text = meta

            row.findViewById<MaterialButton>(R.id.siteRowDetailsButton).setOnClickListener {
                openTowerDetails(tower.id)
            }
            row.findViewById<MaterialButton>(R.id.siteRowAimButton).setOnClickListener {
                openTowerTool(tower.id, MainActivity::class.java)
            }
            row.findViewById<MaterialButton>(R.id.siteRowMapButton).setOnClickListener {
                openTowerTool(tower.id, MapActivity::class.java)
            }
            row.findViewById<MaterialButton>(R.id.siteRowLosButton).setOnClickListener {
                openTowerTool(tower.id, LosProfilesActivity::class.java)
            }
            list.addView(row)
        }
        scrollView.scrollTo(0, scrollY)
    }

    private fun siteListSignature(
        state: TowerUiState,
        rows: List<Pair<Tower, Double?>>,
        query: String
    ): String = buildString {
        append(query)
        append('|').append(state.towers.size)
        append('|').append(state.hiddenTowerIds)
        append('|').append(state.maxDistanceMeters)
        append('|').append(state.locationMode)
        append('|').append(state.hasInstallSite)
        rows.joinToString(";") { (tower, distance) ->
            buildString {
                append(tower.id)
                append(':')
                append(distance?.let { (it / 25.0).toInt() } ?: "n")
                append(':')
                append(state.isTowerInRange(tower))
            }
        }
    }

    private fun matchesSearch(tower: Tower, query: String): Boolean {
        if (query.isEmpty()) return true
        if (tower.name.contains(query, ignoreCase = true)) return true
        if (tower.id.contains(query, ignoreCase = true)) return true
        val coords = GeoUtils.formatCoordinates(tower.latitude, tower.longitude)
        return coords.contains(query, ignoreCase = true)
    }

    private fun openTowerDetails(towerId: String) {
        viewModel.selectTower(towerId)
        if (supportFragmentManager.findFragmentByTag(TowerDetailsBottomSheet.TAG) == null) {
            TowerDetailsBottomSheet.newInstance(towerId)
                .show(supportFragmentManager, TowerDetailsBottomSheet.TAG)
        }
    }

    private fun openTowerTool(towerId: String, activityClass: Class<*>) {
        viewModel.selectTower(towerId)
        startActivity(TowerIntents.open(this, activityClass, towerId))
    }
}

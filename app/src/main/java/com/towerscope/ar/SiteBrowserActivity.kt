package com.towerscope.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.towerscope.ar.data.Tower
import com.towerscope.ar.ui.LocationSourceChip
import com.towerscope.ar.ui.SiteBrowserAdapter
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.ui.ToolScaffold
import com.towerscope.ar.ui.ToolTopology
import com.towerscope.ar.ui.TowerDetailsBottomSheet
import com.towerscope.ar.util.DisplayUnits
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.LocationMode
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import com.towerscope.ar.viewmodel.TowerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Searchable list of imported sites with quick links to aim, map, and LOS tools.
 */
class SiteBrowserActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var searchField: TextInputEditText
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var locationSourceChip: LocationSourceChip
    private lateinit var adapter: SiteBrowserAdapter
    private val searchQuery = MutableStateFlow("")
    private var lastLocationChipKey: String? = null
    private var navigating = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOk || coarseOk) {
            viewModel.refreshUserLocationOnce()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_site_browser)
        SystemBars.apply(findViewById(R.id.siteBrowserRoot))
        ToolTopology.bindWhenResumed(this, findViewById(R.id.siteBrowserRoot))
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        searchField = findViewById(R.id.siteBrowserSearch)
        statusView = findViewById(R.id.siteBrowserStatus)
        emptyView = findViewById(R.id.siteBrowserEmpty)
        recycler = findViewById(R.id.siteBrowserRecycler)
        locationSourceChip = LocationSourceChip(
            chip = findViewById(R.id.siteBrowserLocationChip),
            fragmentManager = supportFragmentManager,
            viewModel = viewModel,
            onModeChanged = { viewModel.refreshUserLocationOnce() }
        )
        adapter = SiteBrowserAdapter(
            onDetails = { openTowerDetails(it) },
            onAim = { openTowerTool(it, MainActivity::class.java) },
            onMap = { openTowerTool(it, MapActivity::class.java) },
            onLos = { openTowerTool(it, LosProfilesActivity::class.java) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        ToolScaffold.bind(
            activity = this,
            titleRes = R.string.site_browser_title,
            subtitleRes = R.string.site_browser_sub
        )

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery.value = s?.toString().orEmpty()
            }
        })

        findViewById<MaterialButton>(R.id.siteBrowserImportButton).setOnClickListener {
            startActivity(Intent(this, DataMenuActivity::class.java))
        }

        observeList()
        ensureLocationPermission()
    }

    @OptIn(FlowPreview::class)
    private fun observeList() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val listState = viewModel.uiState
                    .map { state ->
                        SiteBrowserStateSlice(
                            towers = state.towers,
                            hiddenTowerIds = state.hiddenTowerIds,
                            userLocation = state.userLocation,
                            locationMode = state.locationMode,
                            hasInstallSite = state.hasInstallSite,
                            maxDistanceMeters = state.maxDistanceMeters,
                            installLatitude = state.installLatitude,
                            installLongitude = state.installLongitude
                        )
                    }
                    .distinctUntilChanged()
                combine(listState, searchQuery.debounce(120)) { slice, query ->
                    buildUiModel(slice, query.trim())
                }
                    .flowOn(Dispatchers.Default)
                    .distinctUntilChanged()
                    .collect { model ->
                        DisplayUnits.apply(
                            viewModel.uiState.value.distanceUnitSystem,
                            viewModel.uiState.value.coordinateFormat
                        )
                        applyUiModel(model)
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        navigating = false
        viewModel.syncFromFileStore()
        if (hasLocationPermission()) {
            viewModel.refreshUserLocationOnce()
        }
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            viewModel.refreshUserLocationOnce()
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

    private data class SiteBrowserStateSlice(
        val towers: List<Tower>,
        val hiddenTowerIds: Set<String>,
        val userLocation: com.towerscope.ar.location.UserLocation?,
        val locationMode: LocationMode,
        val hasInstallSite: Boolean,
        val maxDistanceMeters: Float,
        val installLatitude: Double?,
        val installLongitude: Double?
    )

    private data class UiModel(
        val statusText: String,
        val rows: List<SiteBrowserAdapter.Row>,
        val emptyText: String?,
        val locationChipKey: String
    )

    private fun buildUiModel(slice: SiteBrowserStateSlice, query: String): UiModel {
        val state = TowerUiState(
            towers = slice.towers,
            hiddenTowerIds = slice.hiddenTowerIds,
            maxDistanceMeters = slice.maxDistanceMeters,
            userLocation = slice.userLocation,
            installLatitude = slice.installLatitude,
            installLongitude = slice.installLongitude,
            locationMode = slice.locationMode
        )
        val outOfRangeLabel = applicationContext.getString(R.string.site_browser_out_of_range)
        val rows = state.allTowersSortedByDistance()
            .asSequence()
            .filter { (tower, _) -> matchesSearch(tower, query) }
            .map { (tower, distance) ->
                val inRange = state.isTowerInRange(tower)
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
                        append("  ·  ").append(outOfRangeLabel)
                    }
                    append('\n')
                    append(GeoUtils.formatCoordinates(tower.latitude, tower.longitude))
                }
                SiteBrowserAdapter.Row(
                    towerId = tower.id,
                    name = tower.name,
                    meta = meta,
                    inRange = inRange
                )
            }
            .toList()

        val statusText = when {
            state.towers.isEmpty() -> applicationContext.getString(R.string.site_browser_status_empty)
            query.isNotEmpty() -> applicationContext.getString(
                R.string.site_browser_status_filtered,
                rows.size,
                state.towers.size
            )
            else -> applicationContext.getString(R.string.site_browser_status_count, state.towers.size)
        }
        val emptyText = when {
            rows.isNotEmpty() -> null
            state.towers.isEmpty() -> applicationContext.getString(R.string.site_browser_empty_import)
            else -> applicationContext.getString(R.string.site_browser_empty_search)
        }
        val locationChipKey = buildString {
            append(slice.locationMode)
            append('|').append(slice.hasInstallSite)
            append('|').append(slice.userLocation?.latitude?.let { (it * 100).toInt() })
            append('|').append(slice.userLocation?.longitude?.let { (it * 100).toInt() })
        }
        return UiModel(statusText, rows, emptyText, locationChipKey)
    }

    private fun applyUiModel(model: UiModel) {
        statusView.text = model.statusText
        emptyView.text = model.emptyText
        emptyView.isVisible = model.emptyText != null
        recycler.isVisible = model.rows.isNotEmpty()
        adapter.submitList(model.rows)
        if (model.locationChipKey != lastLocationChipKey) {
            lastLocationChipKey = model.locationChipKey
            locationSourceChip.render(viewModel.uiState.value, this)
        }
    }

    private fun matchesSearch(tower: Tower, query: String): Boolean {
        if (query.isEmpty()) return true
        if (tower.name.contains(query, ignoreCase = true)) return true
        if (tower.id.contains(query, ignoreCase = true)) return true
        return GeoUtils.formatCoordinates(tower.latitude, tower.longitude)
            .contains(query, ignoreCase = true)
    }

    private fun openTowerDetails(towerId: String) {
        if (navigating) return
        viewModel.selectTower(towerId, loadProfile = false)
        if (supportFragmentManager.findFragmentByTag(TowerDetailsBottomSheet.TAG) == null) {
            TowerDetailsBottomSheet.newInstance(towerId)
                .show(supportFragmentManager, TowerDetailsBottomSheet.TAG)
        }
    }

    private fun openTowerTool(towerId: String, activityClass: Class<*>) {
        if (navigating) return
        navigating = true
        viewModel.selectTower(towerId, loadProfile = false)
        startActivity(TowerIntents.open(this, activityClass, towerId))
    }
}

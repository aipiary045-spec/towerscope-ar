package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.ui.SettingsBottomSheet
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Install-tech hub: import sites, then Aim / Locate / Check LOS / Wi‑Fi / Speed.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var sourceStatus: TextView
    private lateinit var updatedAt: TextView
    private lateinit var importButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)
        SystemBars.apply(findViewById(R.id.homeRoot))
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        sourceStatus = findViewById(R.id.homeSourceStatus)
        updatedAt = findViewById(R.id.homeUpdatedAt)
        importButton = findViewById(R.id.homeImportButton)

        findViewById<View>(R.id.homeAimButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<View>(R.id.homeMapButton).setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        findViewById<View>(R.id.homeLosButton).setOnClickListener {
            startActivity(Intent(this, LosProfilesActivity::class.java))
        }
        findViewById<View>(R.id.homeWifiButton).setOnClickListener {
            startActivity(Intent(this, WifiMonitorActivity::class.java))
        }
        findViewById<View>(R.id.homeSpeedButton).setOnClickListener {
            startActivity(Intent(this, SpeedTestActivity::class.java))
        }
        importButton.setOnClickListener {
            startActivity(Intent(this, DataMenuActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.homeSettingsButton).setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) == null) {
                SettingsBottomSheet.newInstance().show(supportFragmentManager, SettingsBottomSheet.TAG)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val empty = state.towers.isEmpty()
                    importButton.isVisible = empty
                    val source = state.sourceName
                    sourceStatus.text = when {
                        empty -> getString(R.string.home_no_sites)
                        source != null -> "${state.towers.size} sites · $source"
                        else -> "${state.towers.size} sites loaded"
                    }
                    updatedAt.text = when {
                        empty -> getString(R.string.home_import_hint)
                        state.towersUpdatedAtMs > 0L ->
                            "Updated  " + DateFormat.getDateTimeInstance(
                                DateFormat.MEDIUM,
                                DateFormat.SHORT
                            ).format(Date(state.towersUpdatedAtMs))
                        else -> "Ready for field work"
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncFromFileStore()
    }
}

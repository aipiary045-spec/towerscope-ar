package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
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
 * App hub — compass, satellite map, elevation profiles, and settings.
 * Tower KML/KMZ upload lives under Settings → Tower data.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var sourceStatus: TextView
    private lateinit var updatedAt: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)
        SystemBars.apply(findViewById(R.id.homeRoot))
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        sourceStatus = findViewById(R.id.homeSourceStatus)
        updatedAt = findViewById(R.id.homeUpdatedAt)

        findViewById<View>(R.id.homeArButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<View>(R.id.homeMapButton).setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        findViewById<View>(R.id.homeLosButton).setOnClickListener {
            startActivity(Intent(this, LosProfilesActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.homeSettingsButton).setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) == null) {
                SettingsBottomSheet.newInstance().show(supportFragmentManager, SettingsBottomSheet.TAG)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val source = state.sourceName
                    sourceStatus.text = when {
                        state.towers.isEmpty() -> "No towers loaded"
                        source != null -> "${state.towers.size} towers · $source"
                        else -> "${state.towers.size} towers loaded"
                    }
                    updatedAt.text = if (state.towersUpdatedAtMs > 0L) {
                        "Last updated  " + DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT
                        ).format(Date(state.towersUpdatedAtMs))
                    } else if (state.towers.isNotEmpty()) {
                        "Last updated  —"
                    } else {
                        "Upload towers in Settings → Tower data"
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

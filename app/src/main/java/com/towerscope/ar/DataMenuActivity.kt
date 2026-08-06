package com.towerscope.ar

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import kotlinx.coroutines.launch

/**
 * Separate screen for importing / clearing KML-KMZ tower data.
 */
class DataMenuActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var sourceStatus: TextView
    private lateinit var menuMessage: TextView

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let(viewModel::loadTowersFromUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_menu)
        // Same activity-scoped VM is not shared across activities; use application-scoped
        // by reading/writing through the same ViewModel class bound to this activity —
        // persistence is via TowerFileStore, and MainActivity observes its own VM which
        // restores on create. Prefer ProcessLifecycle-friendly approach: use AndroidViewModel
        // + file store; MainActivity reloads when resumed.
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        sourceStatus = findViewById(R.id.sourceStatus)
        menuMessage = findViewById(R.id.menuMessage)

        findViewById<Button>(R.id.loadKmlButton).setOnClickListener {
            filePickerLauncher.launch(
                arrayOf(
                    "application/vnd.google-earth.kml+xml",
                    "application/vnd.google-earth.kmz",
                    "application/xml",
                    "text/xml",
                    "application/zip",
                    "*/*"
                )
            )
        }
        findViewById<Button>(R.id.sampleButton).setOnClickListener { viewModel.loadSampleTowers() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { viewModel.clearSavedTowers() }
        findViewById<Button>(R.id.doneButton).setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val source = state.sourceName
                    sourceStatus.text = when {
                        state.towers.isEmpty() -> "No towers loaded"
                        source != null -> "Loaded ${state.towers.size} towers\nSource: $source"
                        else -> "Loaded ${state.towers.size} towers"
                    }
                    val message = state.errorMessage ?: state.statusMessage
                    menuMessage.isVisible = message != null
                    menuMessage.text = message.orEmpty()
                    menuMessage.setTextColor(
                        if (state.errorMessage != null) 0xFFFF6B6B.toInt() else 0xFFFFFFFF.toInt()
                    )
                }
            }
        }
    }
}

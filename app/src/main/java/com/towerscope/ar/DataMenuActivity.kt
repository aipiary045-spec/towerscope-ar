package com.towerscope.ar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.towerscope.ar.data.CsvTowerParser
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import kotlinx.coroutines.launch
/**
 * Import / clear network site files (KML, KMZ, vendor CSV).
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_data_menu)
        SystemBars.apply(findViewById(R.id.dataMenuRoot))
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        sourceStatus = findViewById(R.id.sourceStatus)
        menuMessage = findViewById(R.id.menuMessage)

        findViewById<MaterialButton>(R.id.loadKmlButton).setOnClickListener {
            filePickerLauncher.launch(
                arrayOf(
                    "application/vnd.google-earth.kml+xml",
                    "application/vnd.google-earth.kmz",
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "application/xml",
                    "text/xml",
                    "application/zip",
                    "*/*"
                )
            )
        }
        findViewById<MaterialButton>(R.id.shareCsvTemplateButton).setOnClickListener {
            shareCsvTemplate()
        }
        findViewById<MaterialButton>(R.id.clearButton).setOnClickListener { viewModel.clearSavedTowers() }
        findViewById<MaterialButton>(R.id.doneButton).setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val source = state.sourceName
                    sourceStatus.text = when {
                        state.towers.isEmpty() -> "No sites loaded"
                        source != null -> "Loaded ${state.towers.size} sites\nSource: $source"
                        else -> "Loaded ${state.towers.size} sites"
                    }
                    val message = state.errorMessage
                        ?: state.statusMessage?.takeIf {
                            !it.startsWith("Loaded") && !it.startsWith("Restored")
                        }
                    menuMessage.isVisible = message != null
                    menuMessage.text = message.orEmpty()
                    menuMessage.setTextColor(
                        ContextCompat.getColor(
                            this@DataMenuActivity,
                            if (state.errorMessage != null) R.color.status_blocked else R.color.text_muted
                        )
                    )
                }
            }
        }
    }

    private fun shareCsvTemplate() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WispEaze CSV site template")
            putExtra(Intent.EXTRA_TEXT, CsvTowerParser.templateCsv())
        }
        startActivity(Intent.createChooser(intent, "Share CSV template"))
    }
}

package com.towerscope.ar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
 * App hub — compass, elevation profiles, settings, and KML/KMZ upload.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var viewModel: TowerScopeViewModel
    private lateinit var sourceStatus: TextView
    private lateinit var updatedAt: TextView
    private lateinit var uploadMessage: TextView

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let(viewModel::loadTowersFromUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)
        SystemBars.apply(findViewById(R.id.homeRoot))
        viewModel = ViewModelProvider(this)[TowerScopeViewModel::class.java]

        sourceStatus = findViewById(R.id.homeSourceStatus)
        updatedAt = findViewById(R.id.homeUpdatedAt)
        uploadMessage = findViewById(R.id.homeUploadMessage)

        findViewById<MaterialButton>(R.id.homeUploadButton).setOnClickListener {
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
        findViewById<View>(R.id.homeArButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
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
                        "Upload a KML/KMZ to get started"
                    }
                    val message = state.errorMessage
                        ?: state.statusMessage?.takeIf {
                            !it.startsWith("Loaded") && !it.startsWith("Restored")
                        }
                    uploadMessage.isVisible = message != null
                    uploadMessage.text = message.orEmpty()
                    uploadMessage.setTextColor(
                        if (state.errorMessage != null) {
                            getColor(R.color.status_blocked)
                        } else {
                            getColor(R.color.text_muted)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncFromFileStore()
    }
}

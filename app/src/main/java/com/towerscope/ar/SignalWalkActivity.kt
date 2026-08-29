package com.towerscope.ar

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.SignalWalkLogger
import com.towerscope.ar.network.SignalWalkSnapshot
import com.towerscope.ar.network.TestResultExport
import com.towerscope.ar.network.WifiMonitor
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

class SignalWalkActivity : AppCompatActivity() {

    private lateinit var monitor: WifiMonitor
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var toggleButton: MaterialButton
    private var recordJob: Job? = null
    private var lastSnapshot = SignalWalkSnapshot(emptyList(), false, 0.0)
    private lateinit var fusedLocation: FusedLocationProviderClient

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* optional GPS */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_signal_walk)
        SystemBars.apply(findViewById(R.id.signalWalkRoot))
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        monitor = WifiMonitor(this)

        statusView = findViewById(R.id.signalWalkStatus)
        resultView = findViewById(R.id.signalWalkResult)
        toggleButton = findViewById(R.id.signalWalkToggleButton)

        toggleButton.setOnClickListener { toggleRecording() }
        findViewById<MaterialButton>(R.id.toolShareButton).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.toolBackButton).setOnClickListener {
            recordJob?.cancel()
            finish()
        }

        requestLocationIfNeeded()
    }

    override fun onStop() {
        recordJob?.cancel()
        super.onStop()
    }

    private fun requestLocationIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun toggleRecording() {
        if (recordJob?.isActive == true) {
            recordJob?.cancel()
            toggleButton.text = getString(R.string.tool_run)
            statusView.text = "Stopped"
            resultView.text = SignalWalkLogger.summarize(lastSnapshot)
            return
        }
        toggleButton.text = getString(R.string.tool_stop)
        statusView.text = "Recording… walk around"
        recordJob = lifecycleScope.launch {
            SignalWalkLogger.record(monitor) {
                lastLocation()
            }.catch { }
                .collect { snapshot ->
                    lastSnapshot = snapshot
                    val latest = snapshot.samples.lastOrNull()?.rssiDbm
                    statusView.text = buildString {
                        append(String.format(Locale.US, "%.0fs", snapshot.durationSec))
                        latest?.let { append(" · $it dBm") }
                    }
                }
        }
    }

    private suspend fun lastLocation(): Pair<Double?, Double?> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null to null
        }
        return runCatching {
            val loc: Location? = fusedLocation.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            loc?.latitude to loc?.longitude
        }.getOrDefault(null to null)
    }

    private fun share() {
        if (lastSnapshot.samples.isEmpty()) return
        TestResultExport.shareText(this, "Signal walk", SignalWalkLogger.exportCsv(lastSnapshot))
    }
}

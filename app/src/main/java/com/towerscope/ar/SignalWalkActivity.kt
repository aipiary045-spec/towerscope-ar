package com.towerscope.ar

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.towerscope.ar.network.SignalWalkLogger
import com.towerscope.ar.network.SignalWalkSample
import com.towerscope.ar.network.SignalWalkSnapshot
import com.towerscope.ar.network.SignalWalkWeakSpot
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
    private var lastSnapshot = SignalWalkSnapshot(emptyList(), emptyList(), false, 0.0)
    private lateinit var fusedLocation: FusedLocationProviderClient

    private val weakSpots = mutableListOf<SignalWalkWeakSpot>()
    private var previousRssi: Int? = null
    private var weakSpotArmed = true
    private var weakSpotDialogShowing = false
    private var weakSpotDialog: AlertDialog? = null

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
            stopRecording()
            finish()
        }

        requestLocationIfNeeded()
    }

    override fun onDestroy() {
        weakSpotDialog?.dismiss()
        weakSpotDialog = null
        super.onDestroy()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            stopRecording()
        }
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
            stopRecording()
            return
        }
        resetWeakSpotState()
        weakSpots.clear()
        toggleButton.text = getString(R.string.tool_stop)
        statusView.text = "Recording… walk around"
        resultView.text = "Walking… weak spots below ${SignalWalkLogger.WEAK_SPOT_THRESHOLD_DBM} dBm will prompt for a location note."
        recordJob = lifecycleScope.launch {
            SignalWalkLogger.record(monitor) {
                lastLocation()
            }.catch { }
                .collect { snapshot ->
                    val merged = snapshot.copy(weakSpots = weakSpots.toList())
                    lastSnapshot = merged
                    val latest = snapshot.samples.lastOrNull()
                    updateStatus(merged, latest?.rssiDbm)
                    latest?.rssiDbm?.let { rssi ->
                        handleWeakSpotSample(rssi, latest, merged.durationSec)
                    }
                }
        }
    }

    private fun stopRecording() {
        recordJob?.cancel()
        recordJob = null
        weakSpotDialog?.dismiss()
        weakSpotDialog = null
        weakSpotDialogShowing = false
        toggleButton.text = getString(R.string.tool_run)
        if (lastSnapshot.samples.isNotEmpty()) {
            statusView.text = "Stopped"
            resultView.text = SignalWalkLogger.summarize(lastSnapshot)
        }
    }

    private fun resetWeakSpotState() {
        previousRssi = null
        weakSpotArmed = true
        weakSpotDialogShowing = false
    }

    private fun updateStatus(snapshot: SignalWalkSnapshot, latestRssi: Int?) {
        statusView.text = buildString {
            append(String.format(Locale.US, "%.0fs", snapshot.durationSec))
            latestRssi?.let { append(" · $it dBm") }
            if (weakSpots.isNotEmpty()) {
                append(" · ${weakSpots.size} spot")
                if (weakSpots.size != 1) append("s")
            }
        }
    }

    private fun handleWeakSpotSample(rssi: Int, sample: SignalWalkSample, durationSec: Double) {
        if (SignalWalkLogger.isWeakSpotRecovered(rssi)) {
            weakSpotArmed = true
        }
        if (SignalWalkLogger.shouldPromptWeakSpot(previousRssi, rssi, weakSpotArmed) &&
            !weakSpotDialogShowing
        ) {
            weakSpotArmed = false
            showWeakSpotDialog(rssi, sample, durationSec)
        }
        previousRssi = rssi
    }

    private fun showWeakSpotDialog(rssi: Int, sample: SignalWalkSample, durationSec: Double) {
        if (weakSpotDialogShowing || isFinishing) return
        weakSpotDialogShowing = true

        val dialogView = layoutInflater.inflate(R.layout.dialog_signal_weak_spot, null)
        dialogView.findViewById<TextView>(R.id.weakSpotMessage).text =
            getString(R.string.signal_walk_weak_message, rssi)
        val gpsView = dialogView.findViewById<TextView>(R.id.weakSpotGps)
        if (sample.latitude != null && sample.longitude != null) {
            gpsView.text = getString(
                R.string.signal_walk_weak_gps,
                sample.latitude,
                sample.longitude
            )
        } else {
            gpsView.text = getString(R.string.signal_walk_weak_no_gps)
        }
        val noteInput = dialogView.findViewById<TextInputEditText>(R.id.weakSpotNoteInput)

        weakSpotDialog = AlertDialog.Builder(this)
            .setTitle(R.string.signal_walk_weak_title)
            .setView(dialogView)
            .setPositiveButton(R.string.signal_walk_weak_save, null)
            .setNegativeButton(R.string.signal_walk_weak_skip) { _, _ ->
                onWeakSpotDialogDismissed()
            }
            .setOnCancelListener {
                onWeakSpotDialogDismissed()
            }
            .create()

        weakSpotDialog?.setOnShowListener {
            weakSpotDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val note = noteInput.text?.toString()?.trim().orEmpty()
                weakSpots += SignalWalkWeakSpot(
                    timestampMs = sample.timestampMs,
                    elapsedSec = durationSec,
                    rssiDbm = rssi,
                    latitude = sample.latitude,
                    longitude = sample.longitude,
                    note = note
                )
                lastSnapshot = lastSnapshot.copy(weakSpots = weakSpots.toList())
                weakSpotDialog?.dismiss()
                onWeakSpotDialogDismissed()
            }
        }
        weakSpotDialog?.show()
    }

    private fun onWeakSpotDialogDismissed() {
        weakSpotDialogShowing = false
        weakSpotDialog = null
        if (recordJob?.isActive == true) {
            statusView.text = buildString {
                append("Recording… walk around")
                if (weakSpots.isNotEmpty()) {
                    append(" · ${weakSpots.size} spot")
                    if (weakSpots.size != 1) append("s logged")
                    else append(" logged")
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
            val loc: Location? = fusedLocation.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                null
            ).await()
            loc?.latitude to loc?.longitude
        }.getOrDefault(null to null)
    }

    private fun share() {
        if (lastSnapshot.samples.isEmpty()) return
        TestResultExport.shareText(this, "Signal walk", SignalWalkLogger.exportCsv(lastSnapshot))
    }
}

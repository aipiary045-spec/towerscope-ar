package com.towerscope.ar

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.towerscope.ar.location.DeviceHeadingClient
import com.towerscope.ar.network.WifiMonitor
import com.towerscope.ar.ui.BottomNav
import com.towerscope.ar.ui.BottomNavTab
import com.towerscope.ar.ui.SettingsBottomSheet
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.util.GeoUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * WispEaze home faceplate: live RF + AIM readouts that open each hub.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var wifi: WifiMonitor
    private lateinit var headingClient: DeviceHeadingClient
    private lateinit var rfReadout: TextView
    private lateinit var aimReadout: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)
        SystemBars.apply(
            root = findViewById(R.id.homeRoot),
            alsoBottom = findViewById(R.id.homeBottomNav)
        )

        wifi = WifiMonitor(this)
        headingClient = DeviceHeadingClient(this)
        rfReadout = findViewById(R.id.homeRfReadout)
        aimReadout = findViewById(R.id.homeAimReadout)

        findViewById<View>(R.id.homeNetworkHubButton).setOnClickListener {
            startActivity(Intent(this, NetworkHubActivity::class.java))
        }
        findViewById<View>(R.id.homeInstallHubButton).setOnClickListener {
            startActivity(Intent(this, InstallationHubActivity::class.java))
        }
        findViewById<View>(R.id.homeSettingsButton).setOnClickListener {
            openSettings()
        }
        BottomNav.bind(this, BottomNavTab.HOME)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    headingClient.headingUpdates().collect { heading ->
                        val offset = headingOffsetDegrees()
                        val degrees = GeoUtils.normalizeBearing(heading.degrees + offset)
                        aimReadout.text = String.format(Locale.US, "%03.0f", degrees)
                    }
                }
                while (isActive) {
                    refreshRf()
                    delay(1_000L)
                }
            }
        }
    }

    private fun refreshRf() {
        val rssi = wifi.currentLink().rssiDbm
        if (rssi == null) {
            rfReadout.text = "---"
            rfReadout.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            return
        }
        rfReadout.text = String.format(Locale.US, "%d", rssi)
        rfReadout.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    rssi >= -60 -> R.color.accent_teal
                    rssi >= -75 -> R.color.accent_yellow
                    else -> R.color.chip_poor
                }
            )
        )
    }

    private fun headingOffsetDegrees(): Double {
        val prefs = getSharedPreferences("towerscope_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains("heading_calibration_offset_deg")) return 0.0
        val value = prefs.getFloat("heading_calibration_offset_deg", 0f).toDouble()
        return if (value.isFinite()) value else 0.0
    }

    private fun openSettings() {
        if (supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) == null) {
            SettingsBottomSheet.newInstance().show(supportFragmentManager, SettingsBottomSheet.TAG)
        }
    }
}

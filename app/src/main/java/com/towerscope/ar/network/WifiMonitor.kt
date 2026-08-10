package com.towerscope.ar.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class WifiLinkStatus(
    val ssid: String?,
    val bssid: String?,
    val rssiDbm: Int?,
    val linkSpeedMbps: Int?,
    val frequencyMhz: Int?,
    val connected: Boolean
)

data class WifiScanAp(
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val channel: Int
)

/**
 * Live Wi‑Fi connection status + nearby AP scan results.
 */
class WifiMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun currentLink(): WifiLinkStatus {
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo
        val rssi = info?.rssi?.takeIf { it != Int.MIN_VALUE && it != -127 }
        val ssid = info?.ssid
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
        return WifiLinkStatus(
            ssid = ssid,
            bssid = info?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" },
            rssiDbm = rssi,
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
            frequencyMhz = info?.frequency?.takeIf { it > 0 },
            connected = wifi.isWifiEnabled && rssi != null
        )
    }

    fun signalQualityLabel(rssiDbm: Int?): String = when {
        rssiDbm == null -> "—"
        rssiDbm >= -50 -> "Excellent"
        rssiDbm >= -60 -> "Good"
        rssiDbm >= -70 -> "Fair"
        rssiDbm >= -80 -> "Weak"
        else -> "Poor"
    }

    fun startScan(): Boolean = runCatching { wifi.startScan() }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun latestScanResults(): List<WifiScanAp> {
        val results = wifi.scanResults ?: emptyList()
        return results
            .mapNotNull { it.toScanAp() }
            .sortedByDescending { it.rssiDbm }
            .distinctBy { it.bssid }
    }

    fun scanUpdates(): Flow<List<WifiScanAp>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(latestScanResults())
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        trySend(latestScanResults())
        startScan()
        awaitClose {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private fun ScanResult.toScanAp(): WifiScanAp? {
        val name = SSID?.trim().orEmpty()
        val id = BSSID?.trim().orEmpty()
        if (id.isEmpty()) return null
        return WifiScanAp(
            ssid = name.ifBlank { "(hidden)" },
            bssid = id,
            rssiDbm = level,
            frequencyMhz = frequency,
            channel = channelFromMhz(frequency)
        )
    }

    companion object {
        fun channelFromMhz(mhz: Int): Int = when {
            mhz in 2412..2484 -> ((mhz - 2412) / 5) + 1
            mhz in 5170..5825 -> ((mhz - 5000) / 5)
            mhz in 5955..7115 -> ((mhz - 5955) / 5) + 1
            else -> 0
        }

        fun formatBand(mhz: Int): String = when {
            mhz in 2400..2500 -> "2.4 GHz"
            mhz in 4900..5900 -> "5 GHz"
            mhz in 5900..7200 -> "6 GHz"
            else -> "$mhz MHz"
        }
    }
}

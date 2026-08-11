package com.towerscope.ar.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import kotlin.math.abs
import kotlin.math.sqrt

data class WifiLinkStatus(
    val ssid: String?,
    val bssid: String?,
    val rssiDbm: Int?,
    val linkSpeedMbps: Int?,
    val frequencyMhz: Int?,
    val channel: Int?,
    val band: String?,
    val connected: Boolean
)

data class WifiScanAp(
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val channel: Int,
    val band: String,
    val overlapWithActive: Boolean,
    val coChannelWithActive: Boolean
)

data class WifiChannelAnalysis(
    val activeChannel: Int?,
    val activeBand: String?,
    val activeFrequencyMhz: Int?,
    val coChannelCount: Int,
    val overlappingCount: Int,
    val strongestCoChannelRssi: Int?,
    val overlapSummary: String,
    val interferenceHints: List<String>,
    val interferenceLevel: InterferenceLevel
)

enum class InterferenceLevel {
    LOW,
    MODERATE,
    HIGH,
    UNKNOWN
}

/**
 * Live Wi‑Fi connection status + nearby AP scan with channel overlap /
 * heuristic RF interference indicators (phone radios only — not a spectrum analyzer).
 */
class WifiMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val rssiHistory = ArrayDeque<Int>(24)

    fun currentLink(): WifiLinkStatus {
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo
        val rssi = info?.rssi?.takeIf { it != Int.MIN_VALUE && it != -127 }
        if (rssi != null) {
            rssiHistory.addLast(rssi)
            while (rssiHistory.size > 20) rssiHistory.removeFirst()
        }
        val ssid = info?.ssid
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
        val freq = info?.frequency?.takeIf { it > 0 }
        val channel = freq?.let { channelFromMhz(it) }?.takeIf { it > 0 }
        return WifiLinkStatus(
            ssid = ssid,
            bssid = info?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" },
            rssiDbm = rssi,
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
            frequencyMhz = freq,
            channel = channel,
            band = freq?.let { formatBand(it) },
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

    fun analyzeChannels(link: WifiLinkStatus, scan: List<WifiScanAp>): WifiChannelAnalysis {
        val activeCh = link.channel
        val activeBand = link.band
        val activeMhz = link.frequencyMhz
        val others = scan.filter { ap ->
            link.bssid == null || !ap.bssid.equals(link.bssid, ignoreCase = true)
        }
        val coChannel = if (activeCh != null && activeCh > 0) {
            others.filter { it.channel == activeCh && sameBand(it.frequencyMhz, activeMhz) }
        } else {
            emptyList()
        }
        val overlapping = if (activeCh != null && activeCh > 0 && activeMhz != null) {
            others.filter { overlaps(activeCh, activeMhz, it.channel, it.frequencyMhz) }
        } else {
            emptyList()
        }
        val strongestCo = coChannel.maxByOrNull { it.rssiDbm }?.rssiDbm
        val hints = mutableListOf<String>()
        var score = 0

        if (activeCh == null) {
            hints += "Connect to Wi‑Fi to evaluate your active channel."
        } else {
            if (coChannel.isNotEmpty()) {
                score += minOf(3, coChannel.size)
                hints += "${coChannel.size} other AP${if (coChannel.size == 1) "" else "s"} on channel $activeCh (co‑channel)."
            }
            val adjacentOnly = overlapping.filter { it.channel != activeCh }
            if (adjacentOnly.isNotEmpty()) {
                score += 1
                hints += "${adjacentOnly.size} nearby AP${if (adjacentOnly.size == 1) "" else "s"} on overlapping 2.4 GHz channels."
            }
            if (activeMhz in 2400..2500 && isBluetoothEnabled()) {
                score += 2
                hints += "Bluetooth is on · can raise 2.4 GHz noise (microwave / BT / Zigbee band)."
            }
            val variance = rssiStdDev()
            if (variance != null && variance >= 6.0 && (link.rssiDbm ?: -999) > -85) {
                score += 2
                hints += String.format(
                    java.util.Locale.US,
                    "RSSI unstable (σ ≈ %.1f dB) · possible non‑Wi‑Fi interference or multipath.",
                    variance
                )
            }
            if (link.rssiDbm != null && link.linkSpeedMbps != null &&
                link.rssiDbm >= -55 && link.linkSpeedMbps < 50 && activeMhz in 2400..2500
            ) {
                score += 1
                hints += "Strong RSSI but low link rate on 2.4 GHz · airtime contention or interference likely."
            }
        }

        if (hints.isEmpty() && activeCh != null) {
            hints += "No strong interference indicators from phone sensors right now."
        }

        val level = when {
            activeCh == null -> InterferenceLevel.UNKNOWN
            score >= 5 -> InterferenceLevel.HIGH
            score >= 2 -> InterferenceLevel.MODERATE
            else -> InterferenceLevel.LOW
        }

        val summary = when {
            activeCh == null -> "No active channel"
            overlapping.isEmpty() -> "Channel $activeCh clear of nearby Wi‑Fi overlap"
            else -> "Channel $activeCh · ${coChannel.size} co‑channel · ${overlapping.size} overlapping"
        }

        return WifiChannelAnalysis(
            activeChannel = activeCh,
            activeBand = activeBand,
            activeFrequencyMhz = activeMhz,
            coChannelCount = coChannel.size,
            overlappingCount = overlapping.size,
            strongestCoChannelRssi = strongestCo,
            overlapSummary = summary,
            interferenceHints = hints,
            interferenceLevel = level
        )
    }

    fun annotateScan(link: WifiLinkStatus, results: List<WifiScanAp>): List<WifiScanAp> {
        val activeCh = link.channel
        val activeMhz = link.frequencyMhz
        return results.map { ap ->
            val same = link.bssid != null && ap.bssid.equals(link.bssid, ignoreCase = true)
            val co = !same && activeCh != null && ap.channel == activeCh &&
                sameBand(ap.frequencyMhz, activeMhz)
            val overlap = !same && activeCh != null && activeMhz != null &&
                overlaps(activeCh, activeMhz, ap.channel, ap.frequencyMhz)
            ap.copy(coChannelWithActive = co, overlapWithActive = overlap)
        }
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

    private fun isBluetoothEnabled(): Boolean {
        return try {
            val mgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = mgr?.adapter
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun rssiStdDev(): Double? {
        if (rssiHistory.size < 5) return null
        val mean = rssiHistory.average()
        val varSum = rssiHistory.sumOf { (it - mean) * (it - mean) }
        return sqrt(varSum / rssiHistory.size)
    }

    private fun ScanResult.toScanAp(): WifiScanAp? {
        val name = SSID?.trim().orEmpty()
        val id = BSSID?.trim().orEmpty()
        if (id.isEmpty()) return null
        val ch = channelFromMhz(frequency)
        return WifiScanAp(
            ssid = name.ifBlank { "(hidden)" },
            bssid = id,
            rssiDbm = level,
            frequencyMhz = frequency,
            channel = ch,
            band = formatBand(frequency),
            overlapWithActive = false,
            coChannelWithActive = false
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

        fun sameBand(aMhz: Int?, bMhz: Int?): Boolean {
            if (aMhz == null || bMhz == null) return false
            return formatBand(aMhz) == formatBand(bMhz)
        }

        /** 2.4 GHz 20 MHz channels overlap when |Δch| < 5; 5/6 GHz treated as co-channel only. */
        fun overlaps(activeCh: Int, activeMhz: Int, otherCh: Int, otherMhz: Int): Boolean {
            if (otherCh <= 0 || activeCh <= 0) return false
            if (!sameBand(activeMhz, otherMhz)) return false
            return if (activeMhz in 2400..2500) {
                abs(activeCh - otherCh) < 5
            } else {
                activeCh == otherCh
            }
        }

        fun interferenceLabel(level: InterferenceLevel): String = when (level) {
            InterferenceLevel.LOW -> "Low"
            InterferenceLevel.MODERATE -> "Moderate"
            InterferenceLevel.HIGH -> "Elevated"
            InterferenceLevel.UNKNOWN -> "—"
        }
    }
}

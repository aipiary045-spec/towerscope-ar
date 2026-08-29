package com.towerscope.ar.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SignalWalkSample(
    val timestampMs: Long,
    val elapsedSec: Double,
    val rssiDbm: Int?,
    val ssid: String?,
    val channel: Int?,
    val latitude: Double?,
    val longitude: Double?
)

data class SignalWalkSnapshot(
    val samples: List<SignalWalkSample>,
    val running: Boolean,
    val durationSec: Double
)

/**
 * Records Wi‑Fi signal strength over time while walking a site.
 */
object SignalWalkLogger {

    fun record(
        monitor: WifiMonitor,
        intervalMs: Long = 1_000L,
        locationProvider: suspend () -> Pair<Double?, Double?> = { null to null }
    ): Flow<SignalWalkSnapshot> = flow {
        val samples = mutableListOf<SignalWalkSample>()
        val start = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        emit(SignalWalkSnapshot(samples.toList(), running = true, durationSec = 0.0))
        while (true) {
            val now = System.currentTimeMillis()
            val link = monitor.currentLink()
            val (lat, lon) = locationProvider()
            samples += SignalWalkSample(
                timestampMs = now,
                elapsedSec = (now - start) / 1000.0,
                rssiDbm = link.rssiDbm,
                ssid = link.ssid,
                channel = link.channel,
                latitude = lat,
                longitude = lon
            )
            emit(
                SignalWalkSnapshot(
                    samples = samples.toList(),
                    running = true,
                    durationSec = (now - start) / 1000.0
                )
            )
            delay(intervalMs.coerceAtLeast(500L))
        }
    }.flowOn(Dispatchers.IO)

    fun summarize(snapshot: SignalWalkSnapshot): String {
        val rssis = snapshot.samples.mapNotNull { it.rssiDbm }
        if (rssis.isEmpty()) return "No samples recorded"
        val min = rssis.minOrNull()!!
        val max = rssis.maxOrNull()!!
        val avg = rssis.average()
        val weak = rssis.count { it < -75 }
        return buildString {
            appendLine("Signal walk (${String.format(Locale.US, "%.0f", snapshot.durationSec)}s)")
            appendLine("Samples: ${rssis.size}")
            appendLine(String.format(Locale.US, "RSSI: avg %.0f dBm · min %d · max %d", avg, min, max))
            appendLine("Weak spots (< -75 dBm): $weak")
        }.trim()
    }

    fun exportCsv(snapshot: SignalWalkSnapshot): String {
        val header = "elapsed_sec,rssi_dbm,ssid,channel,latitude,longitude,timestamp"
        val rows = snapshot.samples.map { s ->
            listOf(
                String.format(Locale.US, "%.1f", s.elapsedSec),
                s.rssiDbm?.toString() ?: "",
                s.ssid?.replace(',', ' ') ?: "",
                s.channel?.toString() ?: "",
                s.latitude?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                s.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(s.timestampMs))
            ).joinToString(",")
        }
        return (listOf(header) + rows).joinToString("\n")
    }
}

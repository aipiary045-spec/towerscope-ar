package com.towerscope.ar.network

import android.net.TrafficStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

data class BandwidthSample(
    val timestampMs: Long,
    /** Total RX rate across the device (Mbps). */
    val rxMbps: Double,
    /** Total TX rate across the device (Mbps). */
    val txMbps: Double,
    val mobileRxMbps: Double,
    val mobileTxMbps: Double,
    val wifiRxMbps: Double,
    val wifiTxMbps: Double,
    val totalRxBytes: Long,
    val totalTxBytes: Long
)

/**
 * Real-time throughput for this device using [TrafficStats].
 *
 * Note: Android cannot see other LAN devices' traffic without router SNMP/API access.
 * "Per-device" here means mobile vs Wi‑Fi on this phone (plus overall).
 */
object BandwidthMonitor {

    fun stream(intervalMs: Long = 1_000L): Flow<BandwidthSample> = flow {
        var prevTotalRx = TrafficStats.getTotalRxBytes().takeIf { it >= 0 } ?: 0L
        var prevTotalTx = TrafficStats.getTotalTxBytes().takeIf { it >= 0 } ?: 0L
        var prevMobileRx = TrafficStats.getMobileRxBytes().takeIf { it >= 0 } ?: 0L
        var prevMobileTx = TrafficStats.getMobileTxBytes().takeIf { it >= 0 } ?: 0L
        var prevTs = System.nanoTime()

        // Prime one interval so the first sample isn't a cold zero.
        delay(intervalMs.coerceAtLeast(400L))

        while (currentCoroutineContext().isActive) {
            val now = System.nanoTime()
            val totalRx = TrafficStats.getTotalRxBytes().takeIf { it >= 0 } ?: prevTotalRx
            val totalTx = TrafficStats.getTotalTxBytes().takeIf { it >= 0 } ?: prevTotalTx
            val mobileRx = TrafficStats.getMobileRxBytes().takeIf { it >= 0 } ?: prevMobileRx
            val mobileTx = TrafficStats.getMobileTxBytes().takeIf { it >= 0 } ?: prevMobileTx
            val dtSec = ((now - prevTs) / 1_000_000_000.0).coerceAtLeast(0.001)

            val rxMbps = bytesToMbps(totalRx - prevTotalRx, dtSec)
            val txMbps = bytesToMbps(totalTx - prevTotalTx, dtSec)
            val mobileRxMbps = bytesToMbps(mobileRx - prevMobileRx, dtSec)
            val mobileTxMbps = bytesToMbps(mobileTx - prevMobileTx, dtSec)
            // Wi‑Fi ≈ total − mobile (VPN may blur this; still useful in the field).
            val wifiRxMbps = (rxMbps - mobileRxMbps).coerceAtLeast(0.0)
            val wifiTxMbps = (txMbps - mobileTxMbps).coerceAtLeast(0.0)

            emit(
                BandwidthSample(
                    timestampMs = System.currentTimeMillis(),
                    rxMbps = rxMbps,
                    txMbps = txMbps,
                    mobileRxMbps = mobileRxMbps,
                    mobileTxMbps = mobileTxMbps,
                    wifiRxMbps = wifiRxMbps,
                    wifiTxMbps = wifiTxMbps,
                    totalRxBytes = totalRx,
                    totalTxBytes = totalTx
                )
            )

            prevTotalRx = totalRx
            prevTotalTx = totalTx
            prevMobileRx = mobileRx
            prevMobileTx = mobileTx
            prevTs = now
            delay(intervalMs.coerceAtLeast(400L))
        }
    }.flowOn(Dispatchers.Default)

    private fun bytesToMbps(deltaBytes: Long, dtSec: Double): Double {
        if (deltaBytes <= 0L) return 0.0
        return (deltaBytes * 8.0) / dtSec / 1_000_000.0
    }

    fun formatMbps(value: Double): String =
        when {
            !value.isFinite() -> "—"
            value < 0.1 -> String.format(java.util.Locale.US, "%.2f Mbps", value)
            value < 10 -> String.format(java.util.Locale.US, "%.1f Mbps", value)
            else -> String.format(java.util.Locale.US, "%.0f Mbps", value)
        }
}

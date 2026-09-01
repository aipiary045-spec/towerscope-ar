package com.towerscope.ar.ui

import android.content.Context
import com.towerscope.ar.network.NetworkSession
import com.towerscope.ar.network.PingMonitor
import com.towerscope.ar.network.SpeedTestClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

/**
 * Rolling ICMP probe + periodic quick download sample for the Network Hub Internet tab.
 */
class InternetLiveMonitor(
    private val pingHost: String = "1.1.1.1",
    private val quickSpeedIntervalMs: Long = 180_000L,
    private val maxSamples: Int = 12
) {
    data class State(
        val host: String,
        val lastMs: Double?,
        val avgMs: Double?,
        val jitterMs: Double?,
        val lossPercent: Double,
        val sampleCount: Int,
        val quickSpeedRunning: Boolean,
        val quickSpeedMbps: Double?,
        val quickSpeedAtMs: Long?,
        val cachedSpeed: NetworkSession.SpeedSnapshot?,
        val speedAgeMs: Long?
    )

    private val samples = ArrayDeque<Double>()
    private var attempts = 0
    private var losses = 0
    private var lastMs: Double? = null
    private var quickSpeedRunning = false
    private var lastQuickSpeedAt = 0L
    private var lastQuickSpeedMbps: Double? = null
    private val quickMutex = Mutex()

    suspend fun tick(context: Context, forceQuickSpeed: Boolean = false): State {
        val ping = PingMonitor.pingOnce(pingHost)
        attempts++
        if (ping.success && ping.latencyMs != null) {
            lastMs = ping.latencyMs
            if (samples.size >= maxSamples) samples.removeFirst()
            samples.addLast(ping.latencyMs)
        } else {
            losses++
        }
        val stats = computeStats()
        NetworkSession.recordLivePing(
            context = context,
            host = pingHost,
            avgMs = stats.avg,
            jitterMs = stats.jitter,
            lossPercent = stats.lossPercent,
            sampleCount = stats.sampleCount
        )

        val now = System.currentTimeMillis()
        val shouldQuick = forceQuickSpeed ||
            (!quickSpeedRunning && now - lastQuickSpeedAt >= quickSpeedIntervalMs)
        if (shouldQuick) {
            quickMutex.withLock {
                if (!quickSpeedRunning) {
                    quickSpeedRunning = true
                    try {
                        val quick = SpeedTestClient.runQuick()
                        if (quick.downloadMbps.isFinite() && quick.downloadMbps > 0) {
                            lastQuickSpeedMbps = quick.downloadMbps
                        }
                    } finally {
                        quickSpeedRunning = false
                        lastQuickSpeedAt = System.currentTimeMillis()
                    }
                }
            }
        }
        return snapshot(context)
    }

    fun snapshot(context: Context): State {
        val stats = computeStats()
        return State(
            host = pingHost,
            lastMs = lastMs,
            avgMs = stats.avg,
            jitterMs = stats.jitter,
            lossPercent = stats.lossPercent,
            sampleCount = stats.sampleCount,
            quickSpeedRunning = quickSpeedRunning,
            quickSpeedMbps = lastQuickSpeedMbps,
            quickSpeedAtMs = lastQuickSpeedAt.takeIf { it > 0L },
            cachedSpeed = NetworkSession.lastSpeedSnapshot(context),
            speedAgeMs = NetworkSession.speedSnapshotAgeMs(context)
        )
    }

    private data class Stats(
        val avg: Double,
        val jitter: Double,
        val lossPercent: Double,
        val sampleCount: Int
    )

    private fun computeStats(): Stats {
        val lossPercent = if (attempts == 0) 0.0 else (losses * 100.0) / attempts
        if (samples.isEmpty()) {
            return Stats(0.0, 0.0, lossPercent, 0)
        }
        val avg = samples.average()
        val variance = samples.map { (it - avg) * (it - avg) }.average()
        return Stats(avg, sqrt(variance), lossPercent, samples.size)
    }
}

package com.towerscope.ar.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max

data class StabilityProgress(
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val lastMs: Double?,
    val avgMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    val elapsedSec: Double,
    val targetHost: String,
    val running: Boolean
)

data class StabilityResult(
    val targetHost: String,
    val durationSec: Double,
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val avgMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    val jitterMs: Double?
)

/**
 * Sustained ping for 1–5 minutes with rolling stats.
 */
object StabilityPing {

    fun run(
        host: String,
        durationSec: Int = 120,
        intervalMs: Long = 1_000L
    ): Flow<StabilityProgress> = flow {
        val target = PingMonitor.parseTarget(host) ?: PingMonitor.parseTarget("1.1.1.1")!!
        val latencies = ArrayList<Double>()
        var sent = 0
        var received = 0
        val start = System.nanoTime()
        val deadline = start + durationSec.coerceIn(10, 600) * 1_000_000_000L

        while (coroutineContext.isActive && System.nanoTime() < deadline) {
            sent += 1
            val sample = probeTarget(target)
            if (sample != null) {
                received += 1
                latencies += sample
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
            val loss = if (sent == 0) 0.0 else ((sent - received) * 100.0) / sent
            emit(
                StabilityProgress(
                    sent = sent,
                    received = received,
                    lossPercent = loss,
                    lastMs = sample,
                    avgMs = latencies.takeIf { it.isNotEmpty() }?.average(),
                    minMs = latencies.minOrNull(),
                    maxMs = latencies.maxOrNull(),
                    elapsedSec = elapsed,
                    targetHost = target.display,
                    running = true
                )
            )
            delay(max(200L, intervalMs))
        }

        val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
        val loss = if (sent == 0) 0.0 else ((sent - received) * 100.0) / sent
        emit(
            StabilityProgress(
                sent = sent,
                received = received,
                lossPercent = loss,
                lastMs = latencies.lastOrNull(),
                avgMs = latencies.takeIf { it.isNotEmpty() }?.average(),
                minMs = latencies.minOrNull(),
                maxMs = latencies.maxOrNull(),
                elapsedSec = elapsed,
                targetHost = target.display,
                running = false
            )
        )
    }.flowOn(Dispatchers.IO)

    fun toResult(progress: StabilityProgress): StabilityResult {
        val jitter = if (progress.avgMs != null && progress.minMs != null && progress.maxMs != null) {
            (progress.maxMs - progress.minMs) / 2.0
        } else {
            null
        }
        return StabilityResult(
            targetHost = progress.targetHost,
            durationSec = progress.elapsedSec,
            sent = progress.sent,
            received = progress.received,
            lossPercent = progress.lossPercent,
            avgMs = progress.avgMs,
            minMs = progress.minMs,
            maxMs = progress.maxMs,
            jitterMs = jitter
        )
    }

    fun formatResult(result: StabilityResult): String = buildString {
        appendLine("Stability test · ${result.targetHost}")
        appendLine(String.format(Locale.US, "Duration: %.0f s", result.durationSec))
        appendLine("Sent: ${result.sent} · Received: ${result.received}")
        appendLine(String.format(Locale.US, "Loss: %.1f%%", result.lossPercent))
        result.avgMs?.let {
            appendLine(String.format(Locale.US, "Latency: avg %.0f ms · min %.0f · max %.0f", it, result.minMs, result.maxMs))
        }
        result.jitterMs?.let {
            appendLine(String.format(Locale.US, "Jitter (range/2): %.0f ms", it))
        }
    }.trim()

    private fun probeTarget(target: PingTarget): Double? {
        return if (target.tcpPort != null) {
            null // stability test uses ICMP by default
        } else {
            PingMonitor.icmpProbe(target.host, 2_000)
        }
    }
}

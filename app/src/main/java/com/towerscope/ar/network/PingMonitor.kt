package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext
import kotlin.math.max

data class PingSample(
    val sequence: Int,
    val success: Boolean,
    val latencyMs: Double?,
    val host: String,
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val avgMs: Double?,
    val minMs: Double?,
    val maxMs: Double?
)

/**
 * Continuous TCP connect latency probe (on-device). Works without ICMP privileges.
 */
object PingMonitor {

    fun stream(
        host: String,
        port: Int = 443,
        intervalMs: Long = 1_000L,
        timeoutMs: Int = 2_000
    ): Flow<PingSample> = flow {
        val cleaned = host.trim().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':').ifBlank { "1.1.1.1" }
        val latencies = ArrayList<Double>()
        var sent = 0
        var received = 0
        var seq = 0
        while (coroutineContext.isActive) {
            seq += 1
            sent += 1
            val sample = probeOnce(cleaned, port, timeoutMs)
            if (sample != null) {
                received += 1
                latencies += sample
            }
            val loss = if (sent == 0) 0.0 else ((sent - received) * 100.0) / sent
            emit(
                PingSample(
                    sequence = seq,
                    success = sample != null,
                    latencyMs = sample,
                    host = cleaned,
                    sent = sent,
                    received = received,
                    lossPercent = loss,
                    avgMs = latencies.takeIf { it.isNotEmpty() }?.average(),
                    minMs = latencies.minOrNull(),
                    maxMs = latencies.maxOrNull()
                )
            )
            delay(max(200L, intervalMs))
        }
    }.flowOn(Dispatchers.IO)

    private fun probeOnce(host: String, port: Int, timeoutMs: Int): Double? {
        return try {
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.nanoTime() - start) / 1_000_000.0
        } catch (_: Exception) {
            null
        }
    }
}

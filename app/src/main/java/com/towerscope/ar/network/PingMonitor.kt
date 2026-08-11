package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

data class PingHostStats(
    val host: String,
    val port: Int,
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val lastMs: Double?,
    val avgMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    val lastSuccess: Boolean
)

data class PingLogLine(
    val timestamp: String,
    val host: String,
    val success: Boolean,
    val latencyMs: Double?,
    val message: String
)

data class MultiPingSnapshot(
    val hosts: List<PingHostStats>,
    val logLine: PingLogLine?,
    val running: Boolean
)

/**
 * Continuous TCP connect latency probes for one or many hosts in parallel.
 */
object PingMonitor {

    fun parseHostList(raw: String): List<String> {
        return raw
            .split(',', ';', '\n', '\r', '\t', ' ')
            .map { cleanHost(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("1.1.1.1") }
    }

    fun cleanHost(raw: String): String {
        return raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()
    }

    fun stream(
        host: String,
        port: Int = 443,
        intervalMs: Long = 1_000L,
        timeoutMs: Int = 2_000
    ): Flow<PingSample> = channelFlow {
        val cleaned = cleanHost(host).ifBlank { "1.1.1.1" }
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
            send(
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

    /**
     * Ping every host concurrently; emit combined stats + one log line per probe result.
     */
    fun streamMany(
        hosts: List<String>,
        port: Int = 443,
        intervalMs: Long = 1_000L,
        timeoutMs: Int = 2_000
    ): Flow<MultiPingSnapshot> = channelFlow {
        val targets = hosts.map { cleanHost(it) }.filter { it.isNotBlank() }.distinct()
            .ifEmpty { listOf("1.1.1.1") }
        val statsMap = ConcurrentHashMap<String, MutableHostStats>()
        targets.forEach { statsMap[it] = MutableHostStats(it, port) }
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun snapshot(log: PingLogLine?): MultiPingSnapshot {
            val list = targets.map { host ->
                val s = statsMap[host]!!
                PingHostStats(
                    host = host,
                    port = port,
                    sent = s.sent,
                    received = s.received,
                    lossPercent = if (s.sent == 0) 0.0 else ((s.sent - s.received) * 100.0) / s.sent,
                    lastMs = s.lastMs,
                    avgMs = s.latencies.takeIf { it.isNotEmpty() }?.average(),
                    minMs = s.latencies.minOrNull(),
                    maxMs = s.latencies.maxOrNull(),
                    lastSuccess = s.lastSuccess
                )
            }
            return MultiPingSnapshot(hosts = list, logLine = log, running = true)
        }

        send(snapshot(null))

        targets.forEach { host ->
            launch(Dispatchers.IO) {
                while (isActive) {
                    val sample = probeOnce(host, port, timeoutMs)
                    val s = statsMap[host]!!
                    synchronized(s) {
                        s.sent += 1
                        if (sample != null) {
                            s.received += 1
                            s.latencies += sample
                            s.lastMs = sample
                            s.lastSuccess = true
                        } else {
                            s.lastMs = null
                            s.lastSuccess = false
                        }
                    }
                    val stamp = timeFmt.format(Date())
                    val line = if (sample != null) {
                        PingLogLine(
                            timestamp = stamp,
                            host = host,
                            success = true,
                            latencyMs = sample,
                            message = String.format(Locale.US, "%s  %s  %.0f ms", stamp, host, sample)
                        )
                    } else {
                        PingLogLine(
                            timestamp = stamp,
                            host = host,
                            success = false,
                            latencyMs = null,
                            message = "$stamp  $host  timeout"
                        )
                    }
                    send(snapshot(line))
                    delay(max(200L, intervalMs))
                }
            }
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

    private class MutableHostStats(
        val host: String,
        val port: Int,
        var sent: Int = 0,
        var received: Int = 0,
        var lastMs: Double? = null,
        var lastSuccess: Boolean = false,
        val latencies: ArrayList<Double> = ArrayList()
    )
}

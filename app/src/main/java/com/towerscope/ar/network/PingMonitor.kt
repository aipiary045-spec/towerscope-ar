package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class PingSample(
    val sequence: Int,
    val success: Boolean,
    val latencyMs: Double?,
    val host: String,
    val method: String,
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val avgMs: Double?,
    val minMs: Double?,
    val maxMs: Double?
)

data class PingHostStats(
    val host: String,
    val displayTarget: String,
    val method: String,
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

data class PingTarget(
    val host: String,
    /** Optional TCP port; null = ICMP only (no TCP fallback). */
    val tcpPort: Int? = null
) {
    val display: String
        get() = if (tcpPort != null) "$host:$tcpPort" else host
}

data class ProbeResult(
    val latencyMs: Double,
    val method: String
)

/**
 * Continuous multi-host ping using ICMP first (platform `ping`).
 *
 * TCP is used only when the target explicitly includes a port (`host:443`),
 * so ordinary IPs are not marked up just because something accepts HTTPS.
 */
object PingMonitor {

    fun parseHostList(raw: String): List<String> {
        return parseTargets(raw).map { it.display }
    }

    fun parseTargets(raw: String): List<PingTarget> {
        return raw
            .split(',', ';', '\n', '\r', '\t', ' ')
            .mapNotNull { parseTarget(it) }
            .distinctBy { it.display }
            .ifEmpty { listOf(PingTarget("1.1.1.1")) }
    }

    fun parseTarget(raw: String): PingTarget? {
        var s = raw.trim()
        if (s.isBlank()) return null
        s = s.removePrefix("https://").removePrefix("http://").substringBefore('/').trim()
        if (s.isBlank()) return null

        // Bracketed IPv6: [2001:db8::1]:443 or [2001:db8::1]
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end <= 1) return null
            val host = s.substring(1, end)
            val rest = s.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.removePrefix(":").toIntOrNull() else null
            return PingTarget(host, port?.takeIf { it in 1..65535 })
        }

        // IPv4 or hostname with optional :port — do not split bare IPv6 on ':'.
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.count { it == ':' } == 1) {
            val host = s.substring(0, colon).trim()
            val port = s.substring(colon + 1).toIntOrNull()
            if (host.isNotBlank() && port != null && port in 1..65535) {
                return PingTarget(host, port)
            }
        }
        return PingTarget(s, null)
    }

    /** @deprecated use [parseTarget] */
    fun cleanHost(raw: String): String = parseTarget(raw)?.host ?: raw.trim()

    fun stream(
        host: String,
        port: Int = 443,
        intervalMs: Long = 1_000L,
        timeoutMs: Int = 2_000
    ): Flow<PingSample> = channelFlow {
        // Legacy single-host API: treat as ICMP unless caller relied on TCP port.
        val target = parseTarget(host) ?: PingTarget(host)
        val effective = if (target.tcpPort == null && port != 443) {
            target.copy(tcpPort = port)
        } else {
            target
        }
        val latencies = ArrayList<Double>()
        var sent = 0
        var received = 0
        var seq = 0
        while (isActive) {
            seq += 1
            sent += 1
            val sample = probe(effective, timeoutMs)
            if (sample != null) {
                received += 1
                latencies += sample.latencyMs
            }
            val loss = if (sent == 0) 0.0 else ((sent - received) * 100.0) / sent
            send(
                PingSample(
                    sequence = seq,
                    success = sample != null,
                    latencyMs = sample?.latencyMs,
                    host = effective.display,
                    method = sample?.method ?: methodLabel(effective),
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

    fun streamMany(
        hosts: List<String>,
        intervalMs: Long = 1_000L,
        timeoutMs: Int = 2_000
    ): Flow<MultiPingSnapshot> = channelFlow {
        val targets = hosts.mapNotNull { parseTarget(it) }
            .distinctBy { it.display }
            .ifEmpty { listOf(PingTarget("1.1.1.1")) }
        val statsMap = ConcurrentHashMap<String, MutableHostStats>()
        targets.forEach { statsMap[it.display] = MutableHostStats(it) }
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun snapshot(log: PingLogLine?): MultiPingSnapshot {
            val list = targets.map { target ->
                val s = statsMap[target.display]!!
                PingHostStats(
                    host = target.host,
                    displayTarget = target.display,
                    method = s.lastMethod,
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

        targets.forEach { target ->
            launch(Dispatchers.IO) {
                while (isActive) {
                    val sample = probe(target, timeoutMs)
                    val s = statsMap[target.display]!!
                    synchronized(s) {
                        s.sent += 1
                        if (sample != null) {
                            s.received += 1
                            s.latencies += sample.latencyMs
                            s.lastMs = sample.latencyMs
                            s.lastSuccess = true
                            s.lastMethod = sample.method
                        } else {
                            s.lastMs = null
                            s.lastSuccess = false
                            s.lastMethod = methodLabel(target)
                        }
                    }
                    val stamp = timeFmt.format(Date())
                    val line = if (sample != null) {
                        PingLogLine(
                            timestamp = stamp,
                            host = target.display,
                            success = true,
                            latencyMs = sample.latencyMs,
                            message = String.format(
                                Locale.US,
                                "%s  %s  %.0f ms  (%s)",
                                stamp,
                                target.display,
                                sample.latencyMs,
                                sample.method
                            )
                        )
                    } else {
                        PingLogLine(
                            timestamp = stamp,
                            host = target.display,
                            success = false,
                            latencyMs = null,
                            message = "$stamp  ${target.display}  timeout  (${methodLabel(target)})"
                        )
                    }
                    send(snapshot(line))
                    delay(max(200L, intervalMs))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    data class PingOnceResult(
        val host: String,
        val latencyMs: Double?,
        val method: String,
        val success: Boolean
    )

    suspend fun pingOnce(raw: String, timeoutMs: Int = 2_000): PingOnceResult =
        withContext(Dispatchers.IO) {
            val target = parseTarget(raw) ?: PingTarget("1.1.1.1")
            val sample = probe(target, timeoutMs)
            PingOnceResult(
                host = target.display,
                latencyMs = sample?.latencyMs,
                method = sample?.method ?: methodLabel(target),
                success = sample != null
            )
        }

    private fun methodLabel(target: PingTarget): String =
        if (target.tcpPort != null) "tcp/${target.tcpPort}" else "icmp"

    private fun probe(target: PingTarget, timeoutMs: Int): ProbeResult? {
        // Explicit :port → TCP connect to that port only.
        if (target.tcpPort != null) {
            return tcpProbe(target.host, target.tcpPort, timeoutMs)?.let {
                ProbeResult(it, "tcp/${target.tcpPort}")
            }
        }
        // Default: real ICMP echo. Do not fall back to TCP/443 — that caused false "up".
        return icmpProbe(target.host, timeoutMs)?.let { ProbeResult(it, "icmp") }
    }

    /**
     * ICMP via platform ping. Only counts a reply when output shows an echo with time=.
     * Gateway "Destination Unreachable" / TTL exceeded are failures.
     */
    internal fun icmpProbe(host: String, timeoutMs: Int): Double? {
        val timeoutSec = max(1, (timeoutMs + 999) / 1000)
        val commands = listOf(
            listOf("ping", "-c", "1", "-W", timeoutSec.toString(), host),
            listOf("ping", "-c", "1", "-w", timeoutSec.toString(), host)
        )
        for (cmd in commands) {
            val parsed = runCatching {
                val proc = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor((timeoutSec + 2).toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@runCatching null
                }
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                parseIcmpOutput(output, proc.exitValue())
            }.getOrNull()
            if (parsed != null) return parsed
            // null = this binary flavor failed to run or no echo; try next command shape
        }
        return null
    }

    /**
     * @return latency ms on echo reply, or null on loss / unreachable / parse miss
     */
    internal fun parseIcmpOutput(output: String, exitCode: Int): Double? {
        val lower = output.lowercase(Locale.US)
        // Explicit failure signatures from the gateway — never treat as success.
        if (lower.contains("destination host unreachable") ||
            lower.contains("destination net unreachable") ||
            lower.contains("network is unreachable") ||
            lower.contains("ttl exceeded") ||
            lower.contains("time to live exceeded") ||
            lower.contains("unknown host") ||
            lower.contains("name or service not known") ||
            lower.contains("100% packet loss")
        ) {
            return null
        }

        // Require a real echo reply line with time.
        val echo = Regex(
            """bytes from\s+\S+.*?time[=<]([\d.]+)\s*ms""",
            RegexOption.IGNORE_CASE
        ).find(output) ?: return null

        val ms = echo.groupValues[1].toDoubleOrNull() ?: return null
        // Prefer exit 0, but some OEMs return 0 with unreachable text (handled above)
        // or non-zero even on success — trust the echo line if present.
        if (exitCode != 0 && !lower.contains("1 received") && !lower.contains("1 packets received")) {
            // Echo line without receive stats is still usually OK; keep ms.
        }
        return ms
    }

    private fun tcpProbe(host: String, port: Int, timeoutMs: Int): Double? {
        return try {
            // Resolve first so we fail closed on bad names instead of odd connect behavior.
            val addr = InetAddress.getByName(host)
            if (addr.isAnyLocalAddress || addr.isMulticastAddress) return null
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(addr, port), timeoutMs)
                // Require a fully connected socket — reject half-open oddities.
                if (!socket.isConnected) return null
            }
            (System.nanoTime() - start) / 1_000_000.0
        } catch (_: Exception) {
            null
        }
    }

    private class MutableHostStats(
        val target: PingTarget,
        var sent: Int = 0,
        var received: Int = 0,
        var lastMs: Double? = null,
        var lastSuccess: Boolean = false,
        var lastMethod: String = if (target.tcpPort != null) "tcp/${target.tcpPort}" else "icmp",
        val latencies: ArrayList<Double> = ArrayList()
    )
}

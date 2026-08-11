package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class TraceHop(
    val ttl: Int,
    val host: String?,
    val ip: String?,
    val latencyMs: Double?,
    val status: String
)

/**
 * Field traceroute using the platform `ping` binary with increasing TTL.
 * Works without root on most devices; hop IPs depend on ICMP time-exceeded replies.
 */
object TraceRoute {

    fun parseTarget(raw: String): String =
        raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .ifBlank { "1.1.1.1" }

    fun run(
        rawTarget: String,
        maxHops: Int = 20,
        timeoutSec: Int = 2
    ): Flow<TraceHop> = flow {
        val target = parseTarget(rawTarget)
        // Resolve once so ping gets a stable destination where possible.
        val destIp = withContext(Dispatchers.IO) {
            runCatching { InetAddress.getByName(target).hostAddress }.getOrNull()
        }
        for (ttl in 1..maxHops.coerceIn(5, 30)) {
            coroutineContext.ensureActive()
            val hop = probeHop(destIp ?: target, ttl, timeoutSec)
            emit(hop)
            val reached = hop.ip != null && destIp != null &&
                hop.ip.equals(destIp, ignoreCase = true)
            val reachedByName = hop.host != null &&
                hop.host.equals(target, ignoreCase = true)
            if (reached || reachedByName || hop.status == "reached") break
            delay(40)
        }
    }.flowOn(Dispatchers.IO)

    private fun probeHop(target: String, ttl: Int, timeoutSec: Int): TraceHop {
        // Android toybox/busybox ping: -c count, -W deadline seconds, -t TTL
        val commands = listOf(
            listOf("ping", "-c", "1", "-W", timeoutSec.toString(), "-t", ttl.toString(), target),
            listOf("ping", "-c", "1", "-w", timeoutSec.toString(), "-t", ttl.toString(), target)
        )
        var lastError = "no reply"
        for (cmd in commands) {
            val result = runCatching {
                val proc = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                val code = proc.waitFor()
                parsePingOutput(ttl, output, code)
            }.getOrElse {
                lastError = it.message ?: "ping failed"
                null
            }
            if (result != null) return result
        }
        return TraceHop(
            ttl = ttl,
            host = null,
            ip = null,
            latencyMs = null,
            status = lastError
        )
    }

    internal fun parsePingOutput(ttl: Int, output: String, exitCode: Int): TraceHop {
        // Typical: "From 192.168.1.1 icmp_seq=1 Time to live exceeded"
        // or: "64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.3 ms"
        val fromExceeded = Regex(
            """From\s+(\S+?)(?:\s|\(|:).*?(?:Time to live exceeded|ttl exceeded)""",
            RegexOption.IGNORE_CASE
        ).find(output)
        if (fromExceeded != null) {
            val token = fromExceeded.groupValues[1].trim('(', ')', ':')
            val ip = extractIp(token)
            return TraceHop(
                ttl = ttl,
                host = if (ip == null) token else null,
                ip = ip ?: token.takeIf { looksLikeIp(it) },
                latencyMs = extractTimeMs(output),
                status = "ttl exceeded"
            )
        }

        val fromReply = Regex(
            """bytes from\s+(\S+?)[:\s]""",
            RegexOption.IGNORE_CASE
        ).find(output)
        if (fromReply != null) {
            val token = fromReply.groupValues[1].trim('(', ')')
            val ip = extractIp(token) ?: extractIp(output)
            return TraceHop(
                ttl = ttl,
                host = token.takeIf { !looksLikeIp(it) },
                ip = ip,
                latencyMs = extractTimeMs(output),
                status = "reached"
            )
        }

        if (exitCode != 0 || output.contains("100% packet loss", ignoreCase = true)) {
            return TraceHop(ttl, null, null, null, "timeout")
        }
        return TraceHop(ttl, null, null, extractTimeMs(output), "unknown")
    }

    private fun extractTimeMs(output: String): Double? =
        Regex("""time[=<]([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

    private fun extractIp(text: String): String? {
        Regex("""(\d{1,3}(?:\.\d{1,3}){3})""").find(text)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""\(([0-9a-fA-F:]+)\)""").find(text)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun looksLikeIp(value: String): Boolean =
        value.contains(':') || value.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))

    fun formatHop(hop: TraceHop): String {
        val who = hop.ip ?: hop.host ?: "*"
        val ms = hop.latencyMs?.let { String.format(Locale.US, "%.0f ms", it) } ?: "—"
        return String.format(Locale.US, "%2d  %s  %s  (%s)", hop.ttl, who, ms, hop.status)
    }
}

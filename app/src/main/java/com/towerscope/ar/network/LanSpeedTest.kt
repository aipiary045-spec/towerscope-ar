package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max

data class LanSpeedResult(
    val targetHost: String,
    val targetPort: Int,
    val method: String,
    val bytesTransferred: Long,
    val durationMs: Long,
    val throughputMbps: Double,
    val connectMs: Double?,
    val error: String? = null
)

/**
 * Measures throughput to a device on the local network (default: gateway).
 * Tries HTTP download from the router/gateway, then falls back to TCP connect timing.
 */
object LanSpeedTest {

    private const val TEST_DURATION_MS = 4_000L
    private const val CHUNK_SIZE = 32 * 1024

    suspend fun run(
        host: String,
        port: Int = 80,
        durationMs: Long = TEST_DURATION_MS
    ): LanSpeedResult = withContext(Dispatchers.IO) {
        val trimmed = host.trim()
        if (trimmed.isBlank()) {
            return@withContext LanSpeedResult(
                targetHost = host,
                targetPort = port,
                method = "none",
                bytesTransferred = 0,
                durationMs = 0,
                throughputMbps = 0.0,
                connectMs = null,
                error = "Enter a host or gateway IP"
            )
        }

        val httpResult = runCatching { httpDownload(trimmed, port, durationMs) }.getOrNull()
        if (httpResult != null && httpResult.bytesTransferred > 0 && httpResult.error == null) {
            return@withContext httpResult
        }

        val tcpResult = runCatching { tcpFlood(trimmed, port.coerceIn(1, 65535), durationMs) }.getOrNull()
        if (tcpResult != null && tcpResult.bytesTransferred > 0 && tcpResult.error == null) {
            return@withContext tcpResult
        }

        httpResult ?: tcpResult ?: LanSpeedResult(
            targetHost = trimmed,
            targetPort = port,
            method = "failed",
            bytesTransferred = 0,
            durationMs = 0,
            throughputMbps = 0.0,
            connectMs = null,
            error = "Could not measure LAN speed to $trimmed"
        )
    }

    private suspend fun httpDownload(host: String, port: Int, durationMs: Long): LanSpeedResult {
        val scheme = if (port == 443) "https" else "http"
        val urlString = if (port == 80 || port == 443) {
            "$scheme://$host/"
        } else {
            "$scheme://$host:$port/"
        }
        val connectStart = System.nanoTime()
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 4_000
        conn.readTimeout = 6_000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.connect()
        val connectMs = (System.nanoTime() - connectStart) / 1_000_000.0

        val code = conn.responseCode
        val stream = if (code in 200..399) {
            conn.inputStream
        } else {
            conn.errorStream ?: conn.inputStream
        } ?: throw IllegalStateException("HTTP $code")

        var total = 0L
        val buffer = ByteArray(CHUNK_SIZE)
        val start = System.nanoTime()
        val deadline = start + durationMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
        }
        stream.close()
        conn.disconnect()

        val elapsedMs = max(1L, (System.nanoTime() - start) / 1_000_000L)
        return LanSpeedResult(
            targetHost = host,
            targetPort = port,
            method = "http",
            bytesTransferred = total,
            durationMs = elapsedMs,
            throughputMbps = mbps(total, elapsedMs),
            connectMs = connectMs
        )
    }

    private suspend fun tcpFlood(host: String, port: Int, durationMs: Long): LanSpeedResult {
        val connectStart = System.nanoTime()
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), 4_000)
        val connectMs = (System.nanoTime() - connectStart) / 1_000_000.0

        val payload = ByteArray(CHUNK_SIZE) { (it and 0xff).toByte() }
        var total = 0L
        val start = System.nanoTime()
        val deadline = start + durationMs * 1_000_000L
        val out = socket.getOutputStream()
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            out.write(payload)
            total += payload.size
        }
        out.flush()
        socket.close()

        val elapsedMs = max(1L, (System.nanoTime() - start) / 1_000_000L)
        return LanSpeedResult(
            targetHost = host,
            targetPort = port,
            method = "tcp-send",
            bytesTransferred = total,
            durationMs = elapsedMs,
            throughputMbps = mbps(total, elapsedMs),
            connectMs = connectMs
        )
    }

    fun formatMbps(mbps: Double): String =
        String.format(Locale.US, "%.1f Mbps", mbps)

    private fun mbps(bytes: Long, durationMs: Long): Double =
        (bytes * 8.0) / (durationMs / 1000.0) / 1_000_000.0
}

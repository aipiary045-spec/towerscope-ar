package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
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
 * Probes common router ports, then tries HTTP/HTTPS download or TCP send on open ports.
 */
object LanSpeedTest {

    private const val TEST_DURATION_MS = 4_000L
    private const val CHUNK_SIZE = 32 * 1024
    private const val PROBE_TIMEOUT_MS = 1_200

    val DEFAULT_PROBE_PORTS = listOf(80, 443, 8080, 8443, 8291, 8728, 53, 22)

    fun isHttpPort(port: Int): Boolean = port in HTTP_PORTS || port in HTTPS_PORTS

    private val HTTP_PORTS = setOf(80, 8080, 8000, 8888)
    private val HTTPS_PORTS = setOf(443, 8443)

    suspend fun run(
        host: String,
        port: Int? = null,
        durationMs: Long = TEST_DURATION_MS
    ): LanSpeedResult = withContext(Dispatchers.IO) {
        val trimmed = host.trim()
        if (trimmed.isBlank()) {
            return@withContext LanSpeedResult(
                targetHost = host,
                targetPort = 0,
                method = "none",
                bytesTransferred = 0,
                durationMs = 0,
                throughputMbps = 0.0,
                connectMs = null,
                error = "Enter a host or gateway IP"
            )
        }

        val portsToTry = if (port != null && port in 1..65535) {
            listOf(port)
        } else {
            DEFAULT_PROBE_PORTS
        }

        val openPorts = probeOpenPorts(trimmed, portsToTry)
        val throughputResults = mutableListOf<LanSpeedResult>()

        for ((openPort, connectMs) in openPorts) {
            coroutineContext.ensureActive()
            val result = when {
                openPort in HTTP_PORTS || openPort in HTTPS_PORTS -> {
                    runCatching { httpDownload(trimmed, openPort, durationMs) }.getOrNull()
                        ?: runCatching { tcpFlood(trimmed, openPort, durationMs) }.getOrNull()
                }
                else -> runCatching { tcpFlood(trimmed, openPort, durationMs) }.getOrNull()
            }
            if (result != null && result.bytesTransferred > 0 && result.error == null) {
                throughputResults += result
            } else if (result == null) {
                throughputResults += LanSpeedResult(
                    targetHost = trimmed,
                    targetPort = openPort,
                    method = "tcp-connect",
                    bytesTransferred = 0,
                    durationMs = connectMs.toLong().coerceAtLeast(1L),
                    throughputMbps = 0.0,
                    connectMs = connectMs
                )
            }
        }

        val best = throughputResults
            .filter { it.bytesTransferred > 0 && it.error == null }
            .maxByOrNull { it.throughputMbps }
        if (best != null) return@withContext best

        val fastest = openPorts.minByOrNull { it.second }
        if (fastest != null) {
            val (openPort, connectMs) = fastest
            return@withContext LanSpeedResult(
                targetHost = trimmed,
                targetPort = openPort,
                method = "tcp-connect",
                bytesTransferred = 0,
                durationMs = connectMs.toLong().coerceAtLeast(1L),
                throughputMbps = 0.0,
                connectMs = connectMs,
                error = "Reachable on port $openPort (${connectMs.toInt()} ms) but could not measure throughput. " +
                    "The device may not serve HTTP — try TCP throughput with a host that has a listener."
            )
        }

        LanSpeedResult(
            targetHost = trimmed,
            targetPort = portsToTry.first(),
            method = "failed",
            bytesTransferred = 0,
            durationMs = 0,
            throughputMbps = 0.0,
            connectMs = null,
            error = "Could not reach $trimmed on ports ${portsToTry.joinToString()}. " +
                "Check the IP and that you are on the same LAN."
        )
    }

    private suspend fun probeOpenPorts(host: String, ports: List<Int>): List<Pair<Int, Double>> =
        coroutineScope {
            ports.map { port ->
                async { port to probeConnect(host, port) }
            }.awaitAll()
                .mapNotNull { (port, connectMs) -> connectMs?.let { port to it } }
                .sortedBy { it.second }
        }

    private fun probeConnect(host: String, port: Int): Double? {
        return try {
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            }
            (System.nanoTime() - start) / 1_000_000.0
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun httpDownload(host: String, port: Int, durationMs: Long): LanSpeedResult {
        val scheme = if (port in HTTPS_PORTS) "https" else "http"
        val urlString = if (port == 80 || port == 443) {
            "$scheme://$host/"
        } else {
            "$scheme://$host:$port/"
        }
        val connectStart = System.nanoTime()
        val conn = openConnection(urlString)
        conn.connectTimeout = 4_000
        conn.readTimeout = 6_000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Connection", "close")
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

        if (total == 0L) {
            throw IllegalStateException("No data received on HTTP port $port")
        }

        val elapsedMs = max(1L, (System.nanoTime() - start) / 1_000_000L)
        return LanSpeedResult(
            targetHost = host,
            targetPort = port,
            method = if (port in HTTPS_PORTS) "https" else "http",
            bytesTransferred = total,
            durationMs = elapsedMs,
            throughputMbps = mbps(total, elapsedMs),
            connectMs = connectMs
        )
    }

    private fun openConnection(urlString: String): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }
            conn.sslSocketFactory = ssl.socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        return conn
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

        if (total == 0L) {
            throw IllegalStateException("No data sent on TCP port $port")
        }

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

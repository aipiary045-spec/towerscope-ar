package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.random.Random

data class SpeedTestResult(
    val latencyMs: Double,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val downloadBytes: Long,
    val uploadBytes: Long
)

/**
 * Field speed test against Cloudflare's public endpoints
 * (`https://speed.cloudflare.com/__down` / `__up`).
 *
 * Uses browser-like headers and modest payload sizes — large anonymous
 * downloads are often rejected with HTTP 403.
 */
object SpeedTestClient {

    private const val LATENCY_URL = "https://speed.cloudflare.com/__down?bytes=0"
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    /** Matches Cloudflare's mid-tier field sizes (see their defaultConfig). */
    private val DOWNLOAD_SIZES = longArrayOf(100_000L, 1_000_000L, 5_000_000L)
    private val UPLOAD_SIZES = longArrayOf(100_000L, 1_000_000L)
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    suspend fun run(
        onProgress: suspend (String) -> Unit = {}
    ): SpeedTestResult = withContext(Dispatchers.IO) {
        onProgress("Measuring latency…")
        val latency = measureLatencyMs()

        onProgress("Measuring download…")
        val download = measureDownloadMbps()

        onProgress("Measuring upload…")
        val upload = measureUploadMbps()

        if (!download.first.isFinite() && !upload.first.isFinite()) {
            error("Could not reach Cloudflare speed endpoints")
        }

        SpeedTestResult(
            latencyMs = latency,
            downloadMbps = download.first,
            uploadMbps = upload.first,
            downloadBytes = download.second,
            uploadBytes = upload.second
        )
    }

    private suspend fun measureLatencyMs(): Double {
        val samples = mutableListOf<Double>()
        repeat(5) {
            coroutineContext.ensureActive()
            val start = System.nanoTime()
            runCatching {
                openGet(LATENCY_URL).use { connection ->
                    val code = connection.responseCode
                    drain(connection)
                    if (code !in 200..399) error("HTTP $code")
                    samples += (System.nanoTime() - start) / 1_000_000.0
                }
            }
        }
        return samples.minOrNull() ?: Double.NaN
    }

    private suspend fun measureDownloadMbps(): Pair<Double, Long> {
        var bestMbps = Double.NaN
        var bestBytes = 0L
        for (bytes in DOWNLOAD_SIZES) {
            coroutineContext.ensureActive()
            val sample = runCatching { downloadOnce(bytes) }.getOrNull() ?: continue
            if (sample.second <= 0L) continue
            if (!bestMbps.isFinite() || sample.first > bestMbps) {
                bestMbps = sample.first
                bestBytes = sample.second
            }
            // Stop once a transfer lasts long enough for a stable estimate.
            if (sample.first.isFinite() && sample.second >= 1_000_000L) break
        }
        return bestMbps to bestBytes
    }

    private suspend fun measureUploadMbps(): Pair<Double, Long> {
        var bestMbps = Double.NaN
        var bestBytes = 0L
        for (bytes in UPLOAD_SIZES) {
            coroutineContext.ensureActive()
            val sample = runCatching { uploadOnce(bytes.toInt()) }.getOrNull() ?: continue
            if (sample.second <= 0L) continue
            if (!bestMbps.isFinite() || sample.first > bestMbps) {
                bestMbps = sample.first
                bestBytes = sample.second
            }
            if (sample.first.isFinite() && sample.second >= 1_000_000L) break
        }
        return bestMbps to bestBytes
    }

    private fun downloadOnce(bytes: Long): Pair<Double, Long> {
        val url = "$DOWNLOAD_URL?bytes=$bytes"
        openGet(url).use { connection ->
            val code = connection.responseCode
            if (code !in 200..299) {
                drain(connection)
                error("Download HTTP $code")
            }
            val start = System.nanoTime()
            var total = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    total += n
                }
            }
            if (total <= 0L) error("Empty download")
            val seconds = max((System.nanoTime() - start) / 1_000_000_000.0, 0.001)
            val mbps = (total * 8.0) / seconds / 1_000_000.0
            return mbps to total
        }
    }

    private fun uploadOnce(bytes: Int): Pair<Double, Long> {
        // Reuse a compressible-ish pattern; full random is slower to generate on device.
        val payload = ByteArray(bytes) { i -> (i * 31).toByte() }
        openPost(UPLOAD_URL, payload.size).use { connection ->
            val start = System.nanoTime()
            connection.outputStream.use { out ->
                out.write(payload)
                out.flush()
            }
            val code = connection.responseCode
            drain(connection)
            if (code !in 200..299) error("Upload HTTP $code")
            val seconds = max((System.nanoTime() - start) / 1_000_000_000.0, 0.001)
            val mbps = (payload.size * 8.0) / seconds / 1_000_000.0
            return mbps to payload.size.toLong()
        }
    }

    private fun openGet(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Origin", "https://speed.cloudflare.com")
            setRequestProperty("Referer", "https://speed.cloudflare.com/")
        }
    }

    private fun openPost(url: String, contentLength: Int): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setFixedLengthStreamingMode(contentLength)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Origin", "https://speed.cloudflare.com")
            setRequestProperty("Referer", "https://speed.cloudflare.com/")
        }
    }

    private fun drain(connection: HttpURLConnection) {
        val stream = try {
            if (connection.responseCode in 200..299) connection.inputStream
            else connection.errorStream
        } catch (_: Exception) {
            connection.errorStream
        }
        runCatching { stream?.use { it.readBytes() } }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        try {
            return block(this)
        } finally {
            disconnect()
        }
    }

    fun formatMbps(value: Double): String =
        if (!value.isFinite()) "—" else String.format(Locale.US, "%.1f Mbps", value)

    fun formatLatency(ms: Double): String =
        if (!ms.isFinite()) "—" else String.format(Locale.US, "%.0f ms", ms)
}

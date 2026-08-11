package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

data class SpeedTestServer(
    val id: String,
    val label: String,
    val detail: String,
    /** Supports POST upload of arbitrary body length. */
    val supportsUpload: Boolean,
    val latencyUrl: String,
    /** Build a download URL for roughly [bytes] of payload. */
    val downloadUrl: (bytes: Long) -> String,
    val uploadUrl: String?
)

data class SpeedTestResult(
    val serverId: String,
    val serverLabel: String,
    val latencyMs: Double,
    val jitterMs: Double,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val downloadBytes: Long,
    val uploadBytes: Long
)

enum class SpeedPhase {
    PICK,
    LATENCY,
    DOWNLOAD,
    UPLOAD,
    DONE
}

data class SpeedProgress(
    val phase: SpeedPhase,
    val message: String,
    val liveMbps: Double? = null,
    val phaseMbps: Double? = null,
    val latencyMs: Double? = null,
    val jitterMs: Double? = null,
    val bytesTransferred: Long = 0L,
    val serverLabel: String? = null
)

/**
 * Multi-server field speed test with auto-pick, parallel streams,
 * sustained Mbps, and latency jitter.
 */
object SpeedTestClient {

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val MIN_DOWNLOAD_MS = 9_000L
    private const val MIN_UPLOAD_MS = 7_000L
    private const val PARALLEL_STREAMS = 4
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    const val AUTO_SERVER_ID = "auto"

    val servers: List<SpeedTestServer> = listOf(
        SpeedTestServer(
            id = "cloudflare",
            label = "Cloudflare",
            detail = "Global anycast · up + down",
            supportsUpload = true,
            latencyUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = { bytes -> "https://speed.cloudflare.com/__down?bytes=$bytes" },
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedTestServer(
            id = "librespeed",
            label = "LibreSpeed",
            detail = "Public demo · up + down",
            supportsUpload = true,
            latencyUrl = "https://librespeed.org/backend/empty.php",
            downloadUrl = { bytes ->
                val chunks = (bytes / 1_048_576L).coerceIn(1L, 100L)
                "https://librespeed.org/backend/garbage.php?ckSize=$chunks"
            },
            uploadUrl = "https://librespeed.org/backend/empty.php"
        ),
        SpeedTestServer(
            id = "hetzner",
            label = "Hetzner",
            detail = "EU · download only",
            supportsUpload = false,
            latencyUrl = "https://speed.hetzner.de/1MB.bin",
            downloadUrl = { bytes ->
                when {
                    bytes <= 1_500_000L -> "https://speed.hetzner.de/1MB.bin"
                    bytes <= 12_000_000L -> "https://speed.hetzner.de/10MB.bin"
                    else -> "https://speed.hetzner.de/100MB.bin"
                }
            },
            uploadUrl = null
        )
    )

    fun serverById(id: String): SpeedTestServer? =
        servers.firstOrNull { it.id == id }

    suspend fun run(
        serverId: String = AUTO_SERVER_ID,
        onProgress: suspend (SpeedProgress) -> Unit = {}
    ): SpeedTestResult = withContext(Dispatchers.IO) {
        val server = if (serverId == AUTO_SERVER_ID) {
            onProgress(
                SpeedProgress(
                    phase = SpeedPhase.PICK,
                    message = "Finding fastest server…"
                )
            )
            pickFastestServer { label, ms ->
                onProgress(
                    SpeedProgress(
                        phase = SpeedPhase.PICK,
                        message = "Probing $label…",
                        latencyMs = ms,
                        serverLabel = label
                    )
                )
            }
        } else {
            serverById(serverId) ?: servers.first()
        }

        onProgress(
            SpeedProgress(
                phase = SpeedPhase.LATENCY,
                message = "Latency · ${server.label}",
                serverLabel = server.label
            )
        )
        val (latency, jitter) = measureLatency(server) { sampleMs, n, total ->
            onProgress(
                SpeedProgress(
                    phase = SpeedPhase.LATENCY,
                    message = "Latency $n / $total · ${server.label}",
                    latencyMs = sampleMs,
                    serverLabel = server.label
                )
            )
        }

        onProgress(
            SpeedProgress(
                phase = SpeedPhase.DOWNLOAD,
                message = "Download · ${server.label}",
                latencyMs = latency,
                jitterMs = jitter,
                serverLabel = server.label
            )
        )
        val download = measureParallelThroughput(
            phase = SpeedPhase.DOWNLOAD,
            server = server,
            minDurationMs = MIN_DOWNLOAD_MS,
            onProgress = onProgress
        )

        val upload = if (server.supportsUpload && server.uploadUrl != null) {
            onProgress(
                SpeedProgress(
                    phase = SpeedPhase.UPLOAD,
                    message = "Upload · ${server.label}",
                    latencyMs = latency,
                    jitterMs = jitter,
                    phaseMbps = download.first.takeIf { it.isFinite() },
                    serverLabel = server.label
                )
            )
            measureParallelThroughput(
                phase = SpeedPhase.UPLOAD,
                server = server,
                minDurationMs = MIN_UPLOAD_MS,
                onProgress = onProgress
            )
        } else {
            Double.NaN to 0L
        }

        if (!download.first.isFinite() && !upload.first.isFinite()) {
            error("Could not reach ${server.label} speed endpoints")
        }

        val result = SpeedTestResult(
            serverId = server.id,
            serverLabel = server.label,
            latencyMs = latency,
            jitterMs = jitter,
            downloadMbps = download.first,
            uploadMbps = upload.first,
            downloadBytes = download.second,
            uploadBytes = upload.second
        )
        onProgress(
            SpeedProgress(
                phase = SpeedPhase.DONE,
                message = "Done · ${server.label}",
                phaseMbps = result.downloadMbps.takeIf { it.isFinite() },
                liveMbps = result.uploadMbps.takeIf { it.isFinite() },
                latencyMs = latency,
                jitterMs = jitter,
                serverLabel = server.label
            )
        )
        result
    }

    private suspend fun pickFastestServer(
        onProbe: suspend (label: String, ms: Double?) -> Unit
    ): SpeedTestServer = coroutineScope {
        val ranked = servers.map { server ->
            async {
                val ms = probeLatencyMs(server.latencyUrl)
                onProbe(server.label, ms)
                server to ms
            }
        }.awaitAll()
            .filter { it.second != null && it.second!!.isFinite() }
            .sortedWith(
                compareBy<Pair<SpeedTestServer, Double?>> { it.second!! }
                    .thenByDescending { if (it.first.supportsUpload) 1 else 0 }
            )

        ranked.firstOrNull()?.first
            ?: servers.first { it.supportsUpload }
    }

    private suspend fun measureLatency(
        server: SpeedTestServer,
        onSample: suspend (ms: Double, index: Int, total: Int) -> Unit
    ): Pair<Double, Double> {
        val samples = mutableListOf<Double>()
        val total = 10
        repeat(total) { i ->
            coroutineContext.ensureActive()
            val ms = probeLatencyMs(server.latencyUrl)
            if (ms != null && ms.isFinite()) {
                samples += ms
                onSample(ms, i + 1, total)
            }
        }
        if (samples.isEmpty()) return Double.NaN to Double.NaN
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val mean = samples.average()
        val variance = samples.map { (it - mean) * (it - mean) }.average()
        val jitter = sqrt(variance)
        return median to jitter
    }

    private fun probeLatencyMs(url: String): Double? {
        val start = System.nanoTime()
        val ok = runCatching {
            openGet(url, refererFor(url)).use { connection ->
                val code = connection.responseCode
                // For binary probes, only read a little so we measure RTT-ish latency.
                connection.inputStream.use { input ->
                    val buf = ByteArray(8 * 1024)
                    var n = 0
                    while (n < 64 * 1024) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        n += r
                    }
                }
                if (code !in 200..399) error("HTTP $code")
            }
        }.isSuccess
        if (!ok) return null
        return (System.nanoTime() - start) / 1_000_000.0
    }

    private suspend fun measureParallelThroughput(
        phase: SpeedPhase,
        server: SpeedTestServer,
        minDurationMs: Long,
        onProgress: suspend (SpeedProgress) -> Unit
    ): Pair<Double, Long> {
        val chunkPlan = when (phase) {
            SpeedPhase.DOWNLOAD -> longArrayOf(1_000_000L, 5_000_000L, 10_000_000L, 25_000_000L)
            else -> longArrayOf(500_000L, 1_000_000L, 2_000_000L, 4_000_000L)
        }
        var sustainedMbps = Double.NaN
        var peakMbps = Double.NaN
        var totalBytes = 0L
        val phaseStart = System.nanoTime()
        var chunkIndex = 0
        var pass = 0
        val liveSamples = mutableListOf<Double>()

        while (true) {
            coroutineContext.ensureActive()
            val elapsedMs = (System.nanoTime() - phaseStart) / 1_000_000L
            if (pass > 0 && elapsedMs >= minDurationMs && sustainedMbps.isFinite()) break
            if (pass >= 10) break

            val sizeHint = chunkPlan[minOf(chunkIndex, chunkPlan.lastIndex)]
            if (sustainedMbps.isFinite() && sustainedMbps > 50.0 && chunkIndex < chunkPlan.lastIndex) {
                chunkIndex += 1
            }

            val label = if (phase == SpeedPhase.DOWNLOAD) "Download" else "Upload"
            onProgress(
                SpeedProgress(
                    phase = phase,
                    message = "$label · pass ${pass + 1} · ${PARALLEL_STREAMS}× · ${server.label}",
                    phaseMbps = sustainedMbps.takeIf { it.isFinite() },
                    serverLabel = server.label
                )
            )

            val wallStart = System.nanoTime()
            val passOk = runCatching {
                coroutineScope {
                    (0 until PARALLEL_STREAMS).map {
                        async {
                            if (phase == SpeedPhase.DOWNLOAD) {
                                downloadOnce(server, sizeHint) { _, _ -> }
                            } else {
                                uploadOnce(server, sizeHint.toInt()) { _, _ -> }
                            }
                        }
                    }.awaitAll()
                }
            }.getOrNull()

            if (passOk != null) {
                val bytes = passOk.sumOf { it.second }
                val seconds = max((System.nanoTime() - wallStart) / 1_000_000_000.0, 0.001)
                if (bytes > 0L) {
                    totalBytes += bytes
                    val passMbps = (bytes * 8.0) / seconds / 1_000_000.0
                    liveSamples += passMbps
                    if (!peakMbps.isFinite() || passMbps > peakMbps) peakMbps = passMbps
                    // Sustained = trimmed mean of pass rates (drop lowest).
                    sustainedMbps = trimmedMean(liveSamples)
                    onProgress(
                        SpeedProgress(
                            phase = phase,
                            message = "$label · ${formatMbps(passMbps)} live · ${server.label}",
                            liveMbps = passMbps,
                            phaseMbps = sustainedMbps,
                            bytesTransferred = totalBytes,
                            serverLabel = server.label
                        )
                    )
                }
            } else if (chunkIndex > 0) {
                chunkIndex -= 1
            }

            pass += 1
            if (pass < 2) continue
        }

        // Prefer sustained; fall back to peak.
        val reported = when {
            sustainedMbps.isFinite() -> sustainedMbps
            peakMbps.isFinite() -> peakMbps
            else -> Double.NaN
        }
        return reported to totalBytes
    }

    private suspend fun downloadOnce(
        server: SpeedTestServer,
        bytes: Long,
        report: suspend (liveMbps: Double, bytes: Long) -> Unit
    ): Pair<Double, Long> {
        val url = server.downloadUrl(bytes)
        openGet(url, refererFor(url)).use { connection ->
            val code = connection.responseCode
            if (code !in 200..299) {
                drain(connection)
                error("Download HTTP $code")
            }
            val start = System.nanoTime()
            var total = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n <= 0) break
                    total += n
                    val now = System.nanoTime()
                    if (now - lastReport >= 220_000_000L) {
                        lastReport = now
                        val seconds = max((now - start) / 1_000_000_000.0, 0.001)
                        report((total * 8.0) / seconds / 1_000_000.0, total)
                    }
                }
            }
            if (total <= 0L) error("Empty download")
            val seconds = max((System.nanoTime() - start) / 1_000_000_000.0, 0.001)
            val mbps = (total * 8.0) / seconds / 1_000_000.0
            report(mbps, total)
            return mbps to total
        }
    }

    private suspend fun uploadOnce(
        server: SpeedTestServer,
        bytes: Int,
        report: suspend (liveMbps: Double, bytes: Long) -> Unit
    ): Pair<Double, Long> {
        val uploadUrl = server.uploadUrl ?: error("No upload URL")
        val size = bytes.coerceIn(64_000, 5_000_000)
        val chunk = ByteArray(64 * 1024) { i -> (i * 31).toByte() }
        openPost(uploadUrl, size, refererFor(uploadUrl)).use { connection ->
            val start = System.nanoTime()
            var written = 0
            var lastReport = 0L
            connection.outputStream.use { out ->
                while (written < size) {
                    coroutineContext.ensureActive()
                    val n = minOf(chunk.size, size - written)
                    out.write(chunk, 0, n)
                    written += n
                    val now = System.nanoTime()
                    if (now - lastReport >= 220_000_000L) {
                        lastReport = now
                        out.flush()
                        val seconds = max((now - start) / 1_000_000_000.0, 0.001)
                        report((written * 8.0) / seconds / 1_000_000.0, written.toLong())
                    }
                }
                out.flush()
            }
            val code = connection.responseCode
            drain(connection)
            if (code !in 200..299) error("Upload HTTP $code")
            val seconds = max((System.nanoTime() - start) / 1_000_000_000.0, 0.001)
            val mbps = (size * 8.0) / seconds / 1_000_000.0
            report(mbps, size.toLong())
            return mbps to size.toLong()
        }
    }

    private fun trimmedMean(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        if (values.size == 1) return values.first()
        val sorted = values.sorted()
        // Drop the slowest pass so a cold start doesn't drag the result.
        val keep = sorted.drop(1).ifEmpty { sorted }
        return keep.average()
    }

    private fun refererFor(url: String): Pair<String, String>? = when {
        url.contains("cloudflare.com") ->
            "https://speed.cloudflare.com" to "https://speed.cloudflare.com/"
        url.contains("librespeed.org") ->
            "https://librespeed.org" to "https://librespeed.org/"
        else -> null
    }

    private fun openGet(url: String, originReferer: Pair<String, String>?): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Cache-Control", "no-cache")
            originReferer?.let { (origin, referer) ->
                setRequestProperty("Origin", origin)
                setRequestProperty("Referer", referer)
            }
        }
    }

    private fun openPost(
        url: String,
        contentLength: Int,
        originReferer: Pair<String, String>?
    ): HttpURLConnection {
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
            originReferer?.let { (origin, referer) ->
                setRequestProperty("Origin", origin)
                setRequestProperty("Referer", referer)
            }
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

    fun formatJitter(ms: Double): String =
        if (!ms.isFinite()) "—" else String.format(Locale.US, "%.0f ms", ms)
}

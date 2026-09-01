package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.max

data class BufferbloatProgress(
    val phase: String,
    val idleAvgMs: Double?,
    val loadedAvgMs: Double?,
    val spikeMs: Double?,
    val running: Boolean
)

data class BufferbloatResult(
    val targetHost: String,
    val idleAvgMs: Double,
    val idleMaxMs: Double,
    val loadedAvgMs: Double,
    val loadedMaxMs: Double,
    val latencyIncreaseMs: Double,
    val rating: BufferbloatRating,
    val downloadMbps: Double?
)

enum class BufferbloatRating {
    GOOD,
    FAIR,
    POOR
}

/**
 * Measures latency under load — high spike suggests bufferbloat on the path.
 */
object BufferbloatTest {

    private const val IDLE_SAMPLES = 8
    private const val LOADED_SAMPLES = 12

    fun run(
        pingHost: String = "1.1.1.1",
        downloadUrl: String = "https://speed.cloudflare.com/__down?bytes=25000000"
    ): Flow<BufferbloatProgress> = flow {
        runOnce(pingHost, downloadUrl) { progress -> emit(progress) }
    }.flowOn(Dispatchers.IO)

    suspend fun runOnce(
        pingHost: String = "1.1.1.1",
        downloadUrl: String = "https://speed.cloudflare.com/__down?bytes=25000000",
        onProgress: suspend (BufferbloatProgress) -> Unit = {}
    ): BufferbloatResult = withContext(Dispatchers.IO) {
        val target = PingMonitor.parseTarget(pingHost) ?: PingMonitor.parseTarget("1.1.1.1")!!
        onProgress(BufferbloatProgress("Measuring idle latency…", null, null, null, true))

        val idle = mutableListOf<Double>()
        repeat(IDLE_SAMPLES) {
            if (!coroutineContext.isActive) {
                return@withContext buildResult(target.display, idle, emptyList(), null)
            }
            probeIcmp(target.host)?.let { idle += it }
            delay(400)
        }

        onProgress(BufferbloatProgress("Loading link while pinging…", idle.average(), null, null, true))

        val loaded = mutableListOf<Double>()
        var downloadMbps: Double? = null
        coroutineScope {
            val download = async {
                runCatching { sustainedDownload(downloadUrl, 12_000L) }.getOrNull()
            }
            repeat(LOADED_SAMPLES) {
                if (!coroutineContext.isActive) return@coroutineScope
                probeIcmp(target.host)?.let { loaded += it }
                val loadedAvg = loaded.average().takeIf { loaded.isNotEmpty() }
                val idleAvg = idle.average()
                onProgress(
                    BufferbloatProgress(
                        "Under load…",
                        idleAvg,
                        loadedAvg,
                        if (loadedAvg != null) loadedAvg - idleAvg else null,
                        true
                    )
                )
                delay(500)
            }
            downloadMbps = download.await()
        }

        val result = buildResult(target.display, idle, loaded, downloadMbps)
        onProgress(
            BufferbloatProgress(
                phase = "Done",
                idleAvgMs = result.idleAvgMs,
                loadedAvgMs = result.loadedAvgMs,
                spikeMs = result.latencyIncreaseMs,
                running = false
            )
        )
        result
    }

    fun buildResult(
        pingHost: String,
        idle: List<Double>,
        loaded: List<Double>,
        downloadMbps: Double?
    ): BufferbloatResult {
        val idleAvg = idle.average().takeIf { idle.isNotEmpty() } ?: 0.0
        val loadedAvg = loaded.average().takeIf { loaded.isNotEmpty() } ?: 0.0
        val increase = loadedAvg - idleAvg
        val rating = when {
            idle.isEmpty() || loaded.isEmpty() -> BufferbloatRating.FAIR
            increase < 15 -> BufferbloatRating.GOOD
            increase < 50 -> BufferbloatRating.FAIR
            else -> BufferbloatRating.POOR
        }
        return BufferbloatResult(
            targetHost = pingHost,
            idleAvgMs = idleAvg,
            idleMaxMs = idle.maxOrNull() ?: idleAvg,
            loadedAvgMs = loadedAvg,
            loadedMaxMs = loaded.maxOrNull() ?: loadedAvg,
            latencyIncreaseMs = increase,
            rating = rating,
            downloadMbps = downloadMbps
        )
    }

    fun formatResult(result: BufferbloatResult): String = buildString {
        appendLine("Bufferbloat test · ${result.targetHost}")
        appendLine(String.format(Locale.US, "Idle latency: avg %.0f ms (max %.0f)", result.idleAvgMs, result.idleMaxMs))
        appendLine(String.format(Locale.US, "Loaded latency: avg %.0f ms (max %.0f)", result.loadedAvgMs, result.loadedMaxMs))
        appendLine(String.format(Locale.US, "Increase: +%.0f ms · ${result.rating.name.lowercase()}", result.latencyIncreaseMs))
        result.downloadMbps?.let {
            appendLine(String.format(Locale.US, "Download during test: %.1f Mbps", it))
        }
    }.trim()

    fun ratingLabel(rating: BufferbloatRating): String = when (rating) {
        BufferbloatRating.GOOD -> "Good — little latency spike under load"
        BufferbloatRating.FAIR -> "Fair — noticeable latency increase under load"
        BufferbloatRating.POOR -> "Poor — large latency spike (bufferbloat likely)"
    }

    private fun probeIcmp(host: String): Double? = PingMonitor.icmpProbe(host, 2_500)

    private fun sustainedDownload(url: String, durationMs: Long): Double {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.requestMethod = "GET"
        val stream = conn.inputStream
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        val start = System.nanoTime()
        val deadline = start + durationMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
        }
        stream.close()
        conn.disconnect()
        val elapsed = max(1L, (System.nanoTime() - start) / 1_000_000L)
        return (total * 8.0) / (elapsed / 1000.0) / 1_000_000.0
    }
}

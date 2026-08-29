package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max

enum class ThroughputDirection {
    UPLOAD,
    DOWNLOAD
}

data class ThroughputProgress(
    val direction: ThroughputDirection,
    val bytesTransferred: Long,
    val elapsedMs: Long,
    val liveMbps: Double,
    val running: Boolean
)

data class ThroughputResult(
    val host: String,
    val port: Int,
    val direction: ThroughputDirection,
    val bytesTransferred: Long,
    val durationMs: Long,
    val throughputMbps: Double,
    val error: String? = null
)

/**
 * Raw TCP throughput to a host:port — useful with netcat, iperf3 -s, or any listener.
 */
object TcpThroughputTest {

    private const val CHUNK = 64 * 1024

    suspend fun run(
        host: String,
        port: Int,
        direction: ThroughputDirection,
        durationMs: Long = 8_000L,
        onProgress: suspend (ThroughputProgress) -> Unit = {}
    ): ThroughputResult = withContext(Dispatchers.IO) {
        val trimmed = host.trim()
        if (trimmed.isBlank() || port !in 1..65535) {
            return@withContext ThroughputResult(
                host = trimmed,
                port = port,
                direction = direction,
                bytesTransferred = 0,
                durationMs = 0,
                throughputMbps = 0.0,
                error = "Enter a valid host and port"
            )
        }

        runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(trimmed, port), 5_000)
                when (direction) {
                    ThroughputDirection.UPLOAD -> upload(socket.getOutputStream(), durationMs, direction, onProgress)
                    ThroughputDirection.DOWNLOAD -> download(socket.getInputStream(), durationMs, direction, onProgress)
                }.let { (bytes, elapsed) ->
                    ThroughputResult(
                        host = trimmed,
                        port = port,
                        direction = direction,
                        bytesTransferred = bytes,
                        durationMs = elapsed,
                        throughputMbps = mbps(bytes, elapsed)
                    )
                }
            }
        }.getOrElse { e ->
            ThroughputResult(
                host = trimmed,
                port = port,
                direction = direction,
                bytesTransferred = 0,
                durationMs = 0,
                throughputMbps = 0.0,
                error = e.message ?: "Connection failed"
            )
        }
    }

    private suspend fun upload(
        out: OutputStream,
        durationMs: Long,
        direction: ThroughputDirection,
        onProgress: suspend (ThroughputProgress) -> Unit
    ): Pair<Long, Long> {
        val payload = ByteArray(CHUNK) { (it and 0xff).toByte() }
        var total = 0L
        val start = System.nanoTime()
        val deadline = start + durationMs * 1_000_000L
        var lastEmit = 0L
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            out.write(payload)
            total += payload.size
            val elapsed = (System.nanoTime() - start) / 1_000_000L
            if (elapsed - lastEmit >= 250) {
                onProgress(
                    ThroughputProgress(
                        direction = direction,
                        bytesTransferred = total,
                        elapsedMs = elapsed,
                        liveMbps = mbps(total, max(1, elapsed)),
                        running = true
                    )
                )
                lastEmit = elapsed
            }
        }
        out.flush()
        val elapsed = max(1L, (System.nanoTime() - start) / 1_000_000L)
        onProgress(
            ThroughputProgress(
                direction = direction,
                bytesTransferred = total,
                elapsedMs = elapsed,
                liveMbps = mbps(total, elapsed),
                running = false
            )
        )
        return total to elapsed
    }

    private suspend fun download(
        input: InputStream,
        durationMs: Long,
        direction: ThroughputDirection,
        onProgress: suspend (ThroughputProgress) -> Unit
    ): Pair<Long, Long> {
        val buffer = ByteArray(CHUNK)
        var total = 0L
        val start = System.nanoTime()
        val deadline = start + durationMs * 1_000_000L
        var lastEmit = 0L
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            val elapsed = (System.nanoTime() - start) / 1_000_000L
            if (elapsed - lastEmit >= 250) {
                onProgress(
                    ThroughputProgress(
                        direction = direction,
                        bytesTransferred = total,
                        elapsedMs = elapsed,
                        liveMbps = mbps(total, max(1, elapsed)),
                        running = true
                    )
                )
                lastEmit = elapsed
            }
        }
        val elapsed = max(1L, (System.nanoTime() - start) / 1_000_000L)
        onProgress(
            ThroughputProgress(
                direction = direction,
                bytesTransferred = total,
                elapsedMs = elapsed,
                liveMbps = mbps(total, elapsed),
                running = false
            )
        )
        return total to elapsed
    }

    fun formatMbps(mbps: Double): String =
        String.format(Locale.US, "%.1f Mbps", mbps)

    private fun mbps(bytes: Long, durationMs: Long): Double =
        (bytes * 8.0) / (durationMs / 1000.0) / 1_000_000.0
}

package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

data class PortCheckResult(
    val host: String,
    val port: Int,
    val open: Boolean,
    val connectMs: Double?,
    val error: String? = null
)

object PortChecker {

    suspend fun check(
        host: String,
        port: Int,
        timeoutMs: Int = 3_000
    ): PortCheckResult = withContext(Dispatchers.IO) {
        val trimmed = host.trim()
        if (trimmed.isBlank() || port !in 1..65535) {
            return@withContext PortCheckResult(
                host = trimmed,
                port = port,
                open = false,
                connectMs = null,
                error = "Enter a valid host and port (1–65535)"
            )
        }
        runCatching {
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(trimmed, port), timeoutMs)
            }
            val ms = (System.nanoTime() - start) / 1_000_000.0
            PortCheckResult(trimmed, port, open = true, connectMs = ms)
        }.getOrElse { e ->
            PortCheckResult(
                host = trimmed,
                port = port,
                open = false,
                connectMs = null,
                error = e.message ?: "Connection refused"
            )
        }
    }

    fun format(result: PortCheckResult): String = buildString {
        appendLine("Port check · ${result.host}:${result.port}")
        appendLine(if (result.open) "OPEN" else "CLOSED / filtered")
        result.connectMs?.let {
            appendLine(String.format(Locale.US, "Connect time: %.0f ms", it))
        }
        result.error?.let { appendLine(it) }
    }.trim()
}

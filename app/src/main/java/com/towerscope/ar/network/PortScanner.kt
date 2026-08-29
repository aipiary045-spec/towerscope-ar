package com.towerscope.ar.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class PortScanHit(
    val port: Int,
    val connectMs: Double,
    val service: String?
)

data class PortScanResult(
    val host: String,
    val openPorts: List<PortScanHit>,
    val portsScanned: Int,
    val error: String? = null
)

data class NetworkPortScanResult(
    val targets: List<String>,
    val results: List<PortScanResult>,
    val portsPerHost: Int,
    val error: String? = null
)

enum class PortScanPreset {
    COMMON,
    WEB,
    ROUTER,
    EXTENDED
}

object PortScanner {

    private val COMMON_PORTS = listOf(22, 53, 80, 443, 445, 554, 3389, 5000, 8080, 8443, 8291)
    private val WEB_PORTS = listOf(80, 443, 8000, 8080, 8443, 8888, 9000)
    private val ROUTER_PORTS = listOf(22, 23, 53, 80, 443, 8080, 8291, 8728, 8443, 7547)
    private val EXTENDED_PORTS = listOf(
        20, 21, 22, 23, 25, 53, 80, 110, 143, 443, 445, 465, 587, 993, 995,
        1433, 1521, 3306, 3389, 5000, 5060, 5432, 554, 5900, 8000, 8080,
        8443, 8888, 9000, 9100, 8291, 8728, 7547
    )

    private val SERVICE_NAMES = mapOf(
        20 to "FTP-DATA", 21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP",
        53 to "DNS", 80 to "HTTP", 110 to "POP3", 143 to "IMAP", 443 to "HTTPS",
        445 to "SMB", 465 to "SMTPS", 554 to "RTSP", 587 to "SMTP", 993 to "IMAPS",
        995 to "POP3S", 1433 to "MSSQL", 1521 to "Oracle", 3306 to "MySQL",
        3389 to "RDP", 5000 to "UPnP", 5060 to "SIP", 5432 to "PostgreSQL",
        5900 to "VNC", 8000 to "HTTP-alt", 8080 to "HTTP-proxy", 8443 to "HTTPS-alt",
        8888 to "HTTP-alt", 9000 to "SonarQube", 9100 to "Print", 8291 to "MikroTik",
        8728 to "MikroTik", 7547 to "TR-069"
    )

    fun portsFor(preset: PortScanPreset, extraRaw: String = ""): List<Int> {
        val base = when (preset) {
            PortScanPreset.COMMON -> COMMON_PORTS
            PortScanPreset.WEB -> WEB_PORTS
            PortScanPreset.ROUTER -> ROUTER_PORTS
            PortScanPreset.EXTENDED -> EXTENDED_PORTS
        }
        val extra = parseExtraPorts(extraRaw)
        return (base + extra).distinct().sorted()
    }

    fun parseExtraPorts(raw: String): List<Int> =
        raw.split(',', ';', ' ', '\n', '\t')
            .flatMap { token ->
                val part = token.trim()
                if (part.isBlank()) return@flatMap emptyList()
                val range = Regex("""^(\d+)-(\d+)$""").matchEntire(part)
                if (range != null) {
                    val start = range.groupValues[1].toIntOrNull() ?: return@flatMap emptyList()
                    val end = range.groupValues[2].toIntOrNull() ?: return@flatMap emptyList()
                    if (start > end || start !in 1..65535 || end !in 1..65535) {
                        return@flatMap emptyList()
                    }
                    (start..end).toList()
                } else {
                    listOfNotNull(part.toIntOrNull()?.takeIf { it in 1..65535 })
                }
            }
            .distinct()

    fun resolveTargets(
        overrideHost: String?,
        gatewayIpv4: String?,
        subnet: SubnetInfo?,
        discoveredHosts: List<String>
    ): List<String> {
        val trimmed = overrideHost?.trim().orEmpty()
        if (trimmed.isNotBlank()) return listOf(trimmed)

        val targets = linkedSetOf<String>()
        gatewayIpv4?.let { targets += it }
        if (subnet != null) {
            val parts = subnet.networkBase.split('.')
            if (parts.size == 4) {
                targets += "${parts[0]}.${parts[1]}.${parts[2]}.1"
            }
        }
        discoveredHosts.forEach { targets += it }
        return targets.toList()
    }

    suspend fun resolveTargets(
        context: Context,
        overrideHost: String?,
        onDiscoverProgress: suspend (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): List<String> = withContext(Dispatchers.IO) {
        val snapshot = ConnectionSnapshotCollector.collect(context, fetchPublicIp = false)
        val subnet = SubnetScanner.localSubnet(context)
        val discovered = if (subnet != null) {
            SubnetScanner.scan(subnet) { scanned, total, _ ->
                onDiscoverProgress(scanned, total)
            }.map { it.ip }
        } else {
            emptyList()
        }
        resolveTargets(overrideHost, snapshot.gatewayIpv4, subnet, discovered)
    }

    suspend fun scanMany(
        hosts: List<String>,
        ports: List<Int>,
        timeoutMs: Int = 900,
        batchSize: Int = 16,
        onProgress: suspend (
            hostIndex: Int,
            hostTotal: Int,
            host: String,
            scanned: Int,
            portTotal: Int,
            hit: PortScanHit?
        ) -> Unit = { _, _, _, _, _, _ -> }
    ): NetworkPortScanResult = withContext(Dispatchers.IO) {
        if (hosts.isEmpty()) {
            return@withContext NetworkPortScanResult(
                targets = emptyList(),
                results = emptyList(),
                portsPerHost = ports.size,
                error = "No devices found on your network"
            )
        }
        if (ports.isEmpty()) {
            return@withContext NetworkPortScanResult(
                targets = hosts,
                results = emptyList(),
                portsPerHost = 0,
                error = "No ports to scan"
            )
        }

        val results = hosts.mapIndexed { index, host ->
            coroutineContext.ensureActive()
            scan(host, ports, timeoutMs, batchSize) { scanned, total, hit ->
                onProgress(index + 1, hosts.size, host, scanned, total, hit)
            }
        }

        NetworkPortScanResult(
            targets = hosts,
            results = results,
            portsPerHost = ports.size
        )
    }

    suspend fun scan(
        host: String,
        ports: List<Int>,
        timeoutMs: Int = 900,
        batchSize: Int = 16,
        onProgress: suspend (scanned: Int, total: Int, hit: PortScanHit?) -> Unit = { _, _, _ -> }
    ): PortScanResult = withContext(Dispatchers.IO) {
        val trimmed = host.trim()
        if (trimmed.isBlank()) {
            return@withContext PortScanResult(trimmed, emptyList(), 0, "Enter a host IP or name")
        }
        if (ports.isEmpty()) {
            return@withContext PortScanResult(trimmed, emptyList(), 0, "No ports to scan")
        }

        val open = mutableListOf<PortScanHit>()
        var scanned = 0
        val total = ports.size

        ports.chunked(batchSize).forEach { batch ->
            coroutineContext.ensureActive()
            val results = coroutineScope {
                batch.map { port ->
                    async { port to probe(trimmed, port, timeoutMs) }
                }.awaitAll()
            }
            results.forEach { (port, ms) ->
                scanned++
                if (ms != null) {
                    val hit = PortScanHit(port, ms, SERVICE_NAMES[port])
                    open += hit
                    onProgress(scanned, total, hit)
                } else {
                    onProgress(scanned, total, null)
                }
            }
        }

        PortScanResult(
            host = trimmed,
            openPorts = open.sortedBy { it.port },
            portsScanned = total
        )
    }

    private fun probe(host: String, port: Int, timeoutMs: Int): Double? {
        return try {
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.nanoTime() - start) / 1_000_000.0
        } catch (_: Exception) {
            null
        }
    }

    fun format(result: PortScanResult): String {
        if (result.error != null) {
            return "Port scan · ${result.host}\n${result.error}"
        }
        return buildString {
            appendLine("Port scan · ${result.host}")
            appendLine("Scanned ${result.portsScanned} ports")
            if (result.openPorts.isEmpty()) {
                appendLine("No open ports found")
            } else {
                appendLine("${result.openPorts.size} open:")
                result.openPorts.forEach { hit ->
                    appendLine("  ${formatHit(hit)}")
                }
            }
        }.trim()
    }

    fun formatNetwork(result: NetworkPortScanResult): String {
        if (result.error != null) {
            return "Port scan · network\n${result.error}"
        }
        return buildString {
            appendLine("Port scan · network")
            appendLine("Devices scanned: ${result.targets.size}")
            appendLine("Ports per device: ${result.portsPerHost}")
            val withOpen = result.results.filter { it.openPorts.isNotEmpty() }
            if (withOpen.isEmpty()) {
                appendLine("No open ports found")
            } else {
                appendLine("${withOpen.size} device(s) with open ports:")
                withOpen.forEach { hostResult ->
                    appendLine()
                    appendLine(hostResult.host)
                    hostResult.openPorts.forEach { hit ->
                        appendLine("  ${formatHit(hit)}")
                    }
                }
            }
        }.trim()
    }

    private fun formatHit(hit: PortScanHit): String {
        val svc = hit.service?.let { " ($it)" }.orEmpty()
        return String.format(Locale.US, "%d%s · %.0f ms", hit.port, svc, hit.connectMs)
    }

    fun presetLabel(preset: PortScanPreset): String = when (preset) {
        PortScanPreset.COMMON -> "Common"
        PortScanPreset.WEB -> "Web"
        PortScanPreset.ROUTER -> "Router"
        PortScanPreset.EXTENDED -> "Extended"
    }
}

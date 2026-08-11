package com.towerscope.ar.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

enum class DiagnoseLayer {
    LINK,
    DNS,
    TCP,
    TLS,
    HTTP
}

enum class DiagnoseStatus {
    PENDING,
    RUNNING,
    PASS,
    FAIL,
    SKIPPED
}

data class DiagnoseLayerResult(
    val layer: DiagnoseLayer,
    val status: DiagnoseStatus,
    val title: String,
    val detail: String,
    val latencyMs: Double? = null,
    val fixHint: String? = null
)

data class DiagnoseReport(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val layers: List<DiagnoseLayerResult>,
    val brokeAt: DiagnoseLayer?,
    val summary: String,
    val fixHint: String?
)

data class DiagnoseTarget(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val httpUrl: String
)

/**
 * Layered on-device path check: link → DNS → TCP → TLS → HTTP.
 * Stops at the first failure so the UI can answer "where did it break?"
 *
 * Original TowerScope implementation (not derived from GPL sources).
 */
object NetworkDiagnose {

    fun parseTarget(raw: String): DiagnoseTarget {
        val trimmed = raw.trim().ifBlank { "1.1.1.1" }
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
        val url = runCatching { URL(withScheme) }.getOrElse {
            URL("https://${trimmed.substringBefore('/').substringBefore(':')}")
        }
        val host = url.host?.takeIf { it.isNotBlank() } ?: "1.1.1.1"
        val explicitPort = if (url.port > 0) url.port else -1
        val useTls = url.protocol.equals("https", ignoreCase = true)
        val port = when {
            explicitPort > 0 -> explicitPort
            useTls -> 443
            else -> 80
        }
        val path = url.path?.takeIf { it.isNotBlank() } ?: "/"
        val query = url.query?.let { "?$it" }.orEmpty()
        val httpUrl = "${url.protocol}://$host:$port$path$query"
        return DiagnoseTarget(host = host, port = port, useTls = useTls, httpUrl = httpUrl)
    }

    fun run(context: Context, rawTarget: String): Flow<DiagnoseReport> = flow {
        val target = parseTarget(rawTarget)
        val layers = mutableListOf(
            pending(DiagnoseLayer.LINK, "Local link"),
            pending(DiagnoseLayer.DNS, "DNS"),
            pending(DiagnoseLayer.TCP, "TCP ${target.port}"),
            pending(
                DiagnoseLayer.TLS,
                if (target.useTls) "TLS handshake" else "TLS"
            ),
            pending(DiagnoseLayer.HTTP, "HTTP")
        )

        fun snapshot(brokeAt: DiagnoseLayer? = null): DiagnoseReport {
            val failed = layers.firstOrNull { it.status == DiagnoseStatus.FAIL }
            val breakLayer = brokeAt ?: failed?.layer
            val summary = when {
                breakLayer != null -> "Broke at ${layerLabel(breakLayer)}"
                layers.all { it.status == DiagnoseStatus.PASS || it.status == DiagnoseStatus.SKIPPED } ->
                    "Path looks healthy"
                else -> "Diagnosing…"
            }
            return DiagnoseReport(
                host = target.host,
                port = target.port,
                useTls = target.useTls,
                layers = layers.toList(),
                brokeAt = breakLayer,
                summary = summary,
                fixHint = failed?.fixHint
            )
        }

        emit(snapshot())

        // 1) Local link
        setRunning(layers, DiagnoseLayer.LINK)
        emit(snapshot())
        val link = checkLink(context)
        replace(layers, link)
        emit(snapshot(if (link.status == DiagnoseStatus.FAIL) DiagnoseLayer.LINK else null))
        if (link.status == DiagnoseStatus.FAIL) {
            skipRemaining(layers, after = DiagnoseLayer.LINK)
            emit(snapshot(DiagnoseLayer.LINK))
            return@flow
        }

        // 2) DNS
        setRunning(layers, DiagnoseLayer.DNS)
        emit(snapshot())
        val dns = checkDns(target.host)
        replace(layers, dns)
        emit(snapshot(if (dns.status == DiagnoseStatus.FAIL) DiagnoseLayer.DNS else null))
        if (dns.status == DiagnoseStatus.FAIL) {
            skipRemaining(layers, after = DiagnoseLayer.DNS)
            emit(snapshot(DiagnoseLayer.DNS))
            return@flow
        }
        val resolvedIps = dns.detail.substringAfter("→", "").trim()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

        // 3) TCP
        setRunning(layers, DiagnoseLayer.TCP)
        emit(snapshot())
        val tcp = checkTcp(target.host, target.port)
        replace(layers, tcp)
        emit(snapshot(if (tcp.status == DiagnoseStatus.FAIL) DiagnoseLayer.TCP else null))
        if (tcp.status == DiagnoseStatus.FAIL) {
            skipRemaining(layers, after = DiagnoseLayer.TCP)
            emit(snapshot(DiagnoseLayer.TCP))
            return@flow
        }

        // 4) TLS
        if (!target.useTls) {
            replace(
                layers,
                DiagnoseLayerResult(
                    layer = DiagnoseLayer.TLS,
                    status = DiagnoseStatus.SKIPPED,
                    title = "TLS",
                    detail = "Skipped · plain HTTP target"
                )
            )
            emit(snapshot())
        } else {
            setRunning(layers, DiagnoseLayer.TLS)
            emit(snapshot())
            val tls = checkTls(target.host, target.port)
            replace(layers, tls)
            emit(snapshot(if (tls.status == DiagnoseStatus.FAIL) DiagnoseLayer.TLS else null))
            if (tls.status == DiagnoseStatus.FAIL) {
                skipRemaining(layers, after = DiagnoseLayer.TLS)
                emit(snapshot(DiagnoseLayer.TLS))
                return@flow
            }
        }

        // 5) HTTP
        setRunning(layers, DiagnoseLayer.HTTP)
        emit(snapshot())
        val http = checkHttp(target.httpUrl)
        replace(layers, http)
        emit(snapshot(if (http.status == DiagnoseStatus.FAIL) DiagnoseLayer.HTTP else null))

        // Attach resolved IPs into DNS detail if still short
        if (resolvedIps.isNotEmpty()) {
            val idx = layers.indexOfFirst { it.layer == DiagnoseLayer.DNS }
            if (idx >= 0 && !layers[idx].detail.contains("→")) {
                layers[idx] = layers[idx].copy(detail = "Resolved → ${resolvedIps.joinToString(", ")}")
            }
        }
        emit(snapshot())
    }.flowOn(Dispatchers.IO)

    private fun pending(layer: DiagnoseLayer, title: String) = DiagnoseLayerResult(
        layer = layer,
        status = DiagnoseStatus.PENDING,
        title = title,
        detail = "Waiting"
    )

    private fun setRunning(layers: MutableList<DiagnoseLayerResult>, layer: DiagnoseLayer) {
        val idx = layers.indexOfFirst { it.layer == layer }
        if (idx >= 0) {
            layers[idx] = layers[idx].copy(status = DiagnoseStatus.RUNNING, detail = "Checking…")
        }
    }

    private fun replace(layers: MutableList<DiagnoseLayerResult>, result: DiagnoseLayerResult) {
        val idx = layers.indexOfFirst { it.layer == result.layer }
        if (idx >= 0) layers[idx] = result
    }

    private fun skipRemaining(layers: MutableList<DiagnoseLayerResult>, after: DiagnoseLayer) {
        val order = DiagnoseLayer.entries
        val afterIdx = order.indexOf(after)
        for (i in layers.indices) {
            val layer = layers[i].layer
            if (order.indexOf(layer) > afterIdx && layers[i].status == DiagnoseStatus.PENDING) {
                layers[i] = layers[i].copy(
                    status = DiagnoseStatus.SKIPPED,
                    detail = "Skipped · earlier layer failed"
                )
            }
        }
    }

    private fun layerLabel(layer: DiagnoseLayer): String = when (layer) {
        DiagnoseLayer.LINK -> "local link"
        DiagnoseLayer.DNS -> "DNS"
        DiagnoseLayer.TCP -> "TCP"
        DiagnoseLayer.TLS -> "TLS"
        DiagnoseLayer.HTTP -> "HTTP"
    }

    private fun checkLink(context: Context): DiagnoseLayerResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        if (network == null || caps == null) {
            return DiagnoseLayerResult(
                layer = DiagnoseLayer.LINK,
                status = DiagnoseStatus.FAIL,
                title = "Local link",
                detail = "No active network",
                fixHint = "Turn on Wi‑Fi or mobile data, then retry."
            )
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi‑Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }
        if (!validated && transports.isEmpty()) {
            return DiagnoseLayerResult(
                layer = DiagnoseLayer.LINK,
                status = DiagnoseStatus.FAIL,
                title = "Local link",
                detail = "Interface up but no usable transport",
                fixHint = "Reconnect Wi‑Fi or toggle airplane mode."
            )
        }
        val label = transports.joinToString(" · ").ifBlank { "Network" }
        return DiagnoseLayerResult(
            layer = DiagnoseLayer.LINK,
            status = DiagnoseStatus.PASS,
            title = "Local link",
            detail = "$label · internet capability ${if (validated) "yes" else "unconfirmed"}"
        )
    }

    private suspend fun checkDns(host: String): DiagnoseLayerResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        // Literal IP — DNS not required
        if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || host.contains(':')) {
            return@withContext DiagnoseLayerResult(
                layer = DiagnoseLayer.DNS,
                status = DiagnoseStatus.PASS,
                title = "DNS",
                detail = "Literal address · $host",
                latencyMs = 0.0
            )
        }
        val start = System.nanoTime()
        return@withContext try {
            val addresses = InetAddress.getAllByName(host)
            val ms = (System.nanoTime() - start) / 1_000_000.0
            if (addresses.isEmpty()) {
                DiagnoseLayerResult(
                    layer = DiagnoseLayer.DNS,
                    status = DiagnoseStatus.FAIL,
                    title = "DNS",
                    detail = "No addresses returned",
                    latencyMs = ms,
                    fixHint = "Check DNS server settings, or try a public resolver (1.1.1.1 / 8.8.8.8)."
                )
            } else {
                DiagnoseLayerResult(
                    layer = DiagnoseLayer.DNS,
                    status = DiagnoseStatus.PASS,
                    title = "DNS",
                    detail = "Resolved → ${addresses.joinToString(", ") { it.hostAddress ?: "?" }}",
                    latencyMs = ms
                )
            }
        } catch (e: Exception) {
            val ms = (System.nanoTime() - start) / 1_000_000.0
            DiagnoseLayerResult(
                layer = DiagnoseLayer.DNS,
                status = DiagnoseStatus.FAIL,
                title = "DNS",
                detail = e.message ?: "Lookup failed",
                latencyMs = ms,
                fixHint = "Hostname did not resolve. Verify spelling, DNS, or captive portal login."
            )
        }
    }

    private suspend fun checkTcp(host: String, port: Int): DiagnoseLayerResult =
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val start = System.nanoTime()
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), 4_000)
                }
                val ms = (System.nanoTime() - start) / 1_000_000.0
                DiagnoseLayerResult(
                    layer = DiagnoseLayer.TCP,
                    status = DiagnoseStatus.PASS,
                    title = "TCP $port",
                    detail = "Connected to $host:$port",
                    latencyMs = ms
                )
            } catch (e: Exception) {
                val ms = (System.nanoTime() - start) / 1_000_000.0
                DiagnoseLayerResult(
                    layer = DiagnoseLayer.TCP,
                    status = DiagnoseStatus.FAIL,
                    title = "TCP $port",
                    detail = e.message ?: "Connect failed",
                    latencyMs = ms,
                    fixHint = "Port $port is closed, filtered, or unreachable. Check firewall, VPN, or try another host."
                )
            }
        }

    private suspend fun checkTls(host: String, port: Int): DiagnoseLayerResult =
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val start = System.nanoTime()
            try {
                val factory = SSLSocketFactory.getDefault()
                (factory.createSocket() as SSLSocket).use { ssl ->
                    ssl.soTimeout = 5_000
                    ssl.connect(InetSocketAddress(host, port), 4_000)
                    ssl.soTimeout = 5_000
                    // SNI
                    try {
                        val params = ssl.sslParameters
                        params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                        ssl.sslParameters = params
                    } catch (_: Exception) {
                        // Older devices may not support SNI params; handshake may still work.
                    }
                    ssl.startHandshake()
                    val session = ssl.session
                    val peer = session.peerCertificates.firstOrNull() as? X509Certificate
                    val subject = peer?.subjectX500Principal?.name
                        ?.substringAfter("CN=", "")
                        ?.substringBefore(',')
                        ?.ifBlank { null }
                    val ms = (System.nanoTime() - start) / 1_000_000.0
                    DiagnoseLayerResult(
                        layer = DiagnoseLayer.TLS,
                        status = DiagnoseStatus.PASS,
                        title = "TLS handshake",
                        detail = buildString {
                            append(session.protocol)
                            append(" · ")
                            append(session.cipherSuite.substringAfter("TLS_", session.cipherSuite))
                            if (subject != null) append(" · CN=$subject")
                        },
                        latencyMs = ms
                    )
                }
            } catch (e: Exception) {
                val ms = (System.nanoTime() - start) / 1_000_000.0
                DiagnoseLayerResult(
                    layer = DiagnoseLayer.TLS,
                    status = DiagnoseStatus.FAIL,
                    title = "TLS handshake",
                    detail = e.message ?: "Handshake failed",
                    latencyMs = ms,
                    fixHint = "Certificate or TLS negotiation failed. Check date/time, MITM proxy, or HTTPS interception."
                )
            }
        }

    private suspend fun checkHttp(httpUrl: String): DiagnoseLayerResult =
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val start = System.nanoTime()
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(httpUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 6_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "TowerScope-Diagnose/1.0")
                    setRequestProperty("Accept", "*/*")
                    useCaches = false
                }
                val code = connection.responseCode
                runCatching {
                    (if (code in 200..399) connection.inputStream else connection.errorStream)
                        ?.use { stream ->
                            val buf = ByteArray(512)
                            while (stream.read(buf) != -1) {
                                // drain a little so some servers finish cleanly
                            }
                        }
                }
                val ms = (System.nanoTime() - start) / 1_000_000.0
                // Any HTTP response means the application layer answered.
                val ok = code in 100..599
                DiagnoseLayerResult(
                    layer = DiagnoseLayer.HTTP,
                    status = if (ok) DiagnoseStatus.PASS else DiagnoseStatus.FAIL,
                    title = "HTTP",
                    detail = "HTTP $code · ${connection.responseMessage ?: ""}".trim(),
                    latencyMs = ms,
                    fixHint = if (ok) null else "No HTTP response. Service may be down or blocking this client."
                )
            } catch (e: Exception) {
                val ms = (System.nanoTime() - start) / 1_000_000.0
                DiagnoseLayerResult(
                    layer = DiagnoseLayer.HTTP,
                    status = DiagnoseStatus.FAIL,
                    title = "HTTP",
                    detail = e.message ?: "Request failed",
                    latencyMs = ms,
                    fixHint = "TCP/TLS worked but HTTP failed. Check URL path, proxy, or server health."
                )
            } finally {
                connection?.disconnect()
            }
        }
}

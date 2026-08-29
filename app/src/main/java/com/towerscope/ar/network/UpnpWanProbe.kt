package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

data class UpnpWanInfo(
    val downstreamBps: Long?,
    val upstreamBps: Long?,
    val physicalStatus: String?,
    val accessType: String?,
    val source: String
)

/**
 * Passive UPnP IGD probe — works on some routers with UPnP enabled by default
 * (no SNMP or admin login). Not available on all routers (e.g. many MikroTik setups).
 */
object UpnpWanProbe {

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val TIMEOUT_MS = 2_500

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "upnp:rootdevice",
        "urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1"
    )

    suspend fun probe(gatewayHost: String?): UpnpWanInfo? = withContext(Dispatchers.IO) {
        val host = gatewayHost?.trim().orEmpty()
        if (host.isBlank()) return@withContext null

        val descriptionUrls = linkedSetOf<String>()
        descriptionUrls += discoverDescriptionUrls(host)
        descriptionUrls += commonDescriptionUrls(host)

        for (descriptionUrl in descriptionUrls) {
            val controlUrl = parseWanControlUrl(fetchText(descriptionUrl) ?: continue) ?: continue
            val resolvedControl = resolveUrl(descriptionUrl, controlUrl) ?: continue
            val soap = fetchText(
                url = resolvedControl,
                method = "POST",
                body = soapGetCommonLinkProperties(),
                headers = mapOf(
                    "Content-Type" to "text/xml; charset=\"utf-8\"",
                    "SOAPAction" to "\"urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1#GetCommonLinkProperties\""
                )
            ) ?: continue
            val info = parseCommonLinkProperties(soap, resolvedControl) ?: continue
            if (info.downstreamBps != null || info.upstreamBps != null) return@withContext info
        }
        null
    }

    internal fun parseCommonLinkProperties(xml: String, source: String): UpnpWanInfo? {
        val downstream = readSoapULong(xml, "NewLayer1DownstreamMaxBitRate")
        val upstream = readSoapULong(xml, "NewLayer1UpstreamMaxBitRate")
        val status = readSoapString(xml, "NewPhysicalLinkStatus")
        val access = readSoapString(xml, "NewWANAccessType")
        if (downstream == null && upstream == null) return null
        return UpnpWanInfo(
            downstreamBps = downstream,
            upstreamBps = upstream,
            physicalStatus = status,
            accessType = access,
            source = source
        )
    }

    internal fun parseWanControlUrl(descriptionXml: String): String? {
        val services = descriptionXml.split("<service>", "</service>")
        for (chunk in services) {
            if (!chunk.contains("WANCommonInterfaceConfig", ignoreCase = true)) continue
            val control = readXmlTag(chunk, "controlURL") ?: continue
            if (control.isNotBlank()) return control.trim()
        }
        return null
    }

    private fun discoverDescriptionUrls(gatewayHost: String): Set<String> {
        val urls = linkedSetOf<String>()
        for (target in SEARCH_TARGETS) {
            urls += ssdpSearch(target, gatewayHost)
            urls += ssdpSearch(target, SSDP_ADDR)
        }
        return urls
    }

    private fun commonDescriptionUrls(gatewayHost: String): List<String> = listOf(
        "http://$gatewayHost:49000/igddesc.xml",
        "http://$gatewayHost:52869/picsdesc.xml"
    )

    private fun ssdpSearch(searchTarget: String, host: String): Set<String> {
        val urls = linkedSetOf<String>()
        val payload = buildString {
            appendLine("M-SEARCH * HTTP/1.1")
            appendLine("HOST: $host:$SSDP_PORT")
            appendLine("MAN: \"ssdp:discover\"")
            appendLine("MX: 2")
            appendLine("ST: $searchTarget")
            appendLine()
            appendLine()
        }.toByteArray(StandardCharsets.UTF_8)

        val socket = DatagramSocket()
        socket.soTimeout = TIMEOUT_MS
        return try {
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(payload, payload.size, address, SSDP_PORT))
            val buffer = ByteArray(8_192)
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.onFailure { break }
                val response = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                parseLocationHeader(response)?.let { urls += it }
            }
            urls
        } catch (_: Exception) {
            urls
        } finally {
            socket.close()
        }
    }

    private fun parseLocationHeader(response: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.startsWith("http", ignoreCase = true) }

    private fun soapGetCommonLinkProperties(): String =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:GetCommonLinkProperties xmlns:u="urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1" />
          </s:Body>
        </s:Envelope>
        """.trimIndent()

    private fun fetchText(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = method
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: return null
            stream.bufferedReader().use(BufferedReader::readText)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveUrl(baseUrl: String, path: String): String? = runCatching {
        if (path.startsWith("http", ignoreCase = true)) return@runCatching path
        URL(URL(baseUrl), path).toString()
    }.getOrNull()

    private fun readSoapULong(xml: String, tag: String): Long? =
        readXmlTag(xml, tag)?.toLongOrNull()

    private fun readSoapString(xml: String, tag: String): String? =
        readXmlTag(xml, tag)

    private fun readXmlTag(xml: String, tag: String): String? {
        val open = "<$tag"
        val start = xml.indexOf(open, ignoreCase = true)
        if (start < 0) return null
        val contentStart = xml.indexOf('>', start)
        if (contentStart < 0) return null
        val close = xml.indexOf("</$tag>", contentStart, ignoreCase = true)
        if (close < 0) return null
        return xml.substring(contentStart + 1, close).trim()
    }

    fun format(info: UpnpWanInfo): String = buildString {
        appendLine("Router WAN (UPnP)")
        info.downstreamBps?.let { bps ->
            LinkSpeedClassifier.labelFromBps(bps)?.let { appendLine("Downstream: $it") }
                ?: appendLine("Downstream: ${bps / 1_000_000} Mbps")
        }
        info.upstreamBps?.let { bps ->
            LinkSpeedClassifier.labelFromBps(bps)?.let { appendLine("Upstream: $it") }
        }
        info.physicalStatus?.let { appendLine("Link: $it") }
        info.accessType?.let { appendLine("Type: $it") }
        appendLine("(UPnP — works when router has UPnP on; not all routers support this)")
    }.trim()

    fun formatUnavailable(): String =
        "Router WAN: not exposed without router setup.\n" +
            "Many customer routers block UPnP/SNMP by default. Wi‑Fi link above is what your phone can see directly."
}

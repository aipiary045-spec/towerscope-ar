package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class RouterInterface(
    val index: Int,
    val name: String,
    val speedBps: Long?,
    val speedMbps: Long?,
    val operStatus: Int?
)

data class PhoneLinkInfo(
    val ssid: String?,
    val linkMbps: Int?,
    val connected: Boolean
)

data class WanLinkResult(
    val routerHost: String,
    val community: String,
    val selectedInterface: RouterInterface?,
    val interfaces: List<RouterInterface>,
    val phoneLink: PhoneLinkInfo? = null,
    val error: String? = null
)

/**
 * Reads router WAN / uplink Ethernet negotiated speed via SNMP (IF-MIB).
 * Also surfaces phone Wi‑Fi negotiated link speed when available.
 */
object RouterWanLink {

    private const val IF_NAME = "1.3.6.1.2.1.31.1.1.1.1"
    private const val IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15"
    private const val IF_DESCR = "1.3.6.1.2.1.2.2.1.2"
    private const val IF_SPEED = "1.3.6.1.2.1.2.2.1.5"
    private const val IF_OPER_STATUS = "1.3.6.1.2.1.2.2.1.8"

    private val FALLBACK_COMMUNITIES = listOf("public", "private")

    fun wanMatchScore(name: String): Int {
        val n = name.lowercase(Locale.US)
        return when {
            n.contains("wan") -> 100
            n.contains("internet") -> 95
            n.contains("pppoe") || n.contains("ppp") -> 90
            n == "ether1" -> 85
            n.startsWith("sfp") -> 80
            n.matches(Regex("""eth0(\.\d+)?""")) -> 75
            n.contains("dsl") || n.contains("adsl") || n.contains("vdsl") -> 70
            n.contains("uplink") || n.contains("upstream") -> 65
            else -> 0
        }
    }

    fun resolveMbps(iface: RouterInterface): Long? {
        iface.speedMbps?.takeIf { it > 0 }?.let { return it }
        iface.speedBps?.takeIf { it > 0 }?.let { return it / 1_000_000 }
        return null
    }

    fun negotiatedEthernetLabel(iface: RouterInterface): String? {
        val mbps = resolveMbps(iface) ?: return null
        return when {
            mbps <= 15 -> "10 Mbps"
            mbps <= 150 -> "100 Mbps"
            mbps <= 1_500 -> "1000 Mbps"
            mbps < 10_000 -> String.format(Locale.US, "%d Mbps", mbps)
            else -> String.format(Locale.US, "%.1f Gbps", mbps / 1000.0)
        }
    }

    fun formatLinkSpeed(iface: RouterInterface): String? =
        negotiatedEthernetLabel(iface)

    fun formatPhoneLink(info: PhoneLinkInfo): String {
        if (!info.connected || info.linkMbps == null || info.linkMbps <= 0) {
            return "Wi‑Fi link: not connected"
        }
        val ssid = info.ssid?.let { " ($it)" }.orEmpty()
        val label = when {
            info.linkMbps <= 15 -> "10 Mbps"
            info.linkMbps <= 150 -> "100 Mbps"
            info.linkMbps <= 1_500 -> "1000 Mbps"
            else -> "${info.linkMbps} Mbps"
        }
        return "Wi‑Fi link$ssid: $label (phone ↔ router)"
    }

    fun formatOperStatus(status: Int?): String = when (status) {
        1 -> "up"
        2 -> "down"
        3 -> "testing"
        else -> status?.toString() ?: "unknown"
    }

    fun pickWanInterface(interfaces: List<RouterInterface>): RouterInterface? {
        if (interfaces.isEmpty()) return null
        return interfaces
            .sortedWith(
                compareByDescending<RouterInterface> { wanMatchScore(it.name) }
                    .thenByDescending { it.operStatus == 1 }
                    .thenByDescending { resolveMbps(it) ?: 0 }
            )
            .firstOrNull { wanMatchScore(it.name) > 0 }
            ?: interfaces.maxByOrNull { resolveMbps(it) ?: 0L }
    }

    suspend fun query(
        host: String,
        community: String = "public",
        phoneLink: PhoneLinkInfo? = null
    ): WanLinkResult = withContext(Dispatchers.IO) {
        val trimmedHost = host.trim()
        val communities = buildList {
            val trimmed = community.trim()
            if (trimmed.isNotBlank()) add(trimmed)
            addAll(FALLBACK_COMMUNITIES)
        }.distinct()

        if (trimmedHost.isBlank()) {
            return@withContext WanLinkResult(
                routerHost = host,
                community = community,
                selectedInterface = null,
                interfaces = emptyList(),
                phoneLink = phoneLink,
                error = "Enter your router IP (defaults to gateway)"
            )
        }

        var lastError: String? = null
        for (candidate in communities) {
            val result = runCatching { loadInterfaces(trimmedHost, candidate) }
            if (result.isSuccess) {
                val interfaces = result.getOrThrow()
                if (interfaces.isNotEmpty()) {
                    val selected = pickWanInterface(interfaces)
                    return@withContext WanLinkResult(
                        routerHost = trimmedHost,
                        community = candidate,
                        selectedInterface = selected,
                        interfaces = interfaces.sortedBy { it.index },
                        phoneLink = phoneLink,
                        error = if (selected == null || wanMatchScore(selected.name) == 0) {
                            "SNMP works, but no obvious WAN port was found — check the list below."
                        } else {
                            null
                        }
                    )
                }
                lastError = "SNMP responded but returned no interfaces with community \"$candidate\"."
            } else {
                lastError = result.exceptionOrNull()?.message ?: "SNMP failed"
            }
        }

        WanLinkResult(
            routerHost = trimmedHost,
            community = communities.first(),
            selectedInterface = null,
            interfaces = emptyList(),
            phoneLink = phoneLink,
            error = buildString {
                append("Could not read router WAN speed via SNMP. ")
                append(lastError ?: "No response on UDP 161.")
                append("\n\nEnable SNMP on the router (MikroTik: IP → SNMP). ")
                append("Your phone Wi‑Fi link speed is shown above — that is not the WAN port.")
            }
        )
    }

    fun format(result: WanLinkResult): String = buildString {
        appendLine("Link speed")
        result.phoneLink?.let { appendLine(formatPhoneLink(it)) }
        appendLine()
        appendLine("Router WAN (SNMP)")
        appendLine("Router: ${result.routerHost}")
        appendLine("Community: ${result.community}")
        val selected = result.selectedInterface
        if (selected != null) {
            appendLine("WAN port: ${selected.name}")
            negotiatedEthernetLabel(selected)?.let { appendLine("Negotiated: $it") }
            appendLine("Status: ${formatOperStatus(selected.operStatus)}")
        }
        if (result.error != null) {
            appendLine()
            appendLine(result.error)
        }
        if (result.interfaces.isNotEmpty()) {
            appendLine()
            appendLine("All interfaces (${result.interfaces.size}):")
            result.interfaces.forEach { iface ->
                val speed = negotiatedEthernetLabel(iface) ?: "—"
                val marker = if (iface.index == selected?.index) " *" else ""
                appendLine(
                    String.format(
                        Locale.US,
                        "  %s · %s · %s%s",
                        iface.name,
                        speed,
                        formatOperStatus(iface.operStatus),
                        marker
                    )
                )
            }
        }
    }.trim()

    private fun loadInterfaces(host: String, community: String): List<RouterInterface> {
        val snmpVersion = SnmpClient.resolveVersion(host, community)
            ?: throw IllegalStateException("No SNMP response on UDP 161 (community \"$community\")")

        val names = readNameTable(host, community, snmpVersion)
        val descr = readIndexedString(host, community, IF_DESCR, snmpVersion)
        val allNames = if (names.isNotEmpty()) names else descr
        if (allNames.isEmpty()) return emptyList()

        val highSpeed = readIndexedLong(host, community, IF_HIGH_SPEED, snmpVersion)
        val speedBps = readIndexedLong(host, community, IF_SPEED, snmpVersion)
        val oper = readIndexedInt(host, community, IF_OPER_STATUS, snmpVersion)

        return allNames.map { (index, name) ->
            RouterInterface(
                index = index,
                name = name,
                speedBps = speedBps[index]?.takeIf { it > 0 },
                speedMbps = highSpeed[index]?.takeIf { it > 0 },
                operStatus = oper[index]
            )
        }
    }

    private fun walkTable(
        host: String,
        community: String,
        baseOid: String,
        snmpVersion: Int
    ): List<SnmpVarbind> =
        SnmpClient.walk(host, community, baseOid, snmpVersion)

    private fun readNameTable(host: String, community: String, snmpVersion: Int): Map<Int, String> =
        walkTable(host, community, IF_NAME, snmpVersion)
            .mapNotNull { vb ->
                val index = vb.oid.substringAfterLast('.').toIntOrNull() ?: return@mapNotNull null
                val name = vb.value.asDisplayString() ?: return@mapNotNull null
                index to name
            }
            .toMap()

    private fun readIndexedString(
        host: String,
        community: String,
        baseOid: String,
        snmpVersion: Int
    ): Map<Int, String> =
        walkTable(host, community, baseOid, snmpVersion)
            .mapNotNull { vb ->
                val index = vb.oid.substringAfterLast('.').toIntOrNull() ?: return@mapNotNull null
                val value = vb.value.asDisplayString() ?: return@mapNotNull null
                index to value
            }
            .toMap()

    private fun readIndexedInt(
        host: String,
        community: String,
        baseOid: String,
        snmpVersion: Int
    ): Map<Int, Int> =
        walkTable(host, community, baseOid, snmpVersion)
            .mapNotNull { vb ->
                val index = vb.oid.substringAfterLast('.').toIntOrNull() ?: return@mapNotNull null
                val value = vb.value.asNumber()?.toInt() ?: return@mapNotNull null
                index to value
            }
            .toMap()

    private fun readIndexedLong(
        host: String,
        community: String,
        baseOid: String,
        snmpVersion: Int
    ): Map<Int, Long> =
        walkTable(host, community, baseOid, snmpVersion)
            .mapNotNull { vb ->
                val index = vb.oid.substringAfterLast('.').toIntOrNull() ?: return@mapNotNull null
                val value = vb.value.asNumber() ?: return@mapNotNull null
                index to value
            }
            .toMap()

    private fun SnmpValue.asDisplayString(): String? = when (this) {
        is SnmpValue.Octets -> String(bytes, Charsets.UTF_8).trim().ifBlank { null }
        is SnmpValue.Integer -> value.toString()
        is SnmpValue.Gauge -> value.toString()
        else -> null
    }

    private fun SnmpValue.asNumber(): Long? = when (this) {
        is SnmpValue.Integer -> value.toLong()
        is SnmpValue.Gauge -> value
        else -> null
    }
}

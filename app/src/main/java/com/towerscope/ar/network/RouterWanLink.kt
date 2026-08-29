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

data class WanLinkResult(
    val routerHost: String,
    val community: String,
    val selectedInterface: RouterInterface?,
    val interfaces: List<RouterInterface>,
    val error: String? = null
)

/**
 * Reads router WAN / uplink Ethernet negotiated speed via SNMP (IF-MIB).
 * Requires SNMP enabled on the router (UDP 161).
 */
object RouterWanLink {

    private const val IF_NAME = "1.3.6.1.2.1.31.1.1.1.1"
    private const val IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15"
    private const val IF_DESCR = "1.3.6.1.2.1.2.2.1.2"
    private const val IF_SPEED = "1.3.6.1.2.1.2.2.1.5"
    private const val IF_OPER_STATUS = "1.3.6.1.2.1.2.2.1.8"

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

    fun formatLinkSpeed(iface: RouterInterface): String? {
        iface.speedMbps?.takeIf { it > 0 }?.let { mbps ->
            return if (mbps >= 1000) {
                String.format(Locale.US, "%.1f Gbps", mbps / 1000.0)
            } else {
                String.format(Locale.US, "%d Mbps", mbps)
            }
        }
        iface.speedBps?.takeIf { it > 0 }?.let { bps ->
            val mbps = bps / 1_000_000.0
            return if (mbps >= 1000) {
                String.format(Locale.US, "%.1f Gbps", mbps / 1000.0)
            } else {
                String.format(Locale.US, "%.0f Mbps", mbps)
            }
        }
        return null
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
                    .thenByDescending { it.speedMbps ?: 0 }
                    .thenByDescending { it.speedBps ?: 0L }
            )
            .firstOrNull { wanMatchScore(it.name) > 0 }
            ?: interfaces.maxByOrNull { it.speedMbps ?: 0 }
    }

    suspend fun query(
        host: String,
        community: String = "public"
    ): WanLinkResult = withContext(Dispatchers.IO) {
        val trimmedHost = host.trim()
        val trimmedCommunity = community.trim().ifBlank { "public" }
        if (trimmedHost.isBlank()) {
            return@withContext WanLinkResult(
                routerHost = host,
                community = trimmedCommunity,
                selectedInterface = null,
                interfaces = emptyList(),
                error = "Enter your router IP (defaults to gateway)"
            )
        }

        runCatching {
            val interfaces = loadInterfaces(trimmedHost, trimmedCommunity)
            if (interfaces.isEmpty()) {
                WanLinkResult(
                    routerHost = trimmedHost,
                    community = trimmedCommunity,
                    selectedInterface = null,
                    interfaces = emptyList(),
                    error = "No SNMP interfaces returned. Enable SNMP on the router (UDP 161) " +
                        "and check the community string."
                )
            } else {
                val selected = pickWanInterface(interfaces)
                WanLinkResult(
                    routerHost = trimmedHost,
                    community = trimmedCommunity,
                    selectedInterface = selected,
                    interfaces = interfaces.sortedBy { it.index },
                    error = if (selected == null || wanMatchScore(selected.name) == 0) {
                        "SNMP works, but no obvious WAN interface was found. See the interface list below."
                    } else {
                        null
                    }
                )
            }
        }.getOrElse { error ->
            WanLinkResult(
                routerHost = trimmedHost,
                community = trimmedCommunity,
                selectedInterface = null,
                interfaces = emptyList(),
                error = "SNMP query failed: ${error.message ?: "timeout or blocked"}. " +
                    "Enable SNMP on the router and allow UDP 161 from your phone."
            )
        }
    }

    fun format(result: WanLinkResult): String = buildString {
        appendLine("Router WAN link (SNMP)")
        appendLine("Router: ${result.routerHost}")
        appendLine("Community: ${result.community}")
        val selected = result.selectedInterface
        if (selected != null) {
            appendLine()
            appendLine("WAN interface: ${selected.name}")
            formatLinkSpeed(selected)?.let { appendLine("Link speed: $it") }
            appendLine("Status: ${formatOperStatus(selected.operStatus)}")
        }
        if (result.error != null) {
            appendLine()
            appendLine(result.error)
        }
        if (result.interfaces.isNotEmpty()) {
            appendLine()
            appendLine("Interfaces (${result.interfaces.size}):")
            result.interfaces.forEach { iface ->
                val speed = formatLinkSpeed(iface) ?: "—"
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
        val names = readNameTable(host, community)
        if (names.isNotEmpty()) {
            val highSpeed = readIndexedInt(host, community, IF_HIGH_SPEED)
            val oper = readIndexedInt(host, community, IF_OPER_STATUS)
            return names.map { (index, name) ->
                RouterInterface(
                    index = index,
                    name = name,
                    speedBps = null,
                    speedMbps = highSpeed[index]?.toLong(),
                    operStatus = oper[index]
                )
            }
        }

        val descr = readIndexedString(host, community, IF_DESCR)
        val speed = readIndexedLong(host, community, IF_SPEED)
        val oper = readIndexedInt(host, community, IF_OPER_STATUS)
        return descr.map { (index, name) ->
            RouterInterface(
                index = index,
                name = name,
                speedBps = speed[index],
                speedMbps = null,
                operStatus = oper[index]
            )
        }
    }

    private fun readNameTable(host: String, community: String): Map<Int, String> =
        SnmpClient.walk(host, community, IF_NAME)
            .mapNotNull { vb ->
                val index = vb.oid.substringAfterLast('.').toIntOrNull() ?: return@mapNotNull null
                val name = vb.value.asDisplayString() ?: return@mapNotNull null
                index to name
            }
            .toMap()

    private fun readIndexedString(host: String, community: String, baseOid: String): Map<Int, String> =
        SnmpClient.walk(host, community, baseOid)
            .mapNotNull { vb ->
                val index = vb.oid.substringAfterLast('.').toIntOrNull() ?: return@mapNotNull null
                val value = vb.value.asDisplayString() ?: return@mapNotNull null
                index to value
            }
            .toMap()

    private fun readIndexedInt(host: String, community: String, baseOid: String): Map<Int, Int> =
        SnmpClient.walk(host, community, baseOid)
            .mapNotNull { vb ->
                val index = vb.oid.substringAfterLast('.').toIntOrNull() ?: return@mapNotNull null
                val value = (vb.value as? SnmpValue.Integer)?.value ?: return@mapNotNull null
                index to value
            }
            .toMap()

    private fun readIndexedLong(host: String, community: String, baseOid: String): Map<Int, Long> =
        SnmpClient.walk(host, community, baseOid)
            .mapNotNull { vb ->
                val index = vb.oid.substringAfterLast('.').toIntOrNull() ?: return@mapNotNull null
                val value = (vb.value as? SnmpValue.Integer)?.value?.toLong() ?: return@mapNotNull null
                index to value
            }
            .toMap()

    private fun SnmpValue.asDisplayString(): String? = when (this) {
        is SnmpValue.Octets -> String(bytes, Charsets.UTF_8).trim().ifBlank { null }
        is SnmpValue.Integer -> value.toString()
        else -> null
    }
}

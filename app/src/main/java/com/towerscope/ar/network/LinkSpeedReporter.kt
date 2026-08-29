package com.towerscope.ar.network

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class LinkSpeedReport(
    val phoneLink: PhoneLinkInfo,
    val upnpWan: UpnpWanInfo?,
    val snmp: WanLinkResult? = null
)

object LinkSpeedReporter {

    suspend fun collect(
        gatewayHost: String?,
        phoneLink: PhoneLinkInfo,
        snmpCommunity: String? = null,
        trySnmp: Boolean = false
    ): LinkSpeedReport = coroutineScope {
        val upnp = async { UpnpWanProbe.probe(gatewayHost) }
        val snmp = if (trySnmp && !gatewayHost.isNullOrBlank()) {
            async {
                RouterWanLink.query(
                    host = gatewayHost,
                    community = snmpCommunity.orEmpty(),
                    phoneLink = null
                )
            }
        } else {
            null
        }
        LinkSpeedReport(
            phoneLink = phoneLink,
            upnpWan = upnp.await(),
            snmp = snmp?.await()
        )
    }

    fun format(report: LinkSpeedReport): String = buildString {
        appendLine("Link speed")
        appendLine(RouterWanLink.formatPhoneLink(report.phoneLink))
        appendLine()
        when {
            report.upnpWan != null -> appendLine(UpnpWanProbe.format(report.upnpWan))
            report.snmp?.selectedInterface != null -> append(RouterWanLink.format(report.snmp))
            else -> {
                appendLine(UpnpWanProbe.formatUnavailable())
                report.snmp?.error?.let {
                    appendLine()
                    appendLine("SNMP (optional): $it")
                }
            }
        }
    }.trim()

    fun statusLine(report: LinkSpeedReport): String {
        report.phoneLink.linkMbps?.takeIf { report.phoneLink.connected }?.let { mbps ->
            LinkSpeedClassifier.labelFromMbps(mbps.toLong())?.let { label ->
                return "Wi‑Fi $label"
            }
        }
        report.upnpWan?.downstreamBps?.let { bps ->
            LinkSpeedClassifier.labelFromBps(bps)?.let { label ->
                return "WAN $label (UPnP)"
            }
        }
        report.snmp?.selectedInterface?.let { iface ->
            RouterWanLink.negotiatedEthernetLabel(iface)?.let { label ->
                return "WAN $label · ${iface.name}"
            }
        }
        return if (report.phoneLink.connected) "Wi‑Fi connected" else "Not on Wi‑Fi"
    }
}

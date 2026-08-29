package com.towerscope.ar.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.Locale

data class ConnectionSnapshot(
    val linkType: String,
    val isConnected: Boolean,
    val isValidated: Boolean,
    val wifiSsid: String?,
    val wifiBssid: String?,
    val wifiRssiDbm: Int?,
    val wifiChannel: Int?,
    val wifiBand: String?,
    val wifiLinkSpeedMbps: Int?,
    val localIpv4: String?,
    val gatewayIpv4: String?,
    val dnsServers: List<String>,
    val publicIpv4: String?,
    val publicIpv4Error: String?,
    val mtu: Int?
)

/**
 * One-shot snapshot of how this device is connected right now.
 */
object ConnectionSnapshotCollector {

    suspend fun collect(context: Context, fetchPublicIp: Boolean = true): ConnectionSnapshot =
        withContext(Dispatchers.IO) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val props = network?.let { cm.getLinkProperties(it) }

            val linkType = when {
                caps == null -> "None"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Other"
            }

            val wifi = WifiMonitor(context)
            val link = wifi.currentLink()
            val localIp = props?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }
                ?.address?.hostAddress
            val gateway = props?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
            val dns = props?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
            val mtu = props?.mtu?.takeIf { it > 0 }

            var publicIp: String? = null
            var publicError: String? = null
            if (fetchPublicIp && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                val fetched = fetchPublicIpv4()
                publicIp = fetched.first
                publicError = fetched.second
            }

            ConnectionSnapshot(
                linkType = linkType,
                isConnected = network != null && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                wifiSsid = link.ssid,
                wifiBssid = link.bssid,
                wifiRssiDbm = link.rssiDbm,
                wifiChannel = link.channel,
                wifiBand = link.band,
                wifiLinkSpeedMbps = link.linkSpeedMbps,
                localIpv4 = localIp,
                gatewayIpv4 = gateway,
                dnsServers = dns,
                publicIpv4 = publicIp,
                publicIpv4Error = publicError,
                mtu = mtu
            )
        }

    fun format(snapshot: ConnectionSnapshot): String = buildString {
        appendLine("Connection snapshot")
        appendLine("Link: ${snapshot.linkType}")
        appendLine("Internet: ${if (snapshot.isConnected) "yes" else "no"}")
        appendLine("Validated: ${if (snapshot.isValidated) "yes" else "no"}")
        snapshot.wifiSsid?.let { appendLine("Wi‑Fi: $it") }
        snapshot.wifiRssiDbm?.let { appendLine("Signal: $it dBm") }
        snapshot.wifiChannel?.let { ch ->
            appendLine("Channel: $ch (${snapshot.wifiBand ?: "?"})")
        }
        snapshot.wifiLinkSpeedMbps?.let { appendLine("Link speed: $it Mbps") }
        snapshot.localIpv4?.let { appendLine("Local IP: $it") }
        snapshot.gatewayIpv4?.let { appendLine("Gateway: $it") }
        if (snapshot.dnsServers.isNotEmpty()) {
            appendLine("DNS: ${snapshot.dnsServers.joinToString(", ")}")
        }
        snapshot.mtu?.let { appendLine("MTU: $it") }
        snapshot.publicIpv4?.let { appendLine("Public IP: $it") }
        snapshot.publicIpv4Error?.let { appendLine("Public IP: $it") }
    }.trim()

    private fun fetchPublicIpv4(): Pair<String?, String?> {
        val endpoints = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip"
        )
        for (url in endpoints) {
            val result = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.requestMethod = "GET"
                conn.inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrNull()
            if (!result.isNullOrBlank() && result.matches(Regex("""[\d.]+"""))) {
                return result to null
            }
        }
        return null to "Could not reach public IP service"
    }
}

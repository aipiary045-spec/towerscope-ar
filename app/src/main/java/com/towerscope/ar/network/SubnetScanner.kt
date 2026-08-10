package com.towerscope.ar.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min

data class SubnetInfo(
    val localIp: String,
    val prefixLength: Int,
    val networkBase: String,
    val hostCount: Int
)

data class SubnetHost(
    val ip: String,
    val hostname: String?,
    val openPort: Int?
)

/**
 * Local /24 (or smaller) LAN host discovery — on-device only.
 */
object SubnetScanner {

    fun localSubnet(context: Context): SubnetInfo? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return fallbackFromWifi(context)
        val props = cm.getLinkProperties(network) ?: return fallbackFromWifi(context)
        return fromLinkProperties(props) ?: fallbackFromWifi(context)
    }

    private fun fromLinkProperties(props: LinkProperties): SubnetInfo? {
        val link = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val address = link.address as Inet4Address
        val prefix = link.prefixLength.coerceIn(16, 30)
        val ipInt = ipv4ToInt(address)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = ipInt and mask
        val hostBits = 32 - prefix
        val hosts = ((1 shl hostBits) - 2).coerceAtLeast(0)
        return SubnetInfo(
            localIp = address.hostAddress ?: return null,
            prefixLength = prefix,
            networkBase = intToIpv4(network),
            hostCount = min(hosts, 254)
        )
    }

    @Suppress("DEPRECATION")
    private fun fallbackFromWifi(context: Context): SubnetInfo? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifi.connectionInfo ?: return null
        val ip = info.ipAddress
        if (ip == 0) return null
        // WifiManager returns little-endian int on many devices.
        val a = ip and 0xff
        val b = ip shr 8 and 0xff
        val c = ip shr 16 and 0xff
        val d = ip shr 24 and 0xff
        val local = "$a.$b.$c.$d"
        val network = "$a.$b.$c.0"
        return SubnetInfo(localIp = local, prefixLength = 24, networkBase = network, hostCount = 254)
    }

    suspend fun scan(
        subnet: SubnetInfo,
        onProgress: suspend (scanned: Int, total: Int, found: SubnetHost?) -> Unit
    ): List<SubnetHost> = withContext(Dispatchers.IO) {
        val baseInt = ipv4ToInt(InetAddress.getByName(subnet.networkBase) as Inet4Address)
        val usable = subnet.hostCount.coerceIn(1, 254)
        val results = mutableListOf<SubnetHost>()
        var scanned = 0
        // Scan in chunks to keep memory/CPU reasonable on phone.
        val chunk = 32
        var offset = 1
        while (offset <= usable) {
            val end = min(offset + chunk - 1, usable)
            val batch = coroutineScope {
                (offset..end).map { hostIndex ->
                    async {
                        val ipInt = baseInt + hostIndex
                        val ip = intToIpv4(ipInt)
                        if (ip == subnet.localIp) {
                            SubnetHost(ip, "this device", null)
                        } else {
                            probeHost(ip)
                        }
                    }
                }.awaitAll()
            }
            batch.forEach { host ->
                scanned += 1
                if (host != null) {
                    results += host
                    onProgress(scanned, usable, host)
                } else {
                    onProgress(scanned, usable, null)
                }
            }
            offset = end + 1
        }
        results.sortedBy { ipv4ToInt(InetAddress.getByName(it.ip) as Inet4Address) }
    }

    private fun probeHost(ip: String): SubnetHost? {
        val ports = intArrayOf(80, 443, 22, 8080, 554)
        var open: Int? = null
        for (port in ports) {
            if (tcpOpen(ip, port, 220)) {
                open = port
                break
            }
        }
        val reachable = open != null || runCatching {
            InetAddress.getByName(ip).isReachable(280)
        }.getOrDefault(false)
        if (!reachable) return null
        val name = runCatching {
            InetAddress.getByName(ip).canonicalHostName
                ?.takeIf { it != ip && it.isNotBlank() }
        }.getOrNull()
        return SubnetHost(ip = ip, hostname = name, openPort = open)
    }

    private fun tcpOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ipv4ToInt(address: Inet4Address): Int {
        val b = address.address
        return ((b[0].toInt() and 0xff) shl 24) or
            ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or
            (b[3].toInt() and 0xff)
    }

    private fun intToIpv4(value: Int): String =
        "${(value ushr 24) and 0xff}.${(value ushr 16) and 0xff}.${(value ushr 8) and 0xff}.${value and 0xff}"
}

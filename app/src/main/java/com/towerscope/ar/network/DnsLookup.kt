package com.towerscope.ar.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

data class DnsRecord(
    val type: String,
    val value: String
)

data class DnsLookupResult(
    val query: String,
    val records: List<DnsRecord>,
    val reverseName: String?,
    val elapsedMs: Double,
    val networkNote: String?
)

/**
 * On-device DNS / reverse lookup. Uses platform resolvers (no third-party DoH).
 */
object DnsLookup {

    suspend fun lookup(context: Context, raw: String): DnsLookupResult =
        withContext(Dispatchers.IO) {
            val query = clean(raw).ifBlank { "cloudflare.com" }
            val start = System.nanoTime()
            val records = LinkedHashSet<DnsRecord>()
            var reverse: String? = null

            val addrs = runCatching { InetAddress.getAllByName(query) }.getOrDefault(emptyArray())
            for (addr in addrs) {
                val host = addr.hostAddress ?: continue
                val type = when (addr) {
                    is Inet4Address -> "A"
                    is Inet6Address -> "AAAA"
                    else -> "ADDR"
                }
                records += DnsRecord(type, host)
                if (reverse == null) {
                    reverse = runCatching {
                        addr.canonicalHostName?.takeIf { it.isNotBlank() && it != host }
                    }.getOrNull()
                }
            }

            // If the query looks like an IP, also attempt reverse first-class.
            if (looksLikeIp(query)) {
                runCatching {
                    val addr = InetAddress.getByName(query)
                    val name = addr.canonicalHostName
                    if (!name.isNullOrBlank() && name != query) {
                        reverse = name
                        records += DnsRecord("PTR", name)
                    }
                }
            }

            val ms = (System.nanoTime() - start) / 1_000_000.0
            DnsLookupResult(
                query = query,
                records = records.toList(),
                reverseName = reverse,
                elapsedMs = ms,
                networkNote = networkNote(context)
            )
        }

    private fun clean(raw: String): String =
        raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(' ')
            .trim()

    private fun looksLikeIp(value: String): Boolean =
        value.contains(':') || value.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))

    private fun networkNote(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "No active network"
        return buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi‑Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (Build.VERSION.SDK_INT >= 29 &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            ) {
                add("unmetered")
            }
        }.joinToString(" · ").ifBlank { null }
    }

    fun formatMs(ms: Double): String =
        if (!ms.isFinite()) "—" else String.format(Locale.US, "%.0f ms", ms)
}

package com.towerscope.ar.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class WakeOnLanResult(
    val macAddress: String,
    val broadcastIp: String,
    val port: Int,
    val success: Boolean,
    val error: String? = null
)

object WakeOnLan {

  private val MAC_REGEX = Regex("""^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$""")

    suspend fun send(
        macAddress: String,
        broadcastIp: String = "255.255.255.255",
        port: Int = 9
    ): WakeOnLanResult = withContext(Dispatchers.IO) {
        val mac = normalizeMac(macAddress)
        if (mac == null) {
            return@withContext WakeOnLanResult(
                macAddress = macAddress,
                broadcastIp = broadcastIp,
                port = port,
                success = false,
                error = "Use MAC format AA:BB:CC:DD:EE:FF"
            )
        }
        if (port !in 1..65535) {
            return@withContext WakeOnLanResult(
                macAddress = macAddress,
                broadcastIp = broadcastIp,
                port = port,
                success = false,
                error = "Port must be 1–65535"
            )
        }
        runCatching {
            val packet = buildMagicPacket(mac)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val address = InetAddress.getByName(broadcastIp)
                val datagram = DatagramPacket(packet, packet.size, address, port)
                socket.send(datagram)
            }
            WakeOnLanResult(macAddress, broadcastIp, port, success = true)
        }.getOrElse { e ->
            WakeOnLanResult(
                macAddress = macAddress,
                broadcastIp = broadcastIp,
                port = port,
                success = false,
                error = e.message ?: "Send failed"
            )
        }
    }

    /** Builds the 102-byte WoL magic packet. Exposed for unit tests. */
    internal fun buildMagicPacket(macBytes: ByteArray): ByteArray {
        require(macBytes.size == 6)
        val packet = ByteArray(102)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        var offset = 6
        repeat(16) {
            macBytes.copyInto(packet, offset)
            offset += 6
        }
        return packet
    }

    internal fun normalizeMac(raw: String): ByteArray? {
        val cleaned = raw.trim()
        if (!MAC_REGEX.matches(cleaned)) return null
        val parts = cleaned.split(':', '-')
        return ByteArray(6) { idx ->
            parts[idx].toInt(16).toByte()
        }
    }
}

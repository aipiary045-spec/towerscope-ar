package com.towerscope.ar.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Minimal SNMP v2c client (GET / GET-NEXT) for reading router interface tables.
 */
internal object SnmpClient {

    private const val SNMP_PORT = 161
    private const val TIMEOUT_MS = 2_500
    private const val MAX_RESPONSE = 65_536

    fun get(host: String, community: String, oid: String, snmpVersion: Int = 1): SnmpVarbind? =
        runCatching {
            send(host, community, pduGet(oid), snmpVersion, expectGetResponse = true).firstOrNull()
        }.getOrNull()

    fun walk(host: String, community: String, baseOid: String, snmpVersion: Int = 1): List<SnmpVarbind> {
        val results = mutableListOf<SnmpVarbind>()
        var currentOid = baseOid
        repeat(256) {
            val next = getNext(host, community, currentOid, snmpVersion) ?: return results
            if (!next.oid.startsWith("$baseOid.") && next.oid != baseOid) return results
            if (results.any { it.oid == next.oid }) return results
            results += next
            currentOid = next.oid
        }
        return results
    }

    fun probe(host: String, community: String): Boolean = resolveVersion(host, community) != null

    /** Returns 1 for SNMP v2c or 0 for SNMP v1 when the router responds. */
    fun resolveVersion(host: String, community: String): Int? =
        runCatching {
            if (get(host, community, "1.3.6.1.2.1.1.1.0", snmpVersion = 1) != null) 1
            else if (get(host, community, "1.3.6.1.2.1.1.1.0", snmpVersion = 0) != null) 0
            else null
        }.getOrNull()

    internal fun decodeResponse(bytes: ByteArray): List<SnmpVarbind> =
        decodeMessage(bytes, expectGetResponse = true)

    internal fun decodeSnmpValue(bytes: ByteArray): SnmpValue =
        BerDecoder(bytes).readValue()

    private fun getNext(host: String, community: String, oid: String, snmpVersion: Int = 1): SnmpVarbind? =
        runCatching {
            send(host, community, pduGetNext(oid), snmpVersion, expectGetResponse = true).firstOrNull()
        }.getOrNull()

    private fun send(
        host: String,
        community: String,
        pdu: ByteArray,
        snmpVersion: Int = 1,
        expectGetResponse: Boolean
    ): List<SnmpVarbind> {
        val message = encodeMessage(community, pdu, snmpVersion)
        val socket = DatagramSocket()
        socket.soTimeout = TIMEOUT_MS
        return try {
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(message, message.size, address, SNMP_PORT))
            val buffer = ByteArray(MAX_RESPONSE)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            decodeMessage(packet.data.copyOf(packet.length), expectGetResponse)
        } finally {
            socket.close()
        }
    }

    private fun encodeMessage(community: String, pdu: ByteArray, snmpVersion: Int): ByteArray {
        val version = encodeInteger(snmpVersion) // 0 = v1, 1 = v2c
        val communityBytes = encodeOctetString(community.toByteArray(Charsets.UTF_8))
        val content = version + communityBytes + pdu
        return encodeSequence(content)
    }

    private fun pduGet(oid: String): ByteArray {
        val bindings = encodeSequence(encodeVarbind(oid, encodeNull()))
        val body = encodeInteger(1) + encodeInteger(0) + encodeInteger(0) + bindings
        return encodeTagged(0xA0, encodeSequence(body))
    }

    private fun pduGetNext(oid: String): ByteArray {
        val bindings = encodeSequence(encodeVarbind(oid, encodeNull()))
        val body = encodeInteger(1) + encodeInteger(0) + encodeInteger(0) + bindings
        return encodeTagged(0xA1, encodeSequence(body))
    }

    private fun encodeVarbind(oid: String, value: ByteArray): ByteArray =
        encodeSequence(encodeOid(oid) + value)

    private fun decodeMessage(bytes: ByteArray, expectGetResponse: Boolean): List<SnmpVarbind> {
        val message = BerDecoder(bytes).readSequence()
        val decoder = BerDecoder(message)
        decoder.readInteger() // version
        decoder.readOctetString() // community
        val pduTag = decoder.readTag()
        // v2c GetResponse = 0xA2; v1 GetResponse = 0xA0 (same tag as v1 GetRequest)
        if (expectGetResponse && pduTag != 0xA2 && pduTag != 0xA0) {
            throw IllegalStateException("Unexpected SNMP PDU 0x${pduTag.toString(16)}")
        }
        val pdu = decoder.readTaggedContent()
        val pduDecoder = BerDecoder(pdu)
        pduDecoder.readInteger() // request id
        val errorStatus = pduDecoder.readInteger()
        val errorIndex = pduDecoder.readInteger()
        if (errorStatus != 0) {
            throw IllegalStateException("SNMP error $errorStatus at index $errorIndex")
        }
        val bindings = pduDecoder.readSequence()
        val results = mutableListOf<SnmpVarbind>()
        val bindingDecoder = BerDecoder(bindings)
        while (bindingDecoder.hasRemaining()) {
            val vb = bindingDecoder.readSequence()
            val vbDecoder = BerDecoder(vb)
            val oid = vbDecoder.readOid()
            val value = vbDecoder.readValue()
            results += SnmpVarbind(oid, value)
        }
        return results
    }

    private fun encodeSequence(content: ByteArray): ByteArray =
        encodeTagged(0x30, content)

    private fun encodeTagged(tag: Int, content: ByteArray): ByteArray {
        val length = encodeLength(content.size)
        return byteArrayOf(tag.toByte()) + length + content
    }

    private fun encodeLength(length: Int): ByteArray {
        return if (length < 0x80) {
            byteArrayOf(length.toByte())
        } else {
            val bytes = ByteBuffer.allocate(4).putInt(length).array()
            val significant = bytes.dropWhile { it == 0.toByte() }.let {
                if (it.isEmpty()) byteArrayOf() else it.toByteArray()
            }
            byteArrayOf((0x80 or significant.size).toByte()) + significant
        }
    }

    private fun encodeInteger(value: Int): ByteArray {
        val content = ByteBuffer.allocate(4).putInt(value).array()
        val trimmed = content.dropWhile { it == 0.toByte() }.let {
            if (it.isEmpty()) byteArrayOf(0) else it.toByteArray()
        }
        return encodeTagged(0x02, trimmed)
    }

    private fun encodeOctetString(bytes: ByteArray): ByteArray =
        encodeTagged(0x04, bytes)

    private fun encodeNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun encodeOid(oid: String): ByteArray {
        val parts = oid.split('.').mapNotNull { it.toIntOrNull() }
        require(parts.size >= 2) { "Invalid OID: $oid" }
        val encoded = mutableListOf<Byte>()
        encoded += ((parts[0] * 40) + parts[1]).toByte()
        for (index in 2 until parts.size) {
            var value = parts[index]
            val stack = mutableListOf<Int>()
            do {
                stack.add(0, value and 0x7F)
                value = value ushr 7
            } while (value > 0)
            stack.forEachIndexed { i, segment ->
                encoded += (segment or if (i < stack.lastIndex) 0x80 else 0).toByte()
            }
        }
        return encodeTagged(0x06, encoded.toByteArray())
    }

    private class BerDecoder(private val bytes: ByteArray) {
        private var offset = 0

        fun hasRemaining(): Boolean = offset < bytes.size

        fun readSequence(): ByteArray {
            val tag = readTag()
            require(tag == 0x30) { "Expected SEQUENCE" }
            return readTaggedContent()
        }

        fun readTaggedContent(): ByteArray {
            val length = readLength()
            return readBytes(length)
        }

        fun readInteger(): Int {
            val tag = readTag()
            require(tag == 0x02) { "Expected INTEGER" }
            val length = readLength()
            return readIntegerBytes(readBytes(length))
        }

        private fun readIntegerBytes(valueBytes: ByteArray): Int {
            var value = 0
            valueBytes.forEach { value = (value shl 8) or (it.toInt() and 0xFF) }
            if (valueBytes.isNotEmpty() && valueBytes[0].toInt() and 0x80 != 0) {
                value -= 1 shl (valueBytes.size * 8)
            }
            return value
        }

        fun readOctetString(): ByteArray {
            val tag = readTag()
            require(tag == 0x04) { "Expected OCTET STRING" }
            return readBytes(readLength())
        }

        fun readOid(): String {
            val tag = readTag()
            require(tag == 0x06) { "Expected OID" }
            val content = readBytes(readLength())
            if (content.isEmpty()) return ""
            val first = content[0].toInt() and 0xFF
            val parts = mutableListOf((first / 40).toString(), (first % 40).toString())
            var value = 0
            for (index in 1 until content.size) {
                val byte = content[index].toInt() and 0xFF
                value = (value shl 7) or (byte and 0x7F)
                if (byte and 0x80 == 0) {
                    parts += value.toString()
                    value = 0
                }
            }
            return parts.joinToString(".")
        }

        fun readValue(): SnmpValue {
            if (!hasRemaining()) return SnmpValue.Null
            val tag = peekTag()
            return when (tag) {
                0x02 -> SnmpValue.Integer(readInteger())
                0x04 -> SnmpValue.Octets(readOctetString())
                0x05 -> {
                    readTag(); readLength(); SnmpValue.Null
                }
                0x06 -> SnmpValue.ObjectId(readOid())
                0x41, 0x42, 0x43, 0x46 -> {
                    readTag()
                    val length = readLength()
                    SnmpValue.Gauge(readIntegerBytes(readBytes(length)).toLong())
                }
                0x40, 0x44, 0x45 -> {
                    readTag()
                    SnmpValue.Unsupported(readBytes(readLength()))
                }
                else -> {
                    readTag()
                    SnmpValue.Unsupported(readBytes(readLength()))
                }
            }
        }

        fun readTag(): Int {
            val tag = bytes[offset++].toInt() and 0xFF
            return tag
        }

        private fun peekTag(): Int = bytes[offset].toInt() and 0xFF

        private fun readLength(): Int {
            val first = readTag()
            return if (first and 0x80 == 0) {
                first
            } else {
                val count = first and 0x7F
                var length = 0
                repeat(count) {
                    length = (length shl 8) or (readTag())
                }
                length
            }
        }

        private fun readBytes(length: Int): ByteArray {
            val slice = bytes.copyOfRange(offset, offset + length)
            offset += length
            return slice
        }
    }
}

internal data class SnmpVarbind(
    val oid: String,
    val value: SnmpValue
)

internal sealed class SnmpValue {
    data class Integer(val value: Int) : SnmpValue()
    data class Gauge(val value: Long) : SnmpValue()
    data class Octets(val bytes: ByteArray) : SnmpValue()
    data class ObjectId(val oid: String) : SnmpValue()
    data class Unsupported(val bytes: ByteArray) : SnmpValue()
    data object Null : SnmpValue()
}

package com.towerscope.ar.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnmpClientTest {

    @Test
    fun get_onLoopback_returnsNullWithoutCrashing() {
        val result = SnmpClient.get("127.0.0.1", "public", "1.3.6.1.2.1.1.1.0")
        assertEquals(null, result)
    }

    @Test
    fun decodeSnmpValue_parsesGauge32IfHighSpeed() {
        val value = SnmpClient.decodeSnmpValue(hex("42 04 00 00 03 E8"))
        assertTrue(value is SnmpValue.Gauge)
        assertEquals(1000L, (value as SnmpValue.Gauge).value)
    }

    @Test
    fun decodeSnmpValue_parsesIntegerIfSpeed() {
        val value = SnmpClient.decodeSnmpValue(hex("02 04 05 F5 E1 00"))
        assertTrue(value is SnmpValue.Integer)
        assertEquals(100_000_000, (value as SnmpValue.Integer).value)
    }

    private fun hex(data: String): ByteArray =
        data.replace("\\s".toRegex(), "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
}

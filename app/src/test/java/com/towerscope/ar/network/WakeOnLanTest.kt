package com.towerscope.ar.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {

    @Test
    fun buildMagicPacket_hasCorrectSize() {
        val mac = WakeOnLan.normalizeMac("AA:BB:CC:DD:EE:FF")
        assertNotNull(mac)
        val packet = WakeOnLan.buildMagicPacket(mac!!)
        assertEquals(102, packet.size)
        assertEquals(0xFF.toByte(), packet[0])
    }

    @Test
    fun normalizeMac_rejectsInvalid() {
        assertNull(WakeOnLan.normalizeMac("not-a-mac"))
        assertNotNull(WakeOnLan.normalizeMac("AA-BB-CC-DD-EE-FF"))
    }
}

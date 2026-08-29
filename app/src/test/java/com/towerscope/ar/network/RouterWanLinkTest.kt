package com.towerscope.ar.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterWanLinkTest {

    @Test
    fun wanMatchScore_prefersWanNames() {
        assertTrue(RouterWanLink.wanMatchScore("ether1-wan") > RouterWanLink.wanMatchScore("ether2"))
        assertTrue(RouterWanLink.wanMatchScore("internet") > RouterWanLink.wanMatchScore("br-lan"))
    }

    @Test
    fun pickWanInterface_selectsBestMatch() {
        val selected = RouterWanLink.pickWanInterface(
            listOf(
                RouterInterface(1, "br-lan", null, 1000, 1),
                RouterInterface(2, "ether1-wan", null, 1000, 1),
                RouterInterface(3, "wifi", null, 300, 1)
            )
        )
        assertEquals("ether1-wan", selected?.name)
    }

    @Test
    fun formatLinkSpeed_usesHighSpeedMbps() {
        val label = RouterWanLink.formatLinkSpeed(
            RouterInterface(1, "wan", null, 1000, 1)
        )
        assertEquals("1.0 Gbps", label)
    }

    @Test
    fun formatLinkSpeed_usesLegacyBps() {
        val label = RouterWanLink.formatLinkSpeed(
            RouterInterface(1, "wan", 100_000_000, null, 1)
        )
        assertEquals("100 Mbps", label)
    }

    @Test
    fun formatOperStatus_mapsUpDown() {
        assertEquals("up", RouterWanLink.formatOperStatus(1))
        assertEquals("down", RouterWanLink.formatOperStatus(2))
    }
}

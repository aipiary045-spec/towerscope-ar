package com.towerscope.ar.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortScannerTest {

    @Test
    fun portsFor_common_includesHttps() {
        val ports = PortScanner.portsFor(PortScanPreset.COMMON)
        assertTrue(443 in ports)
        assertTrue(22 in ports)
    }

    @Test
    fun parseExtraPorts_supportsListAndRange() {
        val ports = PortScanner.parseExtraPorts("9100, 554, 8000-8002")
        assertTrue(9100 in ports)
        assertTrue(554 in ports)
        assertEquals(listOf(8000, 8001, 8002), ports.filter { it in 8000..8002 })
    }

    @Test
    fun resolveTargets_usesGatewayAndDiscoveredHostsWhenBlank() {
        val targets = PortScanner.resolveTargets(
            overrideHost = "",
            gatewayIpv4 = "192.168.88.1",
            subnet = SubnetInfo("192.168.88.50", 24, "192.168.88.0", 254),
            discoveredHosts = listOf("192.168.88.10", "192.168.88.20")
        )
        assertTrue("192.168.88.1" in targets)
        assertTrue("192.168.88.10" in targets)
        assertTrue("192.168.88.20" in targets)
    }

    @Test
    fun resolveTargets_honorsSingleHostOverride() {
        val targets = PortScanner.resolveTargets(
            overrideHost = "10.0.0.5",
            gatewayIpv4 = "192.168.1.1",
            subnet = null,
            discoveredHosts = listOf("192.168.1.2")
        )
        assertEquals(listOf("10.0.0.5"), targets)
    }

    @Test
    fun formatNetwork_listsHostsWithOpenPorts() {
        val text = PortScanner.formatNetwork(
            NetworkPortScanResult(
                targets = listOf("192.168.1.1", "192.168.1.10"),
                results = listOf(
                    PortScanResult("192.168.1.1", listOf(PortScanHit(443, 8.0, "HTTPS")), 11),
                    PortScanResult("192.168.1.10", emptyList(), 11)
                ),
                portsPerHost = 11
            )
        )
        assertTrue(text.contains("192.168.1.1"))
        assertTrue(text.contains("443"))
    }
}

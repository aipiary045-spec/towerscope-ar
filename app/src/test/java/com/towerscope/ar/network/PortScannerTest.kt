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
    fun format_listsOpenPorts() {
        val text = PortScanner.format(
            PortScanResult(
                host = "10.0.0.1",
                openPorts = listOf(PortScanHit(443, 12.0, "HTTPS")),
                portsScanned = 10
            )
        )
        assertTrue(text.contains("10.0.0.1"))
        assertTrue(text.contains("443"))
        assertTrue(text.contains("HTTPS"))
    }
}

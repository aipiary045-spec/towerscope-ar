package com.towerscope.ar.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSpeedTestTest {

    @Test
    fun defaultProbePorts_includesRouterServices() {
        val ports = LanSpeedTest.DEFAULT_PROBE_PORTS
        assertTrue(80 in ports)
        assertTrue(443 in ports)
        assertTrue(8291 in ports)
    }

    @Test
    fun isHttpPort_recognizesWebPorts() {
        assertTrue(LanSpeedTest.isHttpPort(80))
        assertTrue(LanSpeedTest.isHttpPort(443))
        assertTrue(LanSpeedTest.isHttpPort(8080))
        assertTrue(!LanSpeedTest.isHttpPort(8291))
    }

    @Test
    fun formatMbps_formatsValue() {
        assertEquals("12.5 Mbps", LanSpeedTest.formatMbps(12.5))
    }
}

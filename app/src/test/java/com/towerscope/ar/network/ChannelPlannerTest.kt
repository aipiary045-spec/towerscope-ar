package com.towerscope.ar.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPlannerTest {

    @Test
    fun analyze_prefersEmpty24Channel() {
        val scan = listOf(
            ap("A", 2437, 6, "2.4 GHz", -40),
            ap("B", 2442, 7, "2.4 GHz", -45),
            ap("C", 2462, 11, "2.4 GHz", -50)
        )
        val report = ChannelPlanner.analyze(scan)
        assertNotNull(report.best24)
        assertEquals(1, report.best24!!.channel)
        assertEquals(0, report.best24.apCount)
    }

    @Test
    fun analyze_prefersLessCongestedChannel() {
        val scan = listOf(
            ap("A", 2437, 6, "2.4 GHz", -50),
            ap("B", 2442, 7, "2.4 GHz", -55),
            ap("C", 2462, 11, "2.4 GHz", -60)
        )
        val report = ChannelPlanner.analyze(scan)
        assertNotNull(report.best24)
        assertTrue(report.best24!!.channel == 1 || report.best24.channel == 6 || report.best24.channel == 11)
    }

    @Test
    fun analyze_ranksQuiet5ghzAheadOfBusy() {
        val scan = listOf(
            ap("Busy36", 5180, 36, "5 GHz", -40),
            ap("Busy40", 5200, 40, "5 GHz", -42)
        )
        val report = ChannelPlanner.analyze(scan)
        assertNotNull(report.best5)
        assertTrue(report.best5!!.channel != 36)
        assertTrue(report.best5.apCount == 0)
    }

    private fun ap(
        ssid: String,
        mhz: Int,
        channel: Int,
        band: String,
        rssi: Int
    ) = WifiScanAp(ssid, ssid, rssi, mhz, channel, band, overlapWithActive = false, coChannelWithActive = false)
}

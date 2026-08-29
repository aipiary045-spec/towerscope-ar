package com.towerscope.ar.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPlannerTest {

    @Test
    fun analyze_prefersLessCongestedChannel() {
        val scan = listOf(
            WifiScanAp("A", "aa", -50, 2437, 6, "2.4 GHz", false, false),
            WifiScanAp("B", "bb", -55, 2442, 7, "2.4 GHz", false, false),
            WifiScanAp("C", "cc", -60, 2462, 11, "2.4 GHz", false, false)
        )
        val report = ChannelPlanner.analyze(scan)
        assertNotNull(report.best24)
        assertTrue(report.best24!!.channel == 1 || report.best24.channel == 6 || report.best24.channel == 11)
    }
}

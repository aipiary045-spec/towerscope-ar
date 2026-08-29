package com.towerscope.ar.network

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkSpeedClassifierTest {

    @Test
    fun labelFromMbps_mapsClassicEthernetSpeeds() {
        assertEquals("10 Mbps", LinkSpeedClassifier.labelFromMbps(10))
        assertEquals("100 Mbps", LinkSpeedClassifier.labelFromMbps(100))
        assertEquals("1000 Mbps", LinkSpeedClassifier.labelFromMbps(1000))
    }

    @Test
    fun labelFromBps_convertsBitsPerSecond() {
        assertEquals("100 Mbps", LinkSpeedClassifier.labelFromBps(100_000_000))
    }
}

package com.towerscope.ar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkEstimateTest {
    @Test
    fun fspl_increasesWithDistanceAndFrequency() {
        val short = LinkEstimate.freeSpacePathLossDb(1_000.0, 5.8)
        val long = LinkEstimate.freeSpacePathLossDb(10_000.0, 5.8)
        val higherBand = LinkEstimate.freeSpacePathLossDb(1_000.0, 24.0)
        assertTrue(long > short)
        assertTrue(higherBand > short)
        // ~1 km @ 5.8 GHz ≈ 107.7 dB
        assertEquals(107.7, short, 1.0)
    }
}

class UnitFormatTest {
    @Test
    fun metricAndImperialFormat() {
        assertTrue(UnitFormat.formatDistance(1609.344, DistanceUnitSystem.IMPERIAL).contains("mi"))
        assertTrue(UnitFormat.formatDistance(2500.0, DistanceUnitSystem.METRIC).contains("km"))
    }

    @Test
    fun dmsContainsHemisphere() {
        val dms = UnitFormat.formatCoordinates(36.15, -95.99, CoordinateFormat.DMS)
        assertTrue(dms.contains("N"))
        assertTrue(dms.contains("W"))
    }
}

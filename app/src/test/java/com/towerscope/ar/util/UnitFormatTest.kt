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
        assertEquals(107.7, short, 1.0)
    }

    @Test
    fun receiveLevel_usesLinkBudgetAndWorsensWhenBlocked() {
        val clear = LinkEstimate.estimatedReceiveLevelDbm(
            distanceMeters = 1_000.0,
            frequencyGhz = 5.8,
            txPowerDbm = 26.0,
            apGainDbi = 20.0,
            cpeGainDbi = 20.0,
            geometricClearanceMeters = 10.0,
            fresnelClearanceMeters = 5.0
        )
        val blocked = LinkEstimate.estimatedReceiveLevelDbm(
            distanceMeters = 1_000.0,
            frequencyGhz = 5.8,
            txPowerDbm = 26.0,
            apGainDbi = 20.0,
            cpeGainDbi = 20.0,
            geometricClearanceMeters = -5.0,
            fresnelClearanceMeters = -5.0
        )
        // 26 + 20 + 20 - ~107.7 ≈ -41.7 dBm clear path
        assertEquals(-41.7, clear, 1.5)
        assertTrue(blocked < clear - 20.0)
        assertTrue(LinkEstimate.formatReceiveLevel(clear).contains("dBm"))
        assertTrue(LinkEstimate.formatReceiveLevel(clear, 1_609.0).contains("dBm"))
    }

    @Test
    fun obstruction_grazingDemNoiseIsNotADeadPath() {
        val graze = LinkEstimate.obstructionLossDb(-0.6, -0.6)
        val deep = LinkEstimate.obstructionLossDb(-5.0, -5.0)
        assertTrue(graze < 9.0)
        assertTrue(deep >= 20.0)
    }

    @Test
    fun receiveLevel_twoMile5ghzDishIsUsableWhenClear() {
        val dbm = LinkEstimate.estimatedReceiveLevelDbm(
            distanceMeters = 3_218.0,
            frequencyGhz = 5.8,
            txPowerDbm = 20.0,
            apGainDbi = 20.0,
            cpeGainDbi = 20.0,
            geometricClearanceMeters = 8.0,
            fresnelClearanceMeters = 4.0
        )
        assertEquals(-58.0, dbm, 3.0)
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

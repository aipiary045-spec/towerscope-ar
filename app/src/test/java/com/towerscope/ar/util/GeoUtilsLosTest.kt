package com.towerscope.ar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsLosTest {

    @Test
    fun sampleGeodesicReturnsRequestedCountInclusive() {
        val points = GeoUtils.sampleGeodesic(30.0, -97.0, 31.0, -96.0, 50)
        assertEquals(50, points.size)
        assertEquals(30.0, points.first().latitude, 1e-6)
        assertEquals(-97.0, points.first().longitude, 1e-6)
        assertEquals(31.0, points.last().latitude, 1e-6)
        assertEquals(-96.0, points.last().longitude, 1e-6)
    }

    @Test
    fun midpointIsRoughlyHalfway() {
        val mid = GeoUtils.intermediatePoint(0.0, 0.0, 0.0, 10.0, 0.5)
        assertEquals(0.0, mid.latitude, 0.05)
        assertEquals(5.0, mid.longitude, 0.05)
    }

    @Test
    fun curvatureDropGrowsWithDistanceSquared() {
        val near = GeoUtils.earthCurvatureDropMeters(1_000.0)
        val far = GeoUtils.earthCurvatureDropMeters(10_000.0)
        assertTrue(near < 0.1)
        assertEquals(near * 100.0, far, 0.01)
    }
}

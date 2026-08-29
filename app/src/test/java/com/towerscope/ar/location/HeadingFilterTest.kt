package com.towerscope.ar.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingFilterTest {

    @Test
    fun smooth_movesTowardRawHeading() {
        val result = HeadingFilter.smooth(350.0, 10.0, 0.5)
        assertTrue(result in 0.0..20.0)
    }

    @Test
    fun smooth_wrapsAcrossNorth() {
        val result = HeadingFilter.smooth(359.0, 1.0, 0.55)
        assertTrue(result >= 359.9 || result <= 1.5)
    }

    @Test
    fun circularMean_averagesAcrossNorth() {
        val mean = HeadingFilter.circularMean(listOf(350.0, 10.0))
        assertTrue(mean!! >= 359.0 || mean <= 1.0)
    }
}

package com.towerscope.ar.location

import org.junit.Assert.assertEquals
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
    fun blend_pullsTowardOtherHeading() {
        val blended = HeadingFilter.blend(10.0, 40.0, 0.5)
        assertTrue(blended > 10.0 && blended < 40.0)
    }

    @Test
    fun smooth_wrapsAcrossNorth() {
        val result = HeadingFilter.smooth(359.0, 1.0, 1.0)
        assertEquals(1.0, result, 0.01)
    }

    @Test
    fun alphaForMotion_increasesWhenStill() {
        val moving = HeadingFilter.alphaForMotion(0.24, 60.0)
        val still = HeadingFilter.alphaForMotion(0.24, 2.0)
        assertTrue(still > moving)
    }

    @Test
    fun circularMean_averagesAcrossNorth() {
        val mean = HeadingFilter.circularMean(listOf(350.0, 10.0))
        assertEquals(0.0, mean!!, 1.0)
    }

    @Test
    fun isTilted_detectsLargePitch() {
        assertTrue(HeadingFilter.isTilted(35.0, 0.0))
        assertFalse(HeadingFilter.isTilted(10.0, 10.0))
    }
}

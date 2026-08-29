package com.towerscope.ar.location

import org.junit.Assert.assertEquals
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
}

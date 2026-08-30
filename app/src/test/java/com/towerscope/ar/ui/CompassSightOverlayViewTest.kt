package com.towerscope.ar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassSightOverlayViewTest {

    @Test
    fun bearingToScreenFraction_mapsCenterAndEdges() {
        assertEquals(0f, CompassSightOverlayView.bearingToScreenFraction(0.0, 60f)!!, 0.001f)
        assertEquals(1f, CompassSightOverlayView.bearingToScreenFraction(30.0, 60f)!!, 0.001f)
        assertEquals(-1f, CompassSightOverlayView.bearingToScreenFraction(-30.0, 60f)!!, 0.001f)
    }

    @Test
    fun bearingToScreenFraction_returnsNullOutsideFov() {
        assertNull(CompassSightOverlayView.bearingToScreenFraction(40.0, 60f))
    }

    @Test
    fun headingSmoothingAlpha_isLowerWhenStill() {
        assertTrue(
            CompassSightOverlayView.headingSmoothingAlpha(2.0) <
                CompassSightOverlayView.headingSmoothingAlpha(50.0)
        )
    }
}

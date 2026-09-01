package com.towerscope.ar.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHeadingClientTest {

    @Test
    fun isAimTilted_rejectsFlatPhone() {
        val flat = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        assertTrue(CompassHeadingMath.isAimTilted(flat))
    }

    @Test
    fun isAimTilted_acceptsUprightAim() {
        val upright = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
        assertFalse(CompassHeadingMath.isAimTilted(upright))
    }
}

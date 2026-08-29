package com.towerscope.ar.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHeadingClientTest {

    @Test
    fun isAimTilted_rejectsFlatPhone() {
        // Device Y points world-up — phone flat on a table.
        val flat = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, 0f,
            0f, 1f, 0f
        )
        assertTrue(DeviceHeadingClient.isAimTilted(flat))
    }

    @Test
    fun isAimTilted_acceptsUprightAim() {
        // Device Y points north along the horizon.
        val upright = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        assertFalse(DeviceHeadingClient.isAimTilted(upright))
    }
}

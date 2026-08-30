package com.towerscope.ar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassHeadingMathTest {

    @Test
    fun magneticHeading_topEdgeNorth_whenPitchedForward() {
        val matrix = rotationMatrix(
            deviceXWorld = Triple(1.0, 0.0, 0.0),
            deviceYWorld = Triple(0.0, 1.0, 0.0),
            deviceZWorld = Triple(0.0, 0.0, 1.0)
        )
        assertEquals(
            0.0,
            CompassHeadingMath.magneticHeadingDegrees(matrix, pitchDegrees = 0.0),
            0.5
        )
    }

    @Test
    fun magneticHeading_topEdgeEast_whenPitchedForward() {
        val matrix = rotationMatrix(
            deviceXWorld = Triple(0.0, 1.0, 0.0),
            deviceYWorld = Triple(1.0, 0.0, 0.0),
            deviceZWorld = Triple(0.0, 0.0, 1.0)
        )
        assertEquals(
            90.0,
            CompassHeadingMath.magneticHeadingDegrees(matrix, pitchDegrees = 0.0),
            0.5
        )
    }

    @Test
    fun magneticHeading_bodyFacingNorth_whenUpright() {
        val matrix = rotationMatrix(
            deviceXWorld = Triple(1.0, 0.0, 0.0),
            deviceYWorld = Triple(0.0, 0.0, 1.0),
            deviceZWorld = Triple(0.0, -1.0, 0.0)
        )
        assertEquals(
            0.0,
            CompassHeadingMath.magneticHeadingDegrees(matrix, pitchDegrees = -80.0),
            0.5
        )
    }

    @Test
    fun magneticHeading_bodyFacingEast_whenUpright() {
        val matrix = rotationMatrix(
            deviceXWorld = Triple(0.0, 1.0, 0.0),
            deviceYWorld = Triple(0.0, 0.0, 1.0),
            deviceZWorld = Triple(-1.0, 0.0, 0.0)
        )
        assertEquals(
            90.0,
            CompassHeadingMath.magneticHeadingDegrees(matrix, pitchDegrees = -80.0),
            0.5
        )
    }

    @Test
    fun isAimTilted_rejectsFlatPhone() {
        val flat = rotationMatrix(
            deviceXWorld = Triple(1.0, 0.0, 0.0),
            deviceYWorld = Triple(0.0, 1.0, 0.0),
            deviceZWorld = Triple(0.0, 0.0, 1.0)
        )
        assertTrue(CompassHeadingMath.isAimTilted(flat))
    }

    @Test
    fun isAimTilted_acceptsUprightAim() {
        val upright = rotationMatrix(
            deviceXWorld = Triple(1.0, 0.0, 0.0),
            deviceYWorld = Triple(0.0, 0.0, 1.0),
            deviceZWorld = Triple(0.0, -1.0, 0.0)
        )
        assertFalse(CompassHeadingMath.isAimTilted(upright))
    }

    private fun rotationMatrix(
        deviceXWorld: Triple<Double, Double, Double>,
        deviceYWorld: Triple<Double, Double, Double>,
        deviceZWorld: Triple<Double, Double, Double>
    ): FloatArray = floatArrayOf(
        deviceXWorld.first.toFloat(),
        deviceYWorld.first.toFloat(),
        deviceZWorld.first.toFloat(),
        deviceXWorld.second.toFloat(),
        deviceYWorld.second.toFloat(),
        deviceZWorld.second.toFloat(),
        deviceXWorld.third.toFloat(),
        deviceYWorld.third.toFloat(),
        deviceZWorld.third.toFloat()
    )
}

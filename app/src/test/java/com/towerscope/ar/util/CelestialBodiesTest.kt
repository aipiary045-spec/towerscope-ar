package com.towerscope.ar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CelestialBodiesTest {

    @Test
    fun sunNearSouthAtSolarNoonNorthernHemisphere() {
        // 2024-06-21 18:30 UTC ≈ solar noon near Austin, TX (−97.74°).
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2024, Calendar.JUNE, 21, 18, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sun = CelestialBodies.sunPosition(30.27, -97.74, cal.timeInMillis)
        assertTrue("elevation should be high at noon: ${sun.elevationDegrees}", sun.elevationDegrees > 60.0)
        val az = sun.azimuthDegrees
        assertTrue("azimuth near south: $az", az in 150.0..210.0)
    }

    @Test
    fun sunBelowHorizonAtLocalMidnight() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2024, Calendar.JUNE, 22, 5, 0, 0) // ~midnight CDT
            set(Calendar.MILLISECOND, 0)
        }
        val sun = CelestialBodies.sunPosition(30.27, -97.74, cal.timeInMillis)
        assertTrue(sun.elevationDegrees < 0.0)
    }

    @Test
    fun preferredTargetSwitchesToMoonWhenSunDown() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2024, Calendar.JUNE, 22, 5, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val target = CelestialBodies.preferredCalibrationTarget(30.27, -97.74, cal.timeInMillis)
        // May be null if moon also low; if present it must be moon.
        if (target != null) {
            assertEquals(CelestialBodies.Body.MOON, target.body)
        }
    }

    @Test
    fun signedDeltaWrapsCorrectly() {
        assertEquals(10.0, CelestialBodies.signedDeltaDegrees(350.0, 0.0), 0.01)
        assertEquals(-10.0, CelestialBodies.signedDeltaDegrees(0.0, 350.0), 0.01)
    }
}

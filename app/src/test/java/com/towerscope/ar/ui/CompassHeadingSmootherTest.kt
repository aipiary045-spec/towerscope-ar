package com.towerscope.ar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassHeadingSmootherTest {

    @Test
    fun stepToward_movesTowardTarget() {
        val next = CompassHeadingSmoother.stepToward(
            current = 0.0,
            target = 90.0,
            rotationRateDps = 30.0
        )
        assertTrue(next > 0.0)
        assertTrue(next < 90.0)
    }

    @Test
    fun stepToward_deadbandWhenStill() {
        val next = CompassHeadingSmoother.stepToward(
            current = 10.0,
            target = 10.2,
            rotationRateDps = 1.0
        )
        assertEquals(10.0, next, 0.001)
    }

    @Test
    fun alphaIncreasesWithRotationRate() {
        assertTrue(
            CompassHeadingSmoother.alphaForRotationRate(50.0) >
                CompassHeadingSmoother.alphaForRotationRate(5.0)
        )
    }
}

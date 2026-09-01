package com.towerscope.ar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingSourceArbiterTest {

    @Test
    fun usesFusedWhenSourcesAgree() {
        val arbiter = HeadingSourceArbiter()
        val choice = arbiter.choose(
            fusedHeadingDegrees = 100.0,
            magnetometerHeadingDegrees = 102.0,
            fusedHasMagneticReference = true
        )
        assertEquals(HeadingSourceArbiter.Source.FUSED, choice?.source)
        assertEquals(100.0, choice?.headingDegrees!!, 0.001)
    }

    @Test
    fun switchesToMagnetometerOnLargeDivergence() {
        val arbiter = HeadingSourceArbiter()
        val choice = arbiter.choose(
            fusedHeadingDegrees = 100.0,
            magnetometerHeadingDegrees = 250.0,
            fusedHasMagneticReference = true
        )
        assertEquals(HeadingSourceArbiter.Source.MAGNETOMETER, choice?.source)
        assertEquals(250.0, choice?.headingDegrees!!, 0.001)
    }

    @Test
    fun returnsToFusedAfterSourcesReconverge() {
        val arbiter = HeadingSourceArbiter()
        arbiter.choose(100.0, 250.0, fusedHasMagneticReference = true)
        var last: HeadingSourceArbiter.Choice? = null
        repeat(500) {
            last = arbiter.choose(100.0, 101.0, fusedHasMagneticReference = true)
        }
        assertEquals(HeadingSourceArbiter.Source.FUSED, last?.source)
    }

    @Test
    fun prefersMagnetometerWhenFusedLacksMagneticReference() {
        val arbiter = HeadingSourceArbiter()
        val choice = arbiter.choose(
            fusedHeadingDegrees = 10.0,
            magnetometerHeadingDegrees = 200.0,
            fusedHasMagneticReference = false
        )
        assertEquals(HeadingSourceArbiter.Source.MAGNETOMETER, choice?.source)
        assertEquals(200.0, choice?.headingDegrees!!, 0.001)
    }

    @Test
    fun fallsBackToMagnetometerWhenFusedMissing() {
        val arbiter = HeadingSourceArbiter()
        val choice = arbiter.choose(
            fusedHeadingDegrees = null,
            magnetometerHeadingDegrees = 45.0,
            fusedHasMagneticReference = true
        )
        assertEquals(HeadingSourceArbiter.Source.MAGNETOMETER, choice?.source)
    }

    @Test
    fun returnsNullForGameRotationWithoutMagnetometer() {
        val arbiter = HeadingSourceArbiter()
        assertNull(
            arbiter.choose(
                fusedHeadingDegrees = 10.0,
                magnetometerHeadingDegrees = null,
                fusedHasMagneticReference = false
            )
        )
    }
}

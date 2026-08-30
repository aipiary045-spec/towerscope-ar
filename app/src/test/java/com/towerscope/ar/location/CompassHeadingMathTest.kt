package com.towerscope.ar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassHeadingMathTest {

    @Test
    fun magneticHeading_topEdgeNorth_isZero() {
        val matrix = rotationMatrixWithDeviceYWorld(0.0, 1.0, 0.0)
        assertEquals(0.0, CompassHeadingMath.magneticHeadingDegrees(matrix), 0.5)
    }

    @Test
    fun magneticHeading_topEdgeEast_isNinety() {
        val matrix = rotationMatrixWithDeviceYWorld(1.0, 0.0, 0.0)
        assertEquals(90.0, CompassHeadingMath.magneticHeadingDegrees(matrix), 0.5)
    }

    @Test
    fun isAimTilted_rejectsFlatPhone() {
        val flat = rotationMatrixWithDeviceYWorld(0.0, 0.0, 1.0)
        assertTrue(CompassHeadingMath.isAimTilted(flat))
    }

    @Test
    fun isAimTilted_acceptsUprightAim() {
        val upright = rotationMatrixWithDeviceYWorld(0.0, 1.0, 0.0)
        assertFalse(CompassHeadingMath.isAimTilted(upright))
    }

    /**
     * Build a row-major rotation matrix whose device +Y axis in world ENU is [yx, yy, yz].
     * Other columns are filled to form a right-handed basis (sufficient for heading tests).
     */
    private fun rotationMatrixWithDeviceYWorld(yx: Double, yy: Double, yz: Double): FloatArray {
        val y = doubleArrayOf(yx, yy, yz)
        val up = doubleArrayOf(0.0, 0.0, 1.0)
        var x = cross(up, y)
        if (length(x) < 1e-6) {
            x = doubleArrayOf(1.0, 0.0, 0.0)
        }
        normalize(x)
        val z = cross(y, x)
        normalize(z)
        normalize(y)
        return floatArrayOf(
            x[0].toFloat(), y[0].toFloat(), z[0].toFloat(),
            x[1].toFloat(), y[1].toFloat(), z[1].toFloat(),
            x[2].toFloat(), y[2].toFloat(), z[2].toFloat()
        )
    }

    private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray =
        doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )

    private fun length(v: DoubleArray): Double =
        kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun normalize(v: DoubleArray) {
        val len = length(v)
        if (len < 1e-9) return
        v[0] /= len
        v[1] /= len
        v[2] /= len
    }
}

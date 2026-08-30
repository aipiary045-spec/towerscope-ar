package com.towerscope.ar.location

import com.towerscope.ar.util.GeoUtils
import kotlin.math.atan2

/**
 * Heading math for portrait compass use: the **top edge of the phone** is forward.
 *
 * Android's [android.hardware.SensorManager.getOrientation] already reports azimuth for
 * device +Y on the un-remapped rotation-vector matrix. Remapping to X+Z switches the
 * reference to the screen normal (camera facing) and makes tower bearings wrong.
 */
object CompassHeadingMath {

    /**
     * Magnetic azimuth in degrees (0 = north, clockwise) for the horizontal projection
     * of device +Y (top of phone).
     */
    fun magneticHeadingDegrees(rotationMatrix: FloatArray): Double {
        require(rotationMatrix.size >= 9) { "rotation matrix must have 9 elements" }
        val azimuthRad = atan2(
            rotationMatrix[1].toDouble(),
            rotationMatrix[4].toDouble()
        )
        return GeoUtils.normalizeBearing(Math.toDegrees(azimuthRad))
    }

    /**
     * True when the phone top edge is not aimed near the horizon (flat on a table, etc.).
     * Uses device +Y world Z from the rotation matrix (same as getOrientation pitch).
     */
    fun isAimTilted(rotationMatrix: FloatArray): Boolean {
        require(rotationMatrix.size >= 9) { "rotation matrix must have 9 elements" }
        return kotlin.math.abs(rotationMatrix[7]) > 0.47f
    }
}

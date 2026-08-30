package com.towerscope.ar.location

import com.towerscope.ar.util.GeoUtils
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Heading math for portrait compass use.
 *
 * Field techs hold the phone in two different ways:
 * - **Upright** (screen toward face, rotating in place): body facing follows device −Z.
 * - **Pitched forward** (top edge toward the tower): aim follows device +Y.
 *
 * Using only +Y breaks when the phone is vertical (+Y points at the sky). Using only +Z/−Z
 * breaks when sighting along the top edge. Pick the axis with a stronger horizontal projection,
 * with pitch as a tie-breaker.
 */
object CompassHeadingMath {

    private const val MIN_HORIZONTAL_PROJECTION = 0.22
  /** Pitch above this (less vertical) prefers top-edge aiming. */
    private const val TOP_EDGE_PITCH_DEGREES = -45.0

    enum class ReferenceAxis {
        TOP_EDGE,
        BODY_FACING
    }

    /**
     * Magnetic azimuth in degrees (0 = north, clockwise).
     */
    fun magneticHeadingDegrees(rotationMatrix: FloatArray, pitchDegrees: Double): Double {
        require(rotationMatrix.size >= 9) { "rotation matrix must have 9 elements" }
        val topHoriz = horizontalMagnitude(rotationMatrix, ReferenceAxis.TOP_EDGE)
        val bodyHoriz = horizontalMagnitude(rotationMatrix, ReferenceAxis.BODY_FACING)

        val axis = when {
            topHoriz < MIN_HORIZONTAL_PROJECTION && bodyHoriz >= MIN_HORIZONTAL_PROJECTION ->
                ReferenceAxis.BODY_FACING
            bodyHoriz < MIN_HORIZONTAL_PROJECTION && topHoriz >= MIN_HORIZONTAL_PROJECTION ->
                ReferenceAxis.TOP_EDGE
            pitchDegrees > TOP_EDGE_PITCH_DEGREES -> ReferenceAxis.TOP_EDGE
            else -> ReferenceAxis.BODY_FACING
        }
        return headingForAxis(rotationMatrix, axis)
    }

    fun headingForAxis(rotationMatrix: FloatArray, axis: ReferenceAxis): Double {
        val (east, north) = when (axis) {
            ReferenceAxis.TOP_EDGE -> rotationMatrix[1].toDouble() to rotationMatrix[4].toDouble()
            ReferenceAxis.BODY_FACING ->
                (-rotationMatrix[2]).toDouble() to (-rotationMatrix[5]).toDouble()
        }
        return GeoUtils.normalizeBearing(Math.toDegrees(atan2(east, north)))
    }

    fun horizontalMagnitude(rotationMatrix: FloatArray, axis: ReferenceAxis): Double {
        val (east, north) = when (axis) {
            ReferenceAxis.TOP_EDGE -> rotationMatrix[1].toDouble() to rotationMatrix[4].toDouble()
            ReferenceAxis.BODY_FACING ->
                (-rotationMatrix[2]).toDouble() to (-rotationMatrix[5]).toDouble()
        }
        return hypot(east, north)
    }

    /**
     * True when neither aim axis is usable (flat on a table) or both lack horizontal projection.
     */
    fun isAimTilted(rotationMatrix: FloatArray): Boolean {
        require(rotationMatrix.size >= 9) { "rotation matrix must have 9 elements" }
        if (kotlin.math.abs(rotationMatrix[8]) > 0.75f) {
            return true
        }
        val topHoriz = horizontalMagnitude(rotationMatrix, ReferenceAxis.TOP_EDGE)
        val bodyHoriz = horizontalMagnitude(rotationMatrix, ReferenceAxis.BODY_FACING)
        return topHoriz < MIN_HORIZONTAL_PROJECTION && bodyHoriz < MIN_HORIZONTAL_PROJECTION
    }
}

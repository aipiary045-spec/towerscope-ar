package com.towerscope.ar.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.towerscope.ar.util.GeoUtils
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs

data class DeviceHeading(
    /** Degrees clockwise from true north when declination is available. */
    val degrees: Double,
    /** [SensorManager] accuracy: UNRELIABLE / LOW / MEDIUM / HIGH. */
    val sensorAccuracy: Int,
    val pitchDegrees: Double,
    val rollDegrees: Double,
    val tilted: Boolean,
    val rotationRateDps: Double,
    val magneticInterference: Boolean,
    val headingSource: HeadingSourceArbiter.Source
)

/**
 * Device heading for portrait compass use.
 *
 * Runs two independent heading sources and lets [HeadingSourceArbiter] pick:
 * - Fused rotation vector (smooth, but yaw reference can be stale/arbitrary on some devices)
 * - Raw accelerometer + magnetometer via [SensorManager.getRotationMatrix]
 *   (noisier, but always referenced to the real magnetic field)
 *
 * [CompassHeadingMath] extracts azimuth tilt-aware: body-facing (−Z) when the phone is
 * upright, top-edge (+Y) when pitched toward the target.
 */
class DeviceHeadingClient(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun headingUpdates(locationProvider: () -> UserLocation? = { null }): Flow<DeviceHeading> =
        callbackFlow {
            val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

            val hasMagneticPipeline = accelerometer != null && magnetometer != null
            if (rotation == null && !hasMagneticPipeline) {
                close()
                return@callbackFlow
            }

            val fusedHasMagneticReference = rotation?.type == Sensor.TYPE_ROTATION_VECTOR
            val rotationMatrix = FloatArray(9)
            val magneticMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            val gravity = FloatArray(3)
            val geomagnetic = FloatArray(3)
            var hasRotation = false
            var hasGravity = false
            var hasMagnetic = false
            var latestAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            var smoothedHeading: Double? = null
            var previousRawHeading: Double? = null
            var previousSampleNanos: Long? = null
            var magneticInterference = false
            val magneticMonitor = MagneticFieldMonitor()
            val arbiter = HeadingSourceArbiter()

            fun emitHeading(sampleNanos: Long) {
                val fusedHeading = if (hasRotation) {
                    CompassHeadingMath.magneticHeadingDegrees(rotationMatrix)
                } else {
                    null
                }
                val magneticHeading = if (
                    hasGravity && hasMagnetic &&
                    SensorManager.getRotationMatrix(magneticMatrix, null, gravity, geomagnetic)
                ) {
                    CompassHeadingMath.magneticHeadingDegrees(magneticMatrix)
                } else {
                    null
                }

                val choice = arbiter.choose(
                    fusedHeadingDegrees = fusedHeading,
                    magnetometerHeadingDegrees = magneticHeading,
                    fusedHasMagneticReference = fusedHasMagneticReference
                ) ?: return

                val shapeMatrix = if (hasRotation) rotationMatrix else magneticMatrix
                SensorManager.getOrientation(shapeMatrix, orientation)
                val pitchDegrees = Math.toDegrees(orientation[1].toDouble())
                val rollDegrees = Math.toDegrees(orientation[2].toDouble())
                val tilted = CompassHeadingMath.isAimTilted(shapeMatrix)

                var degrees = choice.headingDegrees

                val rotationRateDps = computeRotationRateDps(
                    previousRawHeading,
                    previousSampleNanos,
                    degrees,
                    sampleNanos
                )
                previousRawHeading = degrees
                previousSampleNanos = sampleNanos

                // Both sources are magnetic-referenced; correct to true north when possible.
                val location = locationProvider()
                if (location != null) {
                    val field = GeomagneticField(
                        location.latitude.toFloat(),
                        location.longitude.toFloat(),
                        (location.altitudeMeters ?: 0.0).toFloat(),
                        System.currentTimeMillis()
                    )
                    degrees = GeoUtils.normalizeBearing(degrees + field.declination)
                }

                if (latestAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    return
                }

                val alpha = HeadingFilter.alphaForAccuracy(latestAccuracy)
                smoothedHeading = HeadingFilter.smooth(smoothedHeading, degrees, alpha)

                trySend(
                    DeviceHeading(
                        degrees = smoothedHeading!!,
                        sensorAccuracy = latestAccuracy,
                        pitchDegrees = pitchDegrees,
                        rollDegrees = rollDegrees,
                        tilted = tilted,
                        rotationRateDps = rotationRateDps,
                        magneticInterference = magneticInterference,
                        headingSource = choice.source
                    )
                )
            }

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            if (!hasGravity) {
                                System.arraycopy(event.values, 0, gravity, 0, 3)
                                hasGravity = true
                            } else {
                                for (i in 0..2) {
                                    gravity[i] = 0.8f * gravity[i] + 0.2f * event.values[i]
                                }
                            }
                            if (rotation == null) emitHeading(event.timestamp)
                        }
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            magneticInterference = magneticMonitor.observe(
                                event.values[0],
                                event.values[1],
                                event.values[2]
                            )
                            System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                            hasMagnetic = true
                            if (rotation == null) emitHeading(event.timestamp)
                        }
                        Sensor.TYPE_ROTATION_VECTOR,
                        Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                            hasRotation = true
                            emitHeading(event.timestamp)
                        }
                        else -> return
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR ||
                        sensor?.type == Sensor.TYPE_MAGNETIC_FIELD
                    ) {
                        latestAccuracy = accuracy
                    }
                }
            }

            rotation?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            accelerometer?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            magnetometer?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            awaitClose { sensorManager.unregisterListener(listener) }
        }

    internal companion object {
        private fun computeRotationRateDps(
            previousHeading: Double?,
            previousNanos: Long?,
            heading: Double,
            sampleNanos: Long
        ): Double {
            val previous = previousHeading ?: return 0.0
            val prevNanos = previousNanos ?: return 0.0
            val deltaMs = (sampleNanos - prevNanos) / 1_000_000.0
            if (deltaMs <= 0.0) return 0.0
            val delta = GeoUtils.relativeBearingDegrees(previous, heading)
            return abs(delta) / (deltaMs / 1000.0)
        }
    }
}

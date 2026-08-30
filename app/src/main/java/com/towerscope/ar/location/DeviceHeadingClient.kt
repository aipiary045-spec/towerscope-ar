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
    val magneticInterference: Boolean
)

/**
 * Device heading for portrait compass use (top of phone = forward, screen toward user).
 *
 * Uses the rotation-vector matrix directly — [CompassHeadingMath] extracts azimuth from
 * device +Y. Do not remap to X+Z; that tracks the screen/camera axis instead of the
 * top edge and makes tower bearings wrong.
 */
class DeviceHeadingClient(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun headingUpdates(locationProvider: () -> UserLocation? = { null }): Flow<DeviceHeading> =
        callbackFlow {
            val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

            if (rotation == null) {
                close()
                return@callbackFlow
            }

            val usesGeomagneticNorth = rotation.type == Sensor.TYPE_ROTATION_VECTOR
            val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            var latestAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            var smoothedHeading: Double? = null
            var previousRawHeading: Double? = null
            var previousSampleNanos: Long? = null
            var magneticInterference = false
            val magneticMonitor = MagneticFieldMonitor()

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            magneticInterference = magneticMonitor.observe(
                                event.values[0],
                                event.values[1],
                                event.values[2]
                            )
                            return
                        }
                        Sensor.TYPE_ROTATION_VECTOR,
                        Sensor.TYPE_GAME_ROTATION_VECTOR -> Unit
                        else -> return
                    }

                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)

                    val pitchDegrees = Math.toDegrees(orientation[1].toDouble())
                    val rollDegrees = Math.toDegrees(orientation[2].toDouble())
                    val tilted = CompassHeadingMath.isAimTilted(rotationMatrix)

                    var degrees = CompassHeadingMath.magneticHeadingDegrees(rotationMatrix)

                    val sampleNanos = event.timestamp
                    val rotationRateDps = computeRotationRateDps(
                        previousRawHeading,
                        previousSampleNanos,
                        degrees,
                        sampleNanos
                    )
                    previousRawHeading = degrees
                    previousSampleNanos = sampleNanos

                    if (usesGeomagneticNorth) {
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
                            magneticInterference = magneticInterference
                        )
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR ||
                        sensor?.type == Sensor.TYPE_MAGNETIC_FIELD
                    ) {
                        latestAccuracy = accuracy
                    }
                }
            }

            sensorManager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME)
            magnetometer?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
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

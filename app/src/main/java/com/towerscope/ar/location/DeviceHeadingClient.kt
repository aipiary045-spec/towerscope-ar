package com.towerscope.ar.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.towerscope.ar.util.CelestialBodies
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
    /** Approximate heading change rate in degrees per second. */
    val rotationRateDps: Double,
    val magneticInterference: Boolean
)

/**
 * Device heading for portrait compass use (top of phone = forward).
 */
class DeviceHeadingClient(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun headingUpdates(locationProvider: () -> UserLocation? = { null }): Flow<DeviceHeading> =
        callbackFlow {
            val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotation == null) {
                close()
                return@callbackFlow
            }

            val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            val rotationMatrix = FloatArray(9)
            val remappedMatrix = FloatArray(9)
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
                        Sensor.TYPE_ROTATION_VECTOR -> Unit
                        else -> return
                    }

                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val remapped = SensorManager.remapCoordinateSystem(
                        rotationMatrix,
                        SensorManager.AXIS_X,
                        SensorManager.AXIS_Y,
                        remappedMatrix
                    )
                    val matrixForOrientation = if (remapped) remappedMatrix else rotationMatrix
                    SensorManager.getOrientation(matrixForOrientation, orientation)
                    val pitchDegrees = Math.toDegrees(orientation[1].toDouble())
                    val rollDegrees = Math.toDegrees(orientation[2].toDouble())
                    val tilted = HeadingFilter.isTilted(pitchDegrees, rollDegrees)

                    var degrees = Math.toDegrees(orientation[0].toDouble())
                    degrees = GeoUtils.normalizeBearing(degrees)

                    val sampleNanos = event.timestamp
                    val rotationRateDps = previousRawHeading?.let { previous ->
                        previousSampleNanos?.let { previousNanos ->
                            val deltaMs = (sampleNanos - previousNanos) / 1_000_000.0
                            if (deltaMs <= 0.0) {
                                0.0
                            } else {
                                val delta = CelestialBodies.signedDeltaDegrees(previous, degrees)
                                abs(delta) / (deltaMs / 1000.0)
                            }
                        }
                    } ?: 0.0
                    previousRawHeading = degrees
                    previousSampleNanos = sampleNanos

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

                    val speed = location?.speedMps
                    val gpsBearing = location?.bearingDegrees
                    if (
                        speed != null && speed >= GPS_BLEND_MIN_SPEED_MPS &&
                        gpsBearing != null && latestAccuracy < SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                    ) {
                        degrees = HeadingFilter.blend(degrees, gpsBearing.toDouble(), GPS_BLEND_WEIGHT)
                    }

                    if (latestAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                        return
                    }

                    val baseAlpha = HeadingFilter.alphaForAccuracy(latestAccuracy)
                    val alpha = HeadingFilter.alphaForMotion(baseAlpha, rotationRateDps)
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

    companion object {
        private const val GPS_BLEND_MIN_SPEED_MPS = 1.4f
        private const val GPS_BLEND_WEIGHT = 0.22
    }
}

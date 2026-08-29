package com.towerscope.ar.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.towerscope.ar.util.GeoUtils

data class DeviceHeading(
    /** Degrees clockwise from true north when declination is available. */
    val degrees: Double,
    /** [SensorManager] accuracy: UNRELIABLE / LOW / MEDIUM / HIGH. */
    val sensorAccuracy: Int
)

/**
 * Device heading for portrait compass use (top of phone = forward).
 *
 * Uses rotation vector with portrait remap (X + Y axes), magnetic declination,
 * light smoothing, and optional GPS course blending while walking.
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

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    // Portrait, screen toward user: top edge of phone = forward.
                    val remapped = SensorManager.remapCoordinateSystem(
                        rotationMatrix,
                        SensorManager.AXIS_X,
                        SensorManager.AXIS_Y,
                        remappedMatrix
                    )
                    val matrixForOrientation = if (remapped) remappedMatrix else rotationMatrix
                    SensorManager.getOrientation(matrixForOrientation, orientation)
                    var degrees = Math.toDegrees(orientation[0].toDouble())
                    degrees = GeoUtils.normalizeBearing(degrees)

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

                    val alpha = HeadingFilter.alphaForAccuracy(latestAccuracy)
                    smoothedHeading = HeadingFilter.smooth(smoothedHeading, degrees, alpha)

                    trySend(
                        DeviceHeading(
                            degrees = smoothedHeading!!,
                            sensorAccuracy = latestAccuracy
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

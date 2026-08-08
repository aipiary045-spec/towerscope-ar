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

data class DeviceHeading(
    /** Degrees clockwise from true north when declination is available. */
    val degrees: Double,
    /** [SensorManager] accuracy: UNRELIABLE / LOW / MEDIUM / HIGH. */
    val sensorAccuracy: Int
)

/**
 * Device heading in degrees clockwise from true north for portrait compass use.
 *
 * Remaps so azimuth follows the direction the **top of the phone** points when held
 * upright (screen toward user) — for radar display and compass aiming.
 *
 * Magnetic azimuth is corrected with [GeomagneticField] declination when using
 * [Sensor.TYPE_ROTATION_VECTOR]. [Sensor.TYPE_GAME_ROTATION_VECTOR] has no geomagnetic
 * reference, so declination is not applied there.
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
            val rotationMatrix = FloatArray(9)
            val remappedMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            var latestAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    // Portrait compass: top of phone = forward (screen toward user).
                    val remapped = SensorManager.remapCoordinateSystem(
                        rotationMatrix,
                        SensorManager.AXIS_X,
                        SensorManager.AXIS_Z,
                        remappedMatrix
                    )
                    val matrixForOrientation = if (remapped) remappedMatrix else rotationMatrix
                    SensorManager.getOrientation(matrixForOrientation, orientation)
                    var degrees = Math.toDegrees(orientation[0].toDouble())
                    degrees = (degrees + 360.0) % 360.0

                    if (usesGeomagneticNorth) {
                        val location = locationProvider()
                        if (location != null) {
                            val field = GeomagneticField(
                                location.latitude.toFloat(),
                                location.longitude.toFloat(),
                                (location.altitudeMeters ?: 0.0).toFloat(),
                                System.currentTimeMillis()
                            )
                            degrees = (degrees + field.declination + 360.0) % 360.0
                        }
                    }

                    trySend(DeviceHeading(degrees = degrees, sensorAccuracy = latestAccuracy))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    latestAccuracy = accuracy
                }
            }

            sensorManager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
            awaitClose { sensorManager.unregisterListener(listener) }
        }
}

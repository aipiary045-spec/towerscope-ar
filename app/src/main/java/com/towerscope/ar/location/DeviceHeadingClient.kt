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

/**
 * Device heading in degrees clockwise from true north, via rotation vector
 * (works while stationary — unlike GPS bearing). Magnetic azimuth is corrected
 * with [GeomagneticField] declination when a location is available.
 */
class DeviceHeadingClient(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun headingUpdates(locationProvider: () -> UserLocation? = { null }): Flow<Double> =
        callbackFlow {
            val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

            if (rotation == null) {
                close()
                return@callbackFlow
            }

            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    // azimuth: radians, -π..π, 0 = magnetic north
                    var degrees = Math.toDegrees(orientation[0].toDouble())
                    degrees = (degrees + 360.0) % 360.0

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

                    trySend(degrees)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            sensorManager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
            awaitClose { sensorManager.unregisterListener(listener) }
        }
}

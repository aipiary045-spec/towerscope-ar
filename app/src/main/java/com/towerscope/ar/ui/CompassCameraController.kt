package com.towerscope.ar.ui

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * Back-camera preview for compass sight mode. Reports horizontal FOV when available.
 */
class CompassCameraController(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val onHorizontalFovDegrees: (Float) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null

    fun start() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener(
            {
                val provider = future.get()
                cameraProvider = provider
                bind(provider)
            },
            ContextCompat.getMainExecutor(activity)
        )
    }

    fun stop() {
        cameraProvider?.unbindAll()
    }

    private fun bind(provider: ProcessCameraProvider) {
        provider.unbindAll()
        val preview = Preview.Builder()
            .setTargetResolution(Size(1280, 720))
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        val camera = provider.bindToLifecycle(
            activity,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview
        )

        val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
        val focalLengths = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        )
        val sensorSize = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
        )
        val fov = if (focalLengths != null && sensorSize != null && focalLengths.isNotEmpty()) {
            horizontalFovFromSensor(sensorSize.width, focalLengths[0])
        } else {
            CompassSightOverlayView.DEFAULT_HORIZONTAL_FOV_DEGREES
        }
        onHorizontalFovDegrees(fov)
    }

    companion object {
        fun horizontalFovFromSensor(sensorWidthMm: Float, focalLengthMm: Float): Float {
            if (sensorWidthMm <= 0f || focalLengthMm <= 0f) {
                return CompassSightOverlayView.DEFAULT_HORIZONTAL_FOV_DEGREES
            }
            val halfAngle = kotlin.math.atan((sensorWidthMm / 2f) / focalLengthMm)
            return Math.toDegrees((2.0 * halfAngle).toDouble()).toFloat()
                .coerceIn(42f, 78f)
        }
    }
}

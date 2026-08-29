package com.towerscope.ar.ui

import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * Back-camera preview for compass sight mode (viewfinder only — radar stays separate).
 */
class CompassCameraController(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView
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

        provider.bindToLifecycle(
            activity,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview
        )
    }
}

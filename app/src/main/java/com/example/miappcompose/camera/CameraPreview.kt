package com.example.miappcompose.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

@Composable
fun CameraPreview(onFacesDetected: (List<android.graphics.Rect>) -> Unit) {
    val context = LocalContext.current

    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx)
        startCamera(ctx, previewView, onFacesDetected)
        previewView
    })
}

private fun startCamera(
    context: Context,
    previewView: PreviewView,
    onFacesDetected: (List<android.graphics.Rect>) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        val preview = androidx.camera.core.Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }

        val analyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(
                    ContextCompat.getMainExecutor(context),
                    FaceAnalyzer(onFacesDetected)
                )
            }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            context as androidx.lifecycle.LifecycleOwner,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            preview,
            analyzer
        )
    }, ContextCompat.getMainExecutor(context))
}
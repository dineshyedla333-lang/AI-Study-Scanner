package com.dineshyedla.aistudyscanner.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onSolved: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember { mutableStateOf(hasPermission(context)) }

    // NOTE: For simplicity, we ask user to grant permission from system dialog.
    // In production, use Accompanist Permissions or ActivityResult API wrapper.
    LaunchedEffect(Unit) {
        hasCameraPermission = hasPermission(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanner") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (!hasCameraPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Camera permission is required to scan questions.")
                Text("Grant camera permission in Settings → Apps → AIStudyScanner → Permissions.")
            }
            return@Scaffold
        }

        val imageCapture = remember { ImageCapture.Builder().build() }
        val executor = remember { ContextCompat.getMainExecutor(context) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                context = context,
                lifecycleOwner = lifecycleOwner,
                imageCapture = imageCapture
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { onSolved() }) {
                    Text("Skip")
                }

                Button(onClick = {
                    capturePhoto(
                        context = context,
                        imageCapture = imageCapture,
                        executor = executor
                    )
                    // For now we just navigate to solution screen after capture.
                    onSolved()
                }) {
                    Text("Capture")
                }
            }
        }
    }
}

private fun hasPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun CameraPreview(
    modifier: Modifier,
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    imageCapture: ImageCapture
) {
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { previewView ->
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (_: Exception) {
                // no-op for now
            }

            DisposableEffect(Unit) {
                onDispose {
                    cameraProvider.unbindAll()
                }
            }
        }
    )
}

@SuppressLint("RestrictedApi")
private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor
) {
    val outputDir = context.cacheDir
    val file = File(outputDir, "scan_${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // TODO: run OCR (ML Kit) and call backend solve API
            }

            override fun onError(exception: ImageCaptureException) {
                // TODO: show error UI
            }
        }
    )
}

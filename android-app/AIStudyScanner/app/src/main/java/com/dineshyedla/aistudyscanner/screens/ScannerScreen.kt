package com.aistudyscanner.agent.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onSolved: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(hasPermission(context)) }

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
                },
            )
        }
    ) { padding ->
        if (!hasCameraPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Camera permission is required to scan questions.")
                Text("Grant camera permission in Settings → Apps → Permissions.")
            }
            return@Scaffold
        }

        val imageCapture = remember { ImageCapture.Builder().build() }
        val executor: Executor = remember { ContextCompat.getMainExecutor(context) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                context = context,
                lifecycleOwner = lifecycleOwner,
                imageCapture = imageCapture,
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(onClick = { onSolved("") }) { Text("Skip") }
                Button(
                    onClick = {
                        capturePhoto(
                            context = context,
                            imageCapture = imageCapture,
                            executor = executor,
                            onTextExtracted = { onSolved(it) },
                            onError = { onSolved("") },
                        )
                    }
                ) { Text("Capture") }
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
    imageCapture: ImageCapture,
) {
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor: Executor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null
        // Type inferred from Java return type — no explicit ListenableFuture import needed
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener(
            {
                runCatching {
                    cameraProvider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                }
            },
            executor,
        )

        onDispose {
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

@SuppressLint("RestrictedApi")
private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    onTextExtracted: (String) -> Unit,
    onError: (Exception) -> Unit,
) {
    val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri: Uri = output.savedUri ?: Uri.fromFile(file)
                runOcr(context, uri, onTextExtracted, onError)
            }
            override fun onError(exception: ImageCaptureException) = onError(exception)
        },
    )
}

private fun runOcr(
    context: Context,
    imageUri: Uri,
    onTextExtracted: (String) -> Unit,
    onError: (Exception) -> Unit,
) {
    try {
        val image = InputImage.fromFilePath(context, imageUri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { onTextExtracted(it.text.orEmpty().trim()) }
            .addOnFailureListener { onError(it) }
    } catch (e: Exception) {
        onError(e)
    }
}

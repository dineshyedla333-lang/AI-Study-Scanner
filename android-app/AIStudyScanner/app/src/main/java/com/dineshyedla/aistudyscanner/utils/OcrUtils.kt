package com.aistudyscanner.agent.utils

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

fun runOcr(
    context: Context,
    imageUri: Uri,
    onTextExtracted: (String) -> Unit,
    onError: (Exception) -> Unit,
) {
    try {
        val image = InputImage.fromFilePath(context, imageUri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { result -> onTextExtracted(result.text.orEmpty().trim()) }
            .addOnFailureListener { onError(it) }
    } catch (e: Exception) {
        onError(e)
    }
}

package com.aistudyscanner.agent.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.aistudyscanner.agent.billing.BillingManager
import com.aistudyscanner.agent.network.ApiClient
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

private const val TAG = "Ocr"

/**
 * Both scan entry points (camera and gallery) come through here.
 *
 * Pro users get server-side math OCR (Mathpix via `POST /ocr`), which reads
 * superscripts, fractions and handwriting that on-device OCR cannot. It costs
 * money per image, so free users — and Pro users past [MathOcrQuota]'s daily cap
 * or when the server is unavailable — use on-device ML Kit, upgraded with
 * [superscriptAware] so `t³` survives as `t^3` instead of `t`.
 *
 * Callbacks arrive on the main thread.
 */
fun runOcr(
    context: Context,
    imageUri: Uri,
    onTextExtracted: (String) -> Unit,
    onError: (Exception) -> Unit,
) {
    val appContext = context.applicationContext
    if (BillingManager.isPro.value && MathOcrQuota.canUse(appContext)) {
        ocrScope.launch {
            val text = runCatching { mathOcr(appContext, imageUri) }
                .onFailure { Log.w(TAG, "Math OCR unavailable, using on-device: ${it.message}") }
                .getOrNull()
            withContext(Dispatchers.Main) {
                if (!text.isNullOrBlank()) {
                    MathOcrQuota.recordUse(appContext)
                    onTextExtracted(text)
                } else {
                    mlKitOcr(appContext, imageUri, onTextExtracted, onError)
                }
            }
        }
    } else {
        mlKitOcr(appContext, imageUri, onTextExtracted, onError)
    }
}

private val ocrScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun mlKitOcr(
    context: Context,
    imageUri: Uri,
    onTextExtracted: (String) -> Unit,
    onError: (Exception) -> Unit,
) {
    try {
        val image = InputImage.fromFilePath(context, imageUri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { result -> onTextExtracted(superscriptAware(result)) }
            .addOnFailureListener { onError(it) }
    } catch (e: Exception) {
        onError(e)
    }
}

/**
 * Rebuilds ML Kit's text, marking raised, smaller tokens as superscripts.
 *
 * ML Kit's Latin model has no notion of exponents: a printed `t³` comes back as
 * either `t3` (merged) or `t` `3` as two elements, and the height/offset that
 * make it a superscript to a human is discarded — except in the bounding boxes,
 * which survive. An element that is clearly shorter than its line and sits above
 * the line's midline is almost always an exponent, so it is re-attached to the
 * previous token as `^3`. The merged `t3` case is left for the server's OCR
 * repair step, which has the context to tell `t3` from a genuine `t3`.
 *
 * Only short tokens qualify (exponents are 1–3 characters), and the thresholds
 * are deliberately strict: a missed superscript costs one wrong answer, a false
 * one corrupts a question that was read correctly.
 */
internal fun superscriptAware(result: Text): String {
    val out = StringBuilder()
    for (block in result.textBlocks) {
        for (line in block.lines) {
            val elements = line.elements
            val boxes = elements.map { it.boundingBox }
            val heights = boxes.mapNotNull { it?.height() }.filter { it > 0 }.sorted()
            val lineBox = line.boundingBox
            if (heights.size < 2 || lineBox == null) {
                out.append(line.text)
            } else {
                val medianHeight = heights[heights.size / 2]
                val midY = lineBox.exactCenterY()
                var first = true
                for ((i, element) in elements.withIndex()) {
                    val text = element.text
                    val box = boxes[i]
                    val isSuper = !first && box != null &&
                        isSuperscriptBox(box, medianHeight, midY) &&
                        text.length in 1..3 && text.all { it.isLetterOrDigit() || it in "+-" }
                    when {
                        isSuper -> out.append('^').append(text)
                        first -> out.append(text)
                        else -> out.append(' ').append(text)
                    }
                    first = false
                }
            }
            out.append('\n')
        }
    }
    return out.toString().trim()
}

private fun isSuperscriptBox(box: Rect, medianHeight: Int, lineMidY: Float): Boolean {
    val small = box.height() < medianHeight * 0.72f
    // Whole box above the line's midline: its bottom edge does not reach the middle.
    val raised = box.bottom < lineMidY
    return small && raised
}

private suspend fun mathOcr(context: Context, imageUri: Uri): String {
    val jpeg = withContext(Dispatchers.IO) { compressForUpload(context, imageUri) }
    val part = MultipartBody.Part.createFormData(
        "image",
        "scan.jpg",
        jpeg.toRequestBody("image/jpeg".toMediaType()),
    )
    return ApiClient.api.ocr(part).text.trim()
}

/**
 * Camera photos are 3–8 MB; Mathpix reads printed maths fine at ~1600 px, and
 * the server caps uploads at 3 MB. Downscale by power-of-two on decode to stay
 * under memory limits on low-end devices, then re-encode as JPEG.
 */
private fun compressForUpload(context: Context, uri: Uri, maxEdge: Int = 1600): ByteArray {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: throw IllegalStateException("Could not decode image")
    val scaled = if (maxOf(bitmap.width, bitmap.height) > maxEdge) {
        val ratio = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    } else bitmap
    return ByteArrayOutputStream().use { buf ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, buf)
        buf.toByteArray()
    }
}

/**
 * Per-device daily cap on paid OCR calls. A Pro subscription is ₹99/month and
 * each Mathpix image is about ₹0.17, so the cap keeps the worst-case cost of one
 * subscriber well under what they pay. Beyond it the scan still works — on-device.
 */
object MathOcrQuota {
    private const val PREFS = "math_ocr_quota"
    private const val DAILY_LIMIT = 15

    fun canUse(context: Context): Boolean = usedToday(context) < DAILY_LIMIT

    fun recordUse(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("day", today()).putInt("used", usedToday(context) + 1).apply()
    }

    private fun usedToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString("day", null) == today()) prefs.getInt("used", 0) else 0
    }

    // minSdk 24 has no java.time without desugaring.
    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
}

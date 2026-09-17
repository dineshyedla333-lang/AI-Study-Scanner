package com.aistudyscanner.agent.ui

import android.annotation.SuppressLint
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

private const val PAGE_URL = "file:///android_asset/math/math.html"

/**
 * Renders the model's answer — Markdown with LaTeX in `\( \)`, `\[ \]` or `$$` —
 * as formatted text and typeset maths.
 *
 * Backed by a WebView running KaTeX and marked from the bundled assets (no
 * network). The page reports its content height back through [MathBridge] so
 * the view sizes to its content and the surrounding Compose column scrolls as
 * one; the WebView itself never scrolls. If the page fails to report a height
 * (WebView missing or disabled — rare, but it happens on some de-Googled
 * devices) the raw text is shown instead so the answer is never lost.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    var contentHeightDp by remember { mutableIntStateOf(0) }
    var pageReady by remember { mutableStateOf(false) }
    var showFallback by remember { mutableStateOf(false) }
    // What the page currently shows, so recompositions do not re-render.
    val shown = remember { arrayOfNulls<String>(1) }
    val colorCss = remember(color) { color.toCssRgb() }

    LaunchedEffect(markdown) {
        delay(3_000)
        if (contentHeightDp == 0) showFallback = true
    }

    if (showFallback) {
        Text(
            text = markdown,
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        return
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (contentHeightDp > 0) Modifier.height(contentHeightDp.dp)
                else Modifier.heightIn(min = 24.dp),
            ),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addJavascriptInterface(
                    MathBridge { px -> post { contentHeightDp = px } },
                    "MathBridge",
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageReady = true
                    }
                }
                loadUrl(PAGE_URL)
            }
        },
        update = { webView ->
            if (!pageReady) return@AndroidView
            if (shown[0] != markdown) {
                webView.evaluateJavascript("setTextColor(${JSONObject.quote(colorCss)})", null)
                webView.evaluateJavascript("render(${JSONObject.quote(markdown)})", null)
                shown[0] = markdown
            }
        },
    )
}

/** JS → Kotlin bridge; the page calls `MathBridge.onHeight(px)` after each render. */
private class MathBridge(private val onHeight: (Int) -> Unit) {
    @JavascriptInterface
    fun onHeight(px: Int) {
        // Arrives on the WebView's JS thread; the caller hops back to main.
        onHeight.invoke(px)
    }
}

private fun Color.toCssRgb(): String {
    val r = (red * 255).roundToInt()
    val g = (green * 255).roundToInt()
    val b = (blue * 255).roundToInt()
    return "rgb($r,$g,$b)"
}

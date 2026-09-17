# Keep Gson/Retrofit model field names so JSON serialization works in release builds
-keep class com.aistudyscanner.agent.network.** { *; }
-keepclassmembers class com.aistudyscanner.agent.network.** { <fields>; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# WebView JS bridge for the KaTeX answer renderer (ui/MathMarkdown.kt): the page
# calls MathBridge.onHeight by name, so R8 must not rename or strip it.
-keepattributes JavascriptInterface
-keepclassmembers class com.aistudyscanner.agent.ui.MathBridge {
    @android.webkit.JavascriptInterface <methods>;
}

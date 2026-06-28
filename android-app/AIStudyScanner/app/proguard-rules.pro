# Keep Gson/Retrofit model field names so JSON serialization works in release builds
-keep class com.aistudyscanner.agent.network.** { *; }
-keepclassmembers class com.aistudyscanner.agent.network.** { <fields>; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

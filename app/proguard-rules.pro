# Keep Gson serialized classes
-keep class com.mimo.app.data.model.** { *; }
-keepclassmembers class com.mimo.app.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

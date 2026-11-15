# Keep Gson serialized classes
-keep class com.zhibai.app.data.model.** { *; }
-keepclassmembers class com.zhibai.app.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# KaTeX WebView height bridge — @JavascriptInterface methods are called via reflection
-keep class com.zhibai.app.ui.components.KatexHeightBridge { *; }
-keepclassmembers class com.zhibai.app.ui.components.KatexHeightBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Wireless debugging ADB (Shizuku-derived)
-keep class com.zhibai.app.adb.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn com.google.errorprone.annotations.**

# MNN local LLM — JNI resolves natives by exact class/method name
-keep class com.alibaba.mnnllm.android.llm.** { *; }
-keepclassmembers class com.alibaba.mnnllm.android.llm.** { native <methods>; }

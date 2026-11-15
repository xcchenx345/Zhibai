# Keep Gson serialized classes
-keep class com.zhibai.app.data.model.** { *; }
-keepclassmembers class com.zhibai.app.data.model.** { *; }
# Gson-reflected agent DTOs (outside data.model.**) — R8 field renaming broke
# reflective binding and the tool loop died with ClassCastException.
-keep class com.zhibai.app.agent.StructuredToolCallResponse { *; }
-keep class com.zhibai.app.agent.StructuredToolCall { *; }
# Gson-reflected DTOs in data.api / agent — R8 mangling made gson throw
# "Abstract classes can't be instantiated" on the ASR/TTS responses.
-keep class com.zhibai.app.data.api.** { *; }
-keep class com.zhibai.app.agent.** { *; }

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

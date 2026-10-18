package com.mimo.app.agent

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class AutoGLMRequest(
    val model: String = "autoglm-phone",
    val messages: List<Map<String, Any>>,
    val stream: Boolean = false,
    @SerializedName("temperature") val temperature: Float = 0.1f
)

data class AutoGLMResponse(
    val id: String?,
    val choices: List<AutoGLMChoice>?
)

data class AutoGLMChoice(
    val index: Int,
    val message: AutoGLMMessage?
)

data class AutoGLMMessage(
    val role: String,
    val content: String
)

data class AutoGLMAction(
    val type: String,
    val x: Int? = null,
    val y: Int? = null,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val package_name: String? = null,
    val keycode: String? = null
) {
    companion object {
        fun parseFromJson(json: String): AutoGLMAction? {
            return try {
                val map = com.google.gson.Gson().fromJson(json, Map::class.java) as? Map<*, *>
                val type = map?.get("type") as? String ?: return null
                AutoGLMAction(
                    type = type,
                    x = (map["x"] as? Number)?.toInt(),
                    y = (map["y"] as? Number)?.toInt(),
                    x1 = (map["x1"] as? Number)?.toInt(),
                    y1 = (map["y1"] as? Number)?.toInt(),
                    x2 = (map["x2"] as? Number)?.toInt(),
                    y2 = (map["y2"] as? Number)?.toInt(),
                    text = map["text"] as? String,
                    package_name = map["package_name"] as? String,
                    keycode = map["keycode"] as? String
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

class AutoGLMService(
    private var apiKey: String = "",
    private var baseUrl: String = "https://open.bigmodel.cn/api/paas/v4"
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun updateApiKey(key: String) { apiKey = key }
    fun updateBaseUrl(url: String) { baseUrl = url }

    suspend fun execute(
        bitmap: Bitmap,
        instruction: String,
        history: List<Map<String, String>> = emptyList()
    ): AutoGLMAction? = withContext(Dispatchers.IO) {
        val base64 = bitmapToBase64(bitmap)

        val systemMessage = mapOf(
            "role" to "system",
            "content" to """你是一个手机智能助理，可以理解屏幕内容并执行操作。用户会给你当前屏幕截图和操作指令，你需要返回一个JSON对象表示要执行的操作。

支持的操作类型：
- tap: 点击指定坐标 {"type":"tap","x":100,"y":200}
- swipe: 滑动 {"type":"swipe","x1":100,"y1":500,"x2":100,"y2":200}
- type: 输入文本 {"type":"type","text":"hello"}
- back: 返回 {"type":"back"}
- home: 返回桌面 {"type":"home"}
- launch: 启动应用 {"type":"launch","package_name":"com.tencent.mm"}
- long_press: 长按 {"type":"long_press","x":100,"y":200}
- double_tap: 双击 {"type":"double_tap","x":100,"y":200}
- wait: 等待 {"type":"wait","duration":2000}
- done: 任务完成 {"type":"done"}
- take_over: 需要人工接管 {"type":"take_over"}

只返回JSON，不要其他文字。"""
        )

        val imageMessage = mapOf(
            "role" to "user",
            "content" to listOf(
                mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/png;base64,$base64")),
                mapOf("type" to "text", "text" to instruction)
            )
        )

        val messages = mutableListOf<Map<String, Any>>(systemMessage)
        messages.addAll(history)
        messages.add(imageMessage)

        val request = AutoGLMRequest(messages = messages)
        val json = gson.toJson(request)
        val body = json.toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val response = client.newCall(httpRequest).await()
        if (!response.isSuccessful) {
            throw IOException("API error: ${response.code} ${response.message}")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        val apiResponse = gson.fromJson(responseBody, AutoGLMResponse::class.java)
        val content = apiResponse.choices?.firstOrNull()?.message?.content ?: ""

        AutoGLMAction.parseFromJson(content)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response)
            }
        })
    }
}

package com.zhibai.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
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
    val content: String?,
    /** GLM thinking models put CoT here; final action may be empty content. */
    @SerializedName("reasoning_content") val reasoningContent: String? = null
)

/**
 * Parsed AutoGLM action.
 * Official Open-AutoGLM model emits:
 *   do(action="Launch", app="京东")
 *   do(action="Tap", element=[500, 100])
 *   do(action="Type", text="hello")
 */
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
    val app: String? = null,
    val keycode: String? = null,
    val duration: Long? = null
) {
    companion object {
        private const val TAG = "AutoGLMAction"

        fun parse(content: String?): AutoGLMAction? {
            if (content.isNullOrBlank()) return null
            // Pull <answer>...</answer> if present (Open-AutoGLM style)
            val answer = Regex("""<answer>(.*?)</answer>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(content)?.groupValues?.get(1)?.trim()
            val cleaned = (answer ?: content)
                .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""```"""), "")
                .trim()

            parseDoCall(cleaned)?.let { return it }
            parseJson(cleaned)?.let { return it }

            // Last resort: any do( in the whole original content
            parseDoCall(content)?.let { return it }
            parseJson(content)?.let { return it }

            Log.w(TAG, "unparseable content: ${content.take(400)}")
            return null
        }

        private fun parseDoCall(text: String): AutoGLMAction? {
            // Find first do(action="...") — do NOT require end-of-string
            val m = Regex(
                """do\s*\(\s*action\s*=\s*["']([^"']+)["']([^)]*(?:\)[^)]*)*)\)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(text) ?: return null

            val action = m.groupValues[1].trim()
            // group 2 is everything after action=... up to the closing of do(
            // Use a simpler approach: take substring from first do( and parse balanced-ish
            val start = m.range.first
            val afterDo = text.substring(start)
            val close = findClosingParen(afterDo) ?: return null
            val inner = afterDo.substring(afterDo.indexOf('(') + 1, close)

            val params = mutableMapOf<String, String>()
            val paramRegex = Regex(
                """(\w+)\s*=\s*(\[[^\]]*\]|"[^"]*"|'[^']*'|\d+)""",
                RegexOption.IGNORE_CASE
            )
            paramRegex.findAll(inner).forEach { pm ->
                var v = pm.groupValues[2].trim()
                if ((v.startsWith("\"") && v.endsWith("\"")) ||
                    (v.startsWith("'") && v.endsWith("'"))
                ) {
                    v = v.substring(1, v.length - 1)
                }
                params[pm.groupValues[1].lowercase()] = v
            }
            return fromOfficial(action, params)
        }

        private fun findClosingParen(s: String): Int? {
            var depth = 0
            for (i in s.indices) {
                when (s[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return null
        }

        private fun fromOfficial(action: String, p: Map<String, String>): AutoGLMAction? {
            fun ints(key: String): List<Int>? =
                p[key]?.trim('[', ']')?.split(',')?.mapNotNull { it.trim().toIntOrNull() }

            return when (action.lowercase()) {
                "launch", "open" -> AutoGLMAction(
                    type = "launch",
                    app = p["app"] ?: p["package_name"] ?: p["application"],
                    package_name = p["package_name"] ?: p["app"]
                )
                "tap", "click" -> {
                    val el = ints("element") ?: ints("coordinate") ?: ints("pos")
                    val x = el?.getOrNull(0) ?: p["x"]?.toIntOrNull()
                    val y = el?.getOrNull(1) ?: p["y"]?.toIntOrNull()
                    if (x != null && y != null) AutoGLMAction(type = "tap", x = x, y = y)
                    else AutoGLMAction(type = "tap", text = p["text"] ?: p["element_text"])
                }
                "type", "input" -> AutoGLMAction(type = "type", text = p["text"] ?: p["content"] ?: p["input"])
                "swipe" -> {
                    val start = ints("start") ?: ints("from")
                    val end = ints("end") ?: ints("to")
                    val s = start ?: ints("element")
                    val e = end
                    if (s != null && e != null) {
                        AutoGLMAction(
                            type = "swipe",
                            x1 = s[0], y1 = s.getOrNull(1) ?: 0,
                            x2 = e[0], y2 = e.getOrNull(1) ?: 0
                        )
                    } else {
                        // direction-based swipe with start point
                        val el = ints("element") ?: ints("coordinate")
                        if (el != null) {
                            val dir = (p["direction"] ?: "up").lowercase()
                            val (dx, dy) = when (dir) {
                                "down" -> 0 to -400
                                "left" -> -400 to 0
                                "right" -> 400 to 0
                                else -> 0 to 400 // up
                            }
                            AutoGLMAction(
                                type = "swipe",
                                x1 = el[0], y1 = el.getOrNull(1) ?: 0,
                                x2 = el[0] + dx, y2 = (el.getOrNull(1) ?: 0) + dy
                            )
                        } else null
                    }
                }
                "back" -> AutoGLMAction(type = "back")
                "home" -> AutoGLMAction(type = "home")
                "long_press", "long press", "longpress" -> {
                    val el = ints("element") ?: ints("coordinate")
                    if (el != null) AutoGLMAction(type = "long_press", x = el[0], y = el.getOrNull(1) ?: 0)
                    else null
                }
                "double_tap", "double tap", "doubletap" -> {
                    val el = ints("element") ?: ints("coordinate")
                    if (el != null) AutoGLMAction(type = "double_tap", x = el[0], y = el.getOrNull(1) ?: 0)
                    else null
                }
                "wait" -> AutoGLMAction(type = "wait", text = p["duration"] ?: p["seconds"] ?: "1000")
                "take_over", "takeover", "take over" -> AutoGLMAction(type = "take_over")
                else -> {
                    Log.w(TAG, "unknown action: $action params=$p")
                    null
                }
            }
        }

        private fun parseJson(text: String): AutoGLMAction? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val json = text.substring(start, end + 1)
            return try {
                val map = Gson().fromJson(json, Map::class.java) as? Map<*, *> ?: return null
                val type = (map["type"] as? String ?: map["action"] as? String)?.lowercase() ?: return null
                AutoGLMAction(
                    type = type,
                    x = (map["x"] as? Number)?.toInt(),
                    y = (map["y"] as? Number)?.toInt(),
                    x1 = (map["x1"] as? Number)?.toInt(),
                    y1 = (map["y1"] as? Number)?.toInt(),
                    x2 = (map["x2"] as? Number)?.toInt(),
                    y2 = (map["y2"] as? Number)?.toInt(),
                    text = map["text"] as? String,
                    package_name = map["package_name"] as? String ?: map["app"] as? String,
                    app = map["app"] as? String
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
    companion object {
        private const val TAG = "AutoGLMService"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun updateApiKey(key: String) { apiKey = key }
    fun updateBaseUrl(url: String) { baseUrl = url }

    /** Last raw model text — for error surfaces / logs. */
    @Volatile
    var lastRawContent: String? = null
        private set

    suspend fun execute(
        bitmap: Bitmap,
        instruction: String,
        history: List<Map<String, String>> = emptyList()
    ): AutoGLMAction? = withContext(Dispatchers.IO) {
        val base64 = bitmapToBase64(bitmap)

        // Model is fine-tuned for AutoGLM phone control; keep prompt aligned with Open-AutoGLM.
        val systemMessage = mapOf(
            "role" to "system",
            "content" to """你是一个手机智能助理。根据用户指令和当前屏幕截图，用 do(...) 形式返回下一步操作。

可用操作：
- do(action="Launch", app="应用名")
- do(action="Tap", element=[x, y])
- do(action="Type", text="要输入的文本")
- do(action="Swipe", start=[x1, y1], end=[x2, y2]) 或 do(action="Swipe", element=[x, y], direction="up")
- do(action="Back")
- do(action="Home")
- do(action="Long Press", element=[x, y])
- do(action="Double Tap", element=[x, y])
- do(action="Wait", duration=1000)
- do(action="Take_over")  # 需要人工处理登录/验证码时

任务完成后：do(action="Take_over") 或不再继续调用。
只输出一行 do(...)，不要 markdown，不要解释。"""
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
            val err = response.body?.string()?.take(400) ?: ""
            throw IOException("API error: ${response.code} ${response.message} $err")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        Log.d(TAG, "raw response: ${responseBody.take(800)}")
        val apiResponse = gson.fromJson(responseBody, AutoGLMResponse::class.java)
        val msg = apiResponse.choices?.firstOrNull()?.message
        val candidates = listOfNotNull(msg?.content, msg?.reasoningContent)
        lastRawContent = candidates.firstOrNull { !it.isNullOrBlank() } ?: responseBody.take(500)
        var action: AutoGLMAction? = null
        for (c in candidates) {
            action = AutoGLMAction.parse(c)
            if (action != null) break
        }
        if (action == null) {
            Log.w(TAG, "parse failed. content=${msg?.content?.take(300)} reasoning=${msg?.reasoningContent?.take(300)}")
        }
        action
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        // Downscale huge screenshots to keep request size reasonable (model max_pixels ~5M)
        var out = bitmap
        val maxSide = 1600
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            out = Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        val stream = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val bytes = stream.toByteArray()
        if (out !== bitmap) out.recycle()
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

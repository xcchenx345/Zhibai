package com.zhibai.app.data.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.zhibai.app.data.model.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class MiMoApiService(
    private var apiKey: String = "",
    private var baseUrl: String = "https://api.xiaomimimo.com/v1"
) {
    private val gson: Gson = GsonBuilder().create()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun updateApiKey(key: String) { apiKey = key }
    fun updateBaseUrl(url: String) { baseUrl = url }
    fun getClient(): OkHttpClient = client
    fun getBaseUrl(): String = baseUrl
    fun getApiKey(): String = apiKey

    /**
     * Recursively extract a human-readable message from an OpenAI-style error body
     * ({"error":{"message":...}} etc., ported from Rikkahub's ErrorParser).
     * Returns null if the body isn't JSON or holds no recognizable error field.
     */
    private fun parseErrorDetail(body: String): String? {
        if (body.isBlank()) return null
        val element = try { JsonParser.parseString(body) } catch (_: Exception) { return null }
        return extractErrorText(element)
    }

    private fun extractErrorText(el: JsonElement): String? = when {
        el.isJsonObject -> {
            val obj = el.asJsonObject
            listOf("error", "detail", "message", "description")
                .firstNotNullOfOrNull { key ->
                    obj.get(key)?.takeIf { !it.isJsonNull }?.let { extractErrorText(it) }
                }
                ?: obj.toString().take(300)
        }
        el.isJsonArray -> if (el.asJsonArray.size() > 0) extractErrorText(el.asJsonArray[0]) else null
        el.isJsonPrimitive -> el.asString.takeIf { it.isNotBlank() }?.take(300)
        else -> null
    }

    fun chatCompletion(request: ChatRequest): Flow<StreamChunk> = callbackFlow {
        val json = gson.toJson(request.copy(
            stream = true,
            streamOptions = mapOf("include_usage" to true)
        ))
        val body = json.toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val call = client.newCall(httpRequest)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // call.cancel() from awaitClose (user pressed stop) surfaces as
                // IOException("Canceled") — not an error, close the flow silently.
                if (call.isCanceled()) close()
                else close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                    response.close()
                    close(IOException(parseErrorDetail(errorBody) ?: "HTTP ${response.code}: ${response.message}"))
                    return
                }

                val source = response.body?.source() ?: run {
                    response.close()
                    close(IOException("Empty response body"))
                    return
                }

                var sawDone = false
                var sawFinish = false
                var usage: Usage? = null
                var streamError: IOException? = null
                val frameBuf = StringBuilder()

                // Process one complete SSE frame. Returns false → stop reading.
                fun processFrame(frame: String): Boolean {
                    for (raw in frame.split('\n')) {
                        val data = raw.trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") {
                            sawDone = true
                            trySend(StreamChunk.Done(usage))
                            return false
                        }
                        val obj = try {
                            val el = JsonParser.parseString(data)
                            if (el.isJsonObject) el.asJsonObject else continue
                        } catch (_: Exception) {
                            continue // skip non-JSON junk (data: null etc.)
                        }
                        // Mid-stream API error — surface it instead of swallowing (Rikkahub-style)
                        if (obj.has("error") && !obj.get("error").isJsonNull) {
                            streamError = IOException(parseErrorDetail(data) ?: data.take(300))
                            return false
                        }
                        try {
                            val resp = gson.fromJson(obj, StreamResponse::class.java) ?: continue
                            // usage chunk carries empty choices — capture before the elvis below
                            resp.usage?.let { usage = it }
                            val choice = resp.choices.firstOrNull() ?: continue
                            if (choice.finishReason != null) sawFinish = true
                            trySend(StreamChunk.Data(
                                content = choice.delta.content ?: "",
                                reasoning = choice.delta.reasoningContent ?: "",
                                finished = choice.finishReason != null,
                                finishReason = choice.finishReason,
                                annotations = choice.delta.annotations
                            ))
                            // No early exit on finish_reason — usage/[DONE] arrive after it
                        } catch (_: Exception) {
                            // malformed payload — skip
                        }
                    }
                    return true
                }

                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) {
                            // Blank line = end of one SSE event
                            if (frameBuf.isNotEmpty()) {
                                val frame = frameBuf.toString()
                                frameBuf.setLength(0)
                                if (!processFrame(frame)) break
                            }
                        } else if (line.startsWith("data:")) {
                            val v = line.removePrefix("data:")
                            val value = if (v.startsWith(" ")) v.substring(1) else v
                            if (frameBuf.isNotEmpty()) frameBuf.append('\n')
                            frameBuf.append(value)
                        }
                        // event:/id:/retry:/:comment lines — ignore
                    }
                    // EOF without trailing blank line — flush the residual frame
                    if (frameBuf.isNotEmpty()) processFrame(frameBuf.toString())
                } catch (e: Exception) {
                    // Mid-stream failure: still finalize so the UI doesn't hang on isStreaming
                    if (!sawDone) trySend(StreamChunk.Done(usage))
                    // finish_reason already seen → content complete; abrupt close is not an error
                    if (sawFinish) close() else close(e)
                    return
                } finally {
                    response.close()
                }

                val err = streamError
                if (err != null) {
                    if (!sawDone) trySend(StreamChunk.Done(usage))
                    // A tail error frame after finish_reason carries no signal — the
                    // content is already complete. Closing with err here made callers
                    // treat a finished answer as a failure and re-stream over it.
                    if (sawFinish) close() else close(err)
                    return
                }
                // EOF without [DONE] — finalize anyway
                if (!sawDone) trySend(StreamChunk.Done(usage))
                close()
            }
        })

        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED)

    fun buildUserMessage(
        text: String,
        imageBase64: String? = null,
        audioBase64: String? = null,
        audioMimeType: String? = null,
        videoBase64: String? = null,
        videoMimeType: String? = null,
        mimeType: String = "image/jpeg"
    ): Message {
        val contentParts = mutableListOf<ImageContent>()

        // Add text
        if (text.isNotBlank()) {
            contentParts.add(ImageContent(type = "text", text = text))
        }

        // Add image
        if (imageBase64 != null) {
            contentParts.add(ImageContent(
                type = "image_url",
                imageUrl = ImageUrl(url = "data:$mimeType;base64,$imageBase64")
            ))
        }

        // Add audio
        if (audioBase64 != null && audioMimeType != null) {
            contentParts.add(ImageContent(
                type = "input_audio",
                inputAudio = InputAudio(data = "data:$audioMimeType;base64,$audioBase64")
            ))
        }

        // Add video as video_url (per docs: type=video_url with fps & media_resolution)
        if (videoBase64 != null && videoMimeType != null) {
            contentParts.add(ImageContent(
                type = "video_url",
                videoUrl = ImageUrl(url = "data:$videoMimeType;base64,$videoBase64"),
                fps = 2f,
                mediaResolution = "default"
            ))
        }

        return if (contentParts.size == 1 && contentParts[0].type == "text") {
            Message(role = "user", content = text)
        } else if (contentParts.isNotEmpty()) {
            Message(role = "user", content = contentParts)
        } else {
            Message(role = "user", content = text)
        }
    }

    fun buildRequest(
        messages: List<Message>,
        model: String = "mimo-v2.6-flash",
        thinkingConfig: ThinkingConfig? = null,
        enableSearch: Boolean = false
    ): ChatRequest {
        val tools = if (enableSearch) {
            // forceSearch=false — intent recognition: brief think → search → think → answer
            // (matches official MiMo chat: 已深度思考 → 搜索 N 关键词 → 已深度思考 → 回答).
            listOf(Tool(
                type = "web_search",
                maxKeyword = 3,
                forceSearch = false,
                limit = 3
            ))
        } else null

        val thinkingOn = thinkingConfig?.type == "enabled"
        return ChatRequest(
            model = model,
            messages = messages,
            maxCompletionTokens = if (thinkingOn) 65536 else 32768,
            stream = true,
            thinking = thinkingConfig ?: ThinkingConfig(type = "disabled"),
            tools = tools
        )
    }

    /**
     * GET /models — returns available chat model ids (OpenAI-compatible {"data":[{"id":...}]}).
     */
    suspend fun listModels(): List<String> {
        val httpRequest = Request.Builder()
            .url("$baseUrl/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${parseErrorDetail(body) ?: body.take(300).ifBlank { response.message }}")
                }
                if (body.isBlank()) throw IOException("Empty response")
                val root = com.google.gson.JsonParser.parseString(body).asJsonObject
                root.getAsJsonArray("data")?.mapNotNull { el ->
                    el.asJsonObject.get("id")?.asString
                } ?: emptyList()
            }
        }
    }

    suspend fun chatCompletionStructured(request: ChatRequest): ChatResponse {
        val nonStreamRequest = request.copy(stream = false)
        val json = gson.toJson(nonStreamRequest)
        val body = json.toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call = client.newCall(httpRequest)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        val errorBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                        if (continuation.isActive) continuation.resumeWith(
                            Result.failure(IOException("HTTP ${response.code}: ${parseErrorDetail(errorBody) ?: errorBody.take(300).ifBlank { response.message }}"))
                        )
                        return
                    }
                    try {
                        val responseBody = response.body?.string() ?: ""
                        val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                        if (continuation.isActive) continuation.resumeWith(Result.success(chatResponse))
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }
                }
            })
        }
    }

    /**
     * Plain non-streaming completion that returns the assistant's text. Used by the
     * Agent's view_media tool — deliberately does NOT force the tool-call json_schema,
     * so the model replies with a normal description instead of a structured object.
     */
    suspend fun chatCompleteText(request: ChatRequest): String? {
        val resp = chatCompletionStructured(request.copy(stream = false, responseFormat = null, tools = null))
        val content = resp.choices.firstOrNull()?.message?.content ?: return null
        return when (content) {
            is String -> content
            else -> content.toString()
        }
    }
}

sealed class StreamChunk {
    data class Data(
        val content: String,
        val reasoning: String = "",
        val finished: Boolean = false,
        val finishReason: String? = null,
        val annotations: List<com.zhibai.app.data.model.Annotation>? = null
    ) : StreamChunk()
    data class Done(val usage: Usage? = null) : StreamChunk()
}

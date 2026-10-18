package com.mimo.app.data.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mimo.app.data.model.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
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
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun updateApiKey(key: String) { apiKey = key }
    fun updateBaseUrl(url: String) { baseUrl = url }
    fun getClient(): OkHttpClient = client
    fun getBaseUrl(): String = baseUrl
    fun getApiKey(): String = apiKey

    fun chatCompletion(request: ChatRequest): Flow<StreamChunk> = callbackFlow {
        val json = gson.toJson(request.copy(stream = true))
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
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    close(IOException("HTTP ${response.code}: ${response.message}"))
                    return
                }

                val source = response.body?.source() ?: run {
                    close(IOException("Empty response body"))
                    return
                }

                var sawDone = false
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") {
                                sawDone = true
                                trySend(StreamChunk.Done)
                                break
                            }
                            try {
                                val chunk = gson.fromJson(data, StreamResponse::class.java)
                                val choice = chunk.choices.firstOrNull() ?: continue
                                trySend(StreamChunk.Data(
                                    content = choice.delta.content ?: "",
                                    reasoning = choice.delta.reasoningContent ?: "",
                                    finished = choice.finishReason != null,
                                    annotations = choice.delta.annotations
                                ))
                                // finish_reason without trailing [DONE] — treat as end of stream
                                if (choice.finishReason != null) {
                                    sawDone = true
                                    trySend(StreamChunk.Done)
                                    break
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    // Mid-stream failure: still finalize so the UI doesn't hang on isStreaming
                    if (!sawDone) trySend(StreamChunk.Done)
                    close(e)
                    return
                } finally {
                    response.close()
                }
                // Stream ended (EOF) without [DONE] — finalize anyway
                if (!sawDone) trySend(StreamChunk.Done)
                close()
            }
        })

        awaitClose { call.cancel() }
    }

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
        enableThinking: Boolean = false,
        enableSearch: Boolean = false
    ): ChatRequest {
        val tools = if (enableSearch) {
            listOf(Tool(
                type = "web_search",
                maxKeyword = 3,
                forceSearch = true,
                limit = 3
            ))
        } else null

        return ChatRequest(
            model = model,
            messages = messages,
            stream = true,
            thinking = if (enableThinking) ThinkingConfig(type = "enabled") else ThinkingConfig(type = "disabled"),
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
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response")
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
                        val errorBody = response.body?.string() ?: "Unknown error"
                        if (continuation.isActive) continuation.resumeWith(
                            Result.failure(IOException("HTTP ${response.code}: $errorBody"))
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
}

sealed class StreamChunk {
    data class Data(
        val content: String,
        val reasoning: String = "",
        val finished: Boolean = false,
        val annotations: List<com.mimo.app.data.model.Annotation>? = null
    ) : StreamChunk()
    data object Done : StreamChunk()
}

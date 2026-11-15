package com.zhibai.app.data.model

import com.google.gson.annotations.SerializedName

// ===== Request =====

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    @SerializedName("max_completion_tokens") val maxCompletionTokens: Int = 32768,
    val temperature: Float = 1.0f,
    @SerializedName("top_p") val topP: Float = 0.95f,
    val stream: Boolean = true,
    val thinking: ThinkingConfig? = null,
    val tools: List<Tool>? = null,
    @SerializedName("response_format") val responseFormat: ResponseFormat? = null,
    @SerializedName("stream_options") val streamOptions: Map<String, Boolean>? = null
)

data class ResponseFormat(
    val type: String = "json_schema",
    @SerializedName("json_schema") val jsonSchema: JsonSchema? = null
)

data class JsonSchema(
    val name: String,
    val strict: Boolean = true,
    val schema: Map<String, Any>
)

data class Message(
    val role: String,
    val content: Any? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null,
    val annotations: List<Annotation>? = null,
    @SerializedName("reasoning_content") val reasoningContent: String? = null
)

data class ImageContent(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrl? = null,
    @SerializedName("input_audio") val inputAudio: InputAudio? = null,
    @SerializedName("video_url") val videoUrl: ImageUrl? = null,
    val fps: Float? = null,
    @SerializedName("media_resolution") val mediaResolution: String? = null
)

data class ImageUrl(val url: String)

data class InputAudio(val data: String)

data class ThinkingConfig(
    val type: String,
    val budget: Int? = null  // thinking token budget: 0=off, low=1024, medium=4096, high=8192, max=16384
)

data class TTSRequestDirect(
    val model: String,
    val messages: List<Message>,
    val audio: Map<String, String>
)

data class Tool(
    val type: String = "function",
    val function: FunctionDef? = null,
    @SerializedName("max_keyword") val maxKeyword: Int? = null,
    @SerializedName("force_search") val forceSearch: Boolean? = null,
    val limit: Int? = null,
    @SerializedName("user_location") val userLocation: UserLocation? = null
)

data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

data class UserLocation(
    val type: String = "approximate",
    val country: String? = null,
    val region: String? = null,
    val city: String? = null
)

data class Annotation(
    val type: String,
    val url: String? = null,
    val title: String? = null,
    val summary: String? = null,
    @SerializedName("site_name") val siteName: String? = null,
    @SerializedName("publish_time") val publishTime: String? = null,
    @SerializedName("logo_url") val logoUrl: String? = null
)

data class FunctionCall(
    val name: String,
    val arguments: String
)

// ===== Response =====

data class ChatResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val index: Int,
    val message: Message?,
    @SerializedName("finish_reason") val finishReason: String?
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)

// ===== Stream Response =====

data class StreamResponse(
    val id: String,
    val choices: List<StreamChoice>,
    val usage: Usage?
)

data class StreamChoice(
    val index: Int,
    val delta: Delta,
    @SerializedName("finish_reason") val finishReason: String?
)

data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<StreamToolCall>? = null,
    @SerializedName("reasoning_content") val reasoningContent: String? = null,
    val annotations: List<Annotation>? = null
)

data class StreamToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: StreamFunctionCall? = null
)

data class StreamFunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

// ===== App Model =====

/** One file mutation recorded by an agent tool run (write/edit/delete/move/copy). */
data class FileChange(
    val path: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    /** Pre-mutation content snapshot for undo — never serialized into cards. */
    val oldContent: String? = null
)

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String = "",
    val reasoningContent: String = "",
    val imageUri: String? = null,
    val fileName: String? = null,
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val annotations: List<Annotation>? = null,
    val reasoningDurationMs: Long = 0,
    /** Search runs carry their own role="step" timeline rows — hide the built-in
     *  process card on the answer so think/search info is not shown twice. */
    val flatProcess: Boolean = false,
    /** Tool-card display: what the tool is doing right now (collapsed title). */
    val toolTitle: String? = null,
    /** Icon key for the tool card (shell/android/file/web/...). */
    val toolIcon: String? = null,
    /** Real-time tool output accumulated while the tool runs. */
    val toolLive: String? = null,
    /** "running" | "ok" | "error" — null on legacy messages. */
    val toolStatus: String? = null
)

data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val messages: List<ChatMessage> = emptyList(),
    val model: String = "mimo-v2.6-flash",
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    val lastActiveAt: Long = System.currentTimeMillis()
)

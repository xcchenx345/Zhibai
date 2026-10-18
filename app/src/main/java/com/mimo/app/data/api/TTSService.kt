package com.mimo.app.data.api

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.mimo.app.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TTSRequest(
    val model: String = "mimo-v2.5-tts",
    val messages: List<Message>,
    @SerializedName("audio") val audio: AudioConfig = AudioConfig(),
    val stream: Boolean = false
)

data class AudioConfig(
    val format: String = "pcm16",
    val voice: String = "mimo_default"
)

data class TTSResponse(
    val choices: List<TTSChoice>
)

data class TTSChoice(
    val message: TTSMessage?
)

data class TTSMessage(
    val audio: AudioData?
)

data class AudioData(
    val data: String
)

// ===== streaming TTS (SSE) =====

data class TTSStreamResponse(
    val choices: List<TTSStreamChoice>?
)

data class TTSStreamChoice(
    val delta: TTSStreamDelta?
)

data class TTSStreamDelta(
    val audio: AudioData?
)

data class ASRResponse(
    val choices: List<ASRChoice>
)

data class ASRChoice(
    val message: ASRMessage?
)

data class ASRMessage(
    val content: Any?
)

class TTSService(
    private var apiKey: String = "",
    private var baseUrl: String = "https://api.xiaomimimo.com/v1"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    fun updateApiKey(key: String) { apiKey = key }
    fun getClient(): OkHttpClient = client

    /** Last ASR failure detail (null when the previous call succeeded). */
    var lastAsrError: String? = null
        private set

    suspend fun speechToText(audioBase64: String, mimeType: String = "audio/wav"): String? = withContext(Dispatchers.IO) {
        lastAsrError = null
        try {
            // API only accepts wav / mp3 — normalize or reject early
            val normalized = when {
                mimeType.contains("wav", ignoreCase = true) -> "audio/wav"
                mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true) -> "audio/mpeg"
                else -> {
                    lastAsrError = "仅支持 wav / mp3，当前格式：$mimeType"
                    return@withContext null
                }
            }
            val request = mapOf(
                "model" to "mimo-v2.5-asr",
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf(
                                "type" to "input_audio",
                                "input_audio" to mapOf(
                                    "data" to "data:$normalized;base64,$audioBase64"
                                )
                            )
                        )
                    )
                ),
                "asr_options" to mapOf("language" to "auto")
            )
            val json = gson.toJson(request)
            val body = json.toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(httpRequest).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string() ?: return@withContext null
                val resp = gson.fromJson(respBody, ASRResponse::class.java)
                when (val content = resp.choices.firstOrNull()?.message?.content) {
                    is String -> content
                    else -> content?.toString()
                }
            } else {
                val err = response.body?.string() ?: response.message
                lastAsrError = "HTTP ${response.code}: ${err.take(200)}"
                null
            }
        } catch (e: Exception) {
            lastAsrError = e.message
            null
        }
    }

    suspend fun synthesize(text: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = TTSRequest(
                messages = listOf(
                    Message(role = "assistant", content = text)
                )
            )
            val json = gson.toJson(request)
            val body = json.toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(httpRequest).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string() ?: return@withContext null
                val resp = gson.fromJson(respBody, TTSResponse::class.java)
                val audioBase64 = resp.choices.firstOrNull()?.message?.audio?.data
                if (audioBase64 != null) {
                    Base64.decode(audioBase64, Base64.DEFAULT)
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Streaming TTS (per docs: stream=true, audio.format=pcm16, 24kHz PCM16LE mono).
     * Calls [onChunk] with each decoded PCM fragment as it arrives — enables low-latency playback.
     * Returns true if the stream completed without error.
     */
    suspend fun synthesizeStream(
        text: String,
        voice: String = "mimo_default",
        onChunk: (ByteArray) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = TTSRequest(
                messages = listOf(
                    Message(role = "assistant", content = text)
                ),
                audio = AudioConfig(format = "pcm16", voice = voice)
            ).copy(stream = true)
            val json = gson.toJson(request)
            val body = json.toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(body)
                .build()

            val response = client.newCall(httpRequest).execute()
            if (!response.isSuccessful) return@withContext false
            val source = response.body?.source() ?: return@withContext false
            try {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    try {
                        val chunk = gson.fromJson(data, TTSStreamResponse::class.java)
                        val audioB64 = chunk.choices?.firstOrNull()?.delta?.audio?.data
                        if (!audioB64.isNullOrEmpty()) {
                            onChunk(Base64.decode(audioB64, Base64.DEFAULT))
                        }
                    } catch (_: Exception) {}
                }
                true
            } finally {
                response.close()
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun playPcm16(pcmData: ByteArray) {
            val sampleRate = 24000
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(pcmData.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(pcmData, 0, pcmData.size)
            audioTrack.play()

            audioTrack.setNotificationMarkerPosition(pcmData.size / 2)
            audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {
                    track.stop()
                    track.release()
                }
                override fun onPeriodicNotification(track: AudioTrack) {}
            })
        }
    }
}

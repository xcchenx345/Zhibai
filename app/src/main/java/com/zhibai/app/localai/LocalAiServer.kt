package com.zhibai.app.localai

import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.llm.LlmSession
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal OpenAI-compatible HTTP server bound to 127.0.0.1, so the existing
 * MiMoApiService chat pipeline (streaming SSE, structured tool JSON, agent
 * rounds) works unchanged with baseUrl = http://127.0.0.1:8620/v1.
 *
 * Implemented on plain ServerSocket — no dependencies (gradle is frozen).
 * Endpoints: GET /v1/models, POST /v1/chat/completions (stream + non-stream).
 * Requests are handled serially on the accept thread; LlmSession also
 * synchronizes internally, so one generation runs at a time.
 */
class LocalAiServer(
    private val sessionProvider: () -> LlmSession?,
    private val modelName: String,
    private val port: Int = PORT,
) {
    @Volatile
    var isRunning = false
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (isRunning) return
        val ss = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        isRunning = true
        acceptThread = Thread({
            while (isRunning) {
                val socket = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    handle(socket)
                } catch (_: Exception) {
                    // one bad request must not kill the loop
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
            isRunning = false
        }, "local-ai-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
    }

    // ---------------------------------------------------------------- routing

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // Read headers byte-wise up to \r\n\r\n (keeps the body stream aligned).
        val headerBuf = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return
            headerBuf.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val headerText = String(headerBuf.toByteArray(), Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 2) return
        val method = requestLine[0]
        val path = requestLine[1].substringBefore('?')

        val contentLength = lines.drop(1).firstNotNullOfOrNull { line ->
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                line.substring(idx + 1).trim().toLongOrNull()
            } else null
        } ?: 0L

        val body = ByteArray(contentLength.toInt().coerceAtMost(MAX_BODY_BYTES))
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }

        when {
            method == "GET" && path == "/v1/models" ->
                writeJson(output, 200, modelsJson())
            method == "POST" && path == "/v1/chat/completions" ->
                handleChat(output, String(body, 0, read, Charsets.UTF_8))
            else ->
                writeJson(output, 404, """{"error":{"message":"not found: $path"}}""")
        }
    }

    private fun modelsJson(): String {
        val data = JsonObject().apply {
            addProperty("id", modelName)
            addProperty("object", "model")
            addProperty("created", 0)
            addProperty("owned_by", "local-mnn")
        }
        val root = JsonObject().apply {
            addProperty("object", "list")
            add("data", com.google.gson.JsonArray().apply { add(data) })
        }
        return root.toString()
    }

    private fun handleChat(output: java.io.OutputStream, body: String) {
        val session = sessionProvider()
        if (session == null || !session.isLoaded) {
            writeJson(output, 503, """{"error":{"message":"local model is not loaded yet"}}""")
            return
        }
        val request = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            writeJson(output, 400, """{"error":{"message":"bad request json: ${e.message?.take(120)}}"}""")
            return
        }

        val messages = request.getAsJsonArray("messages") ?: run {
            writeJson(output, 400, """{"error":{"message":"messages missing"}}""")
            return
        }
        val history = messages.mapNotNull { el ->
            val m = el.asJsonObject
            val role = m.get("role")?.asString ?: return@mapNotNull null
            val content = contentToText(m.get("content"))
            role to content
        }
        val stream = request.get("stream")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        val created = System.currentTimeMillis() / 1000
        val id = "chatcmpl-local-$created"

        if (!stream) {
            val full = StringBuilder()
            try {
                session.submitFullHistory(history, object : GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean {
                        progress?.let { full.append(it) }
                        return false
                    }
                })
            } catch (e: Exception) {
                writeJson(output, 500, """{"error":{"message":"generation failed: ${e.message?.take(200)}}"}""")
                return
            }
            writeJson(output, 200, completionJson(id, created, full.toString()))
            return
        }

        // Streaming: headers first, then SSE frames until [DONE].
        try {
            writeSseHeaders(output)
            val listener = object : GenerateProgressListener {
                override fun onProgress(progress: String?): Boolean {
                    if (progress == null) return false
                    return try {
                        writeSse(output, deltaJson(id, created, progress, null))
                        false
                    } catch (_: Exception) {
                        true // client hung up — abort generation
                    }
                }
            }
            session.submitFullHistory(history, listener)
            writeSse(output, deltaJson(id, created, "", "stop"))
            writeSse(output, "[DONE]")
            output.flush()
        } catch (e: Exception) {
            // Generation blew up mid-stream: close the stream cleanly so the
            // client's collector finishes instead of hanging on a dead socket.
            try {
                writeSse(output, deltaJson(id, created, "", "stop"))
                writeSse(output, "[DONE]")
                output.flush()
            } catch (_: Exception) {}
        }
    }

    // --------------------------------------------------------------- JSON bits

    private fun contentToText(content: com.google.gson.JsonElement?): String = when {
        content == null || content.isJsonNull -> ""
        content.isJsonPrimitive -> content.asString
        content.isJsonArray -> content.asJsonArray.joinToString("") { item ->
            val obj = item.asJsonObject
            when (obj.get("type")?.asString) {
                "text" -> obj.get("text")?.asString ?: ""
                else -> ""
            }
        }
        else -> content.toString()
    }

    private fun deltaJson(id: String, created: Long, delta: String, finish: String?): String {
        val deltaObj = JsonObject().apply { addProperty("content", delta) }
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("delta", deltaObj)
            add("finish_reason", if (finish != null)
                com.google.gson.JsonPrimitive(finish) else com.google.gson.JsonNull.INSTANCE)
        }
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion.chunk")
            addProperty("created", created)
            addProperty("model", modelName)
            add("choices", com.google.gson.JsonArray().apply { add(choice) })
        }.toString()
    }

    private fun completionJson(id: String, created: Long, content: String): String {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", content)
        }
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("message", message)
            addProperty("finish_reason", "stop")
        }
        val usage = JsonObject().apply {
            addProperty("prompt_tokens", 0)
            addProperty("completion_tokens", 0)
            addProperty("total_tokens", 0)
        }
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion")
            addProperty("created", created)
            addProperty("model", modelName)
            add("choices", com.google.gson.JsonArray().apply { add(choice) })
            add("usage", usage)
        }.toString()
    }

    private fun writeJson(output: java.io.OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${statusReason(status)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun writeSseHeaders(output: java.io.OutputStream) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun writeSse(output: java.io.OutputStream, data: String) {
        output.write("data: $data\n\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun statusReason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    companion object {
        const val PORT = 8620
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}

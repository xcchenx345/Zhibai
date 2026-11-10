package com.zhibai.app.localai

import android.content.Context
import com.alibaba.mnnllm.android.llm.LlmSession
import java.io.File

/**
 * Lifecycle for the built-in local LLM: loads the MNN model once and keeps the
 * localhost OpenAI server alive while local AI is enabled.
 *
 * Model layout: <filesDir>/localai/models/<displayName>/  (see ModelDownloader)
 * mmap scratch: <filesDir>/localai/mmap/
 */
object LocalAiManager {
    const val LOCAL_BASE_URL = "http://127.0.0.1:${LocalAiServer.PORT}/v1"
    const val LOCAL_API_KEY = "local"

    @Volatile
    private var session: LlmSession? = null

    @Volatile
    private var server: LocalAiServer? = null

    @Volatile
    var lastError: String? = null
        private set

    val isReady: Boolean
        get() = session?.isLoaded == true && server?.isRunning == true

    /** Root of all local-ai data. */
    fun rootDir(context: Context): File = File(context.filesDir, "localai")

    fun modelsDir(context: Context): File = File(rootDir(context), "models")

    fun modelDir(context: Context, displayName: String): File =
        File(modelsDir(context), displayName)

    /**
     * Load the model (if needed) and start the server. Idempotent; blocking —
     * call from a background thread. On failure [lastError] is set and nothing
     * is started.
     */
    @Synchronized
    fun ensureStarted(context: Context, displayName: String) {
        if (isReady) return
        lastError = null
        try {
            val dir = modelDir(context, displayName)
            val configFile = File(dir, "config.json")
            require(configFile.isFile) {
                "model not installed: $displayName (missing config.json)"
            }
            val current = session
            if (current == null || !current.isLoaded) {
                current?.release()
                val mmap = File(rootDir(context), "mmap").apply { mkdirs() }
                val loaded = LlmSession(configFile.absolutePath, mmap.absolutePath)
                loaded.load()
                session = loaded
            }
            if (server == null || !server!!.isRunning) {
                // Best-effort context length from config.json (keys vary by
                // export); used by the server to trim history for the 7B.
                val contextLength = try {
                    val cfg = configFile.readText()
                    Regex("\"[^\"]*[cC]ontext[^\"]*\"\\s*:\\s*(\\d+)")
                        .find(cfg)?.groupValues?.get(1)?.toIntOrNull()
                        ?.let { if (it in 1024..262144) it else 8192 }
                        ?: 8192
                } catch (_: Exception) {
                    8192
                }
                server = LocalAiServer({ session }, displayName, contextLength)
                    .also { it.start() }
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            // Roll back partial state so a retry starts clean.
            runCatching { server?.stop() }
            runCatching { session?.release() }
            server = null
            session = null
        }
    }

    /** Stop server and unload the model (free ~model-size RAM). */
    @Synchronized
    fun stop() {
        runCatching { server?.stop() }
        runCatching { session?.release() }
        server = null
        session = null
    }

    /**
     * Turn everything off and delete ALL local-ai data (installed models +
     * mmap scratch). Frees several GB of internal storage.
     */
    fun deleteData(context: Context) {
        stop()
        runCatching { rootDir(context).deleteRecursively() }
    }
}

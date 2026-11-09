// Thin JNI binding for the prebuilt libmnnllmapp.so (extracted from the official
// MNN Chat APK, github.com/alibaba/MNN apps/Android/MnnLlmChat).
//
// Ported from RikkaHub-style vendoring: only the pieces MiMoApp needs. The .so
// resolves natives via Java_com_alibaba_mnnllm_android_llm_LlmSession_* SHORT
// symbol names, so the package/class name and every external fun NAME must stay
// exactly as-is — signatures only matter for argument marshalling.
//
// configPath points at the model directory's config.json (same as the official
// LlmService: "$modelDir/config.json").
package com.alibaba.mnnllm.android.llm

import java.io.File

class LlmSession(
    private val configPath: String,
    private val mmapDir: String = "",
) {
    private var nativePtr: Long = 0

    val isLoaded: Boolean get() = nativePtr != 0L

    /**
     * Load the model. [history] is optional prior turns (usually null — callers
     * pass full history per request via [submitFullHistory]).
     *
     * @throws IllegalStateException when native init returns 0 (bad model dir,
     * missing config.json, OOM, unsupported backend…)
     */
    @Synchronized
    fun load(history: List<String>? = null) {
        check(nativePtr == 0L) { "model already loaded" }
        val configFile = File(configPath)
        require(configFile.isFile) { "config not found: $configPath" }
        // param3: the merged ModelConfig JSON the official app Gson-serializes
        // from ModelConfig.loadDefaultConfig(configPath). The shipped config.json
        // IS the native config format; pass it raw (native fills its own defaults).
        val mergedConfig = configFile.readText()
        // param4: runtime map — same keys the official app builds.
        val runtimeConfig = buildString {
            append("{\"is_r1\":false,")
            append("\"mmap_dir\":")
            append('"').append(mmapDir.replace("\"", "\\\"")).append('"').append(',')
            append("\"keep_history\":false}")
        }
        nativePtr = initNative(configPath, history, mergedConfig, runtimeConfig)
        if (nativePtr == 0L) {
            throw IllegalStateException("MNN model load failed (native init returned 0): $configPath")
        }
    }

    /**
     * Run one generation with the full chat history.
     * [history] pairs are (role, content): "system" | "user" | "assistant".
     * [listener].onProgress receives text deltas; null signals completion;
     * return true from onProgress to abort generation.
     */
    @Synchronized
    fun submitFullHistory(
        history: List<Pair<String, String>>,
        listener: GenerateProgressListener,
    ): HashMap<String, Any> {
        check(nativePtr != 0L) { "model not loaded" }
        val androidHistory = history.map { android.util.Pair(it.first, it.second) }
        return submitFullHistoryNative(nativePtr, androidHistory, listener)
    }

    @Synchronized
    fun reset() {
        if (nativePtr != 0L) resetNative(nativePtr)
    }

    @Synchronized
    fun release() {
        if (nativePtr != 0L) {
            releaseNative(nativePtr)
            nativePtr = 0
        }
    }

    fun updateMaxNewTokens(maxNewTokens: Int) {
        if (nativePtr != 0L) updateMaxNewTokensNative(nativePtr, maxNewTokens)
    }

    private external fun initNative(
        configPath: String?,
        history: List<String>?,
        mergedConfigStr: String?,
        configJsonStr: String?,
    ): Long

    private external fun submitFullHistoryNative(
        nativePtr: Long,
        history: List<android.util.Pair<String, String>>?,
        progressListener: GenerateProgressListener,
    ): HashMap<String, Any>

    private external fun resetNative(nativePtr: Long)

    private external fun releaseNative(nativePtr: Long)

    private external fun updateMaxNewTokensNative(llmPtr: Long, maxNewTokens: Int)

    private external fun updateSystemPromptNative(llmPtr: Long, systemPrompt: String)

    companion object {
        init {
            System.loadLibrary("mnnllmapp")
        }
    }
}

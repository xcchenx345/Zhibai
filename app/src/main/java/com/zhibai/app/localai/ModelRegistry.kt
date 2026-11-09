package com.zhibai.app.localai

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** One selectable local model (MNN format), as reported by the cloud registry. */
data class LocalModel(
    /** Registry id, e.g. "taobao-mnn/MiMo-7B-RL-MNN" (HuggingFace form). */
    val id: String,
    val displayName: String,
    /** Parameter count in billions parsed from the name (7.0 for "7B"); 0 = unknown. */
    val paramsB: Double,
    /** File paths inside the repo, needed for download. */
    val files: List<String>,
) {
    /** ModelScope org for downloads: HF "taobao-mnn/X" ↔ ModelScope "MNN/X". */
    val modelScopePath: String
        get() = if (id.startsWith("taobao-mnn/")) "MNN/" + id.substring("taobao-mnn/".length) else id
}

/**
 * Cloud registry of MiMo-series MNN models.
 *
 * The list is fetched live from HuggingFace's public models API (hf-mirror.com
 * first — this device is in China — huggingface.co as fallback), filtered to the
 * MiMo series published by the MNN/taobao-mnn orgs. Device tier gates which
 * models may be selected (LOW ≤1B, MID ≤3B, HIGH ≤7B — see [LocalAiSupport]).
 */
object ModelRegistry {
    private val HOSTS = listOf("https://hf-mirror.com", "https://huggingface.co")
    private val gson = Gson()

    /** Max parameter count (billions) a device tier may run. */
    fun tierCapB(tier: LocalAiSupport.Tier): Double = when (tier) {
        LocalAiSupport.Tier.LOW -> 1.0
        LocalAiSupport.Tier.MID -> 3.0
        LocalAiSupport.Tier.HIGH -> 100.0 // 7B and whatever the series gains later
    }

    fun allowedForTier(tier: LocalAiSupport.Tier, model: LocalModel): Boolean {
        if (model.paramsB <= 0) return true // unknown size — let it through
        return model.paramsB <= tierCapB(tier)
    }

    /** Default pick: MiMo-7B-RL (chat/RL checkpoint) when the tier allows it. */
    fun defaultModel(models: List<LocalModel>, tier: LocalAiSupport.Tier): LocalModel? {
        val allowed = models.filter { allowedForTier(tier, it) }
        return allowed.firstOrNull { it.displayName.contains("MiMo-7B-RL", ignoreCase = true) }
            ?: allowed.sortedByDescending { it.paramsB }.firstOrNull()
            ?: models.firstOrNull { it.displayName.contains("MiMo-7B-RL", ignoreCase = true) }
    }

    /** Fetch MiMo-series models; throws with the last network error when all hosts fail. */
    suspend fun fetch(): List<LocalModel> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (host in HOSTS) {
            try {
                val url = URL("$host/api/models?author=taobao-mnn&limit=200")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                val root = JsonParser.parseString(body).asJsonArray
                val models = root.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val id = obj.get("modelId")?.asString ?: return@mapNotNull null
                    if (!id.contains("mimo", ignoreCase = true)) return@mapNotNull null
                    val files = obj.getAsJsonArray("siblings")
                        ?.mapNotNull { s -> s.asJsonObject.get("rfilename")?.asString }
                        ?: emptyList()
                    if (files.isEmpty()) return@mapNotNull null
                    val name = id.substringAfter("/")
                    LocalModel(
                        id = id,
                        displayName = name.removeSuffix("-MNN"),
                        paramsB = parseParamsB(name),
                        files = files,
                    )
                }.sortedByDescending { it.paramsB }
                if (models.isNotEmpty()) return@withContext models
                lastError = IllegalStateException("registry returned no MiMo models")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("model registry unavailable")
    }

    /** "MiMo-7B-RL-MNN" → 7.0 ; "Qwen2.5-0.5B" → 0.5 ; no match → 0. */
    internal fun parseParamsB(name: String): Double {
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*B\\b", RegexOption.IGNORE_CASE).find(name) ?: return 0.0
        return m.groupValues[1].toDoubleOrNull() ?: 0.0
    }
}

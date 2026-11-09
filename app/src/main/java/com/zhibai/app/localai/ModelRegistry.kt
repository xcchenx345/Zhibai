package com.zhibai.app.localai

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
        // No over-tier fallback on purpose: a LOW/MID device with no allowed model
        // gets null so the UI shows an empty selection instead of a 7B it can't run.
    }

    /**
     * File layout of the official taobao-mnn MiMo MNN exports (verified against
     * the live API 2026-11). Used when the per-model file list can't be fetched
     * and as the offline seed.
     */
    private val MIMO_MNN_FILES = listOf(
        "config.json",
        "embeddings_bf16.bin",
        "llm.mnn",
        "llm.mnn.json",
        "llm.mnn.weight",
        "llm_config.json",
        "tokenizer.txt",
    )

    /** Offline seed — the official MiMo MNN exports, so the list NEVER fails hard. */
    private fun seedModels(): List<LocalModel> =
        listOf("MiMo-7B-RL-MNN", "MiMo-7B-RL-Zero-MNN", "MiMo-7B-SFT-MNN", "MiMo-7B-Base-MNN")
            .map { name ->
                LocalModel(
                    id = "taobao-mnn/$name",
                    displayName = name.removeSuffix("-MNN"),
                    paramsB = parseParamsB(name),
                    files = MIMO_MNN_FILES,
                )
            }

    private fun getJson(urlStr: String): com.google.gson.JsonElement {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code ← $urlStr")
            return conn.inputStream.use {
                JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8))
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Fetch MiMo-series models.
     *
     * hf-mirror.com first (this device is in China; huggingface.co is DNS-poisoned
     * and only burns a 15s timeout). The LIST endpoint returns only `id` — file
     * lists come from the per-model endpoint; if that fails we fall back to the
     * known MNN file layout. If every host fails, return the offline seed so the
     * picker always has a list.
     */
    suspend fun fetch(): List<LocalModel> = withContext(Dispatchers.IO) {
        for (host in HOSTS) {
            try {
                val arr = getJson("$host/api/models?author=taobao-mnn&limit=200").asJsonArray
                val ids = arr.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val id = obj.get("id")?.asString ?: obj.get("modelId")?.asString
                        ?: return@mapNotNull null
                    if (id.contains("mimo", ignoreCase = true)) id else null
                }.distinct()
                if (ids.isEmpty()) continue

                val models = ids.map { id ->
                    val name = id.substringAfter("/")
                    var files = try {
                        getJson("$host/api/models/$id").asJsonObject
                            .getAsJsonArray("siblings")
                            ?.mapNotNull { s -> s.asJsonObject.get("rfilename")?.asString }
                            ?.filterNot { it.startsWith(".") || it.equals("README.md", true) }
                            ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (files.isEmpty() && name.endsWith("-MNN")) files = MIMO_MNN_FILES
                    LocalModel(
                        id = id,
                        displayName = name.removeSuffix("-MNN"),
                        paramsB = parseParamsB(name),
                        files = files,
                    )
                }.filter { it.files.isNotEmpty() }
                    .sortedByDescending { it.paramsB }

                if (models.isNotEmpty()) return@withContext models
            } catch (_: Exception) {
                // try next host
            }
        }
        // Everything unreachable (or the mirror broke): ship the known list so the
        // user still sees models; download errors surface later, per file.
        seedModels()
    }

    /** "MiMo-7B-RL-MNN" → 7.0 ; "Qwen2.5-0.5B" → 0.5 ; no match → 0. */
    internal fun parseParamsB(name: String): Double {
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*B\\b", RegexOption.IGNORE_CASE).find(name) ?: return 0.0
        return m.groupValues[1].toDoubleOrNull() ?: 0.0
    }
}

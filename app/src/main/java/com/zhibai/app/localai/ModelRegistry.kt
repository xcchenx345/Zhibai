package com.zhibai.app.localai

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** One file of a model repo, with size + sha256 when the source provides them. */
data class ModelFile(
    val path: String,
    val size: Long = 0L,
    val sha256: String? = null,
)

/** One selectable local model (MNN format), as reported by the cloud registry. */
data class LocalModel(
    /** Registry id, e.g. "taobao-mnn/MiMo-7B-RL-MNN" (HuggingFace form). */
    val id: String,
    val displayName: String,
    /** Parameter count in billions parsed from the name (7.0 for "7B"); 0 = unknown. */
    val paramsB: Double,
    /** Files needed for download (sizes/sha256 when available — drives progress + verify). */
    val files: List<ModelFile>,
) {
    /** ModelScope org for downloads: HF "taobao-mnn/X" ↔ ModelScope "MNN/X". */
    val modelScopePath: String
        get() = if (id.startsWith("taobao-mnn/")) "MNN/" + id.substring("taobao-mnn/".length) else id
}

/**
 * Cloud registry of MiMo-series MNN models.
 *
 * Model ids come from the HuggingFace API (hf-mirror.com first — this device is
 * in China; huggingface.co is DNS-poisoned). File lists prefer ModelScope's
 * `repo/files?Recursive=1` — the endpoint MNN Chat's own downloader uses — which
 * additionally provides per-file Size and Sha256 for byte-accurate progress and
 * verification. Fallbacks: HF per-model siblings, then the offline seed, so the
 * picker always has a list.
 */
object ModelRegistry {
    private val HOSTS = listOf("https://hf-mirror.com", "https://huggingface.co")
    private const val MS_API = "https://modelscope.cn/api/v1/models"

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
     * the live APIs 2026-11). Last-resort fallback when no host answers.
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
                    files = MIMO_MNN_FILES.map { ModelFile(it) },
                )
            }

    private fun getJson(urlStr: String): com.google.gson.JsonElement {
        System.setProperty("java.net.preferIPv4Stack", "true")
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
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
     * ModelScope file list — the endpoint MNN Chat's MsApiService uses.
     * Returns null when unreachable; caller falls back to HF siblings.
     */
    private fun fetchMsFiles(modelScopePath: String): List<ModelFile>? = try {
        val root = getJson("$MS_API/$modelScopePath/repo/files?Recursive=1")
        val files = root.asJsonObject.getAsJsonObject("Data")?.getAsJsonArray("Files")
            ?: return null
        files.mapNotNull { el ->
            val o = el.asJsonObject
            if (o.get("Type")?.asString == "tree") return@mapNotNull null
            val path = o.get("Path")?.asString ?: return@mapNotNull null
            if (path.startsWith(".") || path.equals("README.md", true)) return@mapNotNull null
            ModelFile(
                path = path,
                size = o.get("Size")?.asLong ?: 0L,
                sha256 = o.get("Sha256")?.asString,
            )
        }.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** HF per-model siblings (no sizes/sha). */
    private fun fetchHfFiles(host: String, id: String): List<ModelFile>? = try {
        getJson("$host/api/models/$id").asJsonObject
            .getAsJsonArray("siblings")
            ?.mapNotNull { s -> s.asJsonObject.get("rfilename")?.asString }
            ?.filterNot { it.startsWith(".") || it.equals("README.md", true) }
            ?.map { ModelFile(it) }
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    suspend fun fetch(): List<LocalModel> = withContext(Dispatchers.IO) {
        var ids: List<String>? = null
        for (host in HOSTS) {
            try {
                val arr = getJson("$host/api/models?author=taobao-mnn&limit=200").asJsonArray
                val found = arr.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val id = obj.get("id")?.asString ?: obj.get("modelId")?.asString
                        ?: return@mapNotNull null
                    if (id.contains("mimo", ignoreCase = true)) id else null
                }.distinct()
                if (found.isNotEmpty()) {
                    ids = found
                    break
                }
            } catch (_: Exception) {
                // try next host
            }
        }

        if (ids == null) return@withContext seedModels()

        ids!!.map { id ->
            val name = id.substringAfter("/")
            val msPath =
                if (id.startsWith("taobao-mnn/")) "MNN/" + id.substringAfter("taobao-mnn/") else id
            // ModelScope first (sizes + sha256 — MNN Chat's proven endpoint),
            // then HF siblings, then the known layout.
            val files = fetchMsFiles(msPath)
                ?: HOSTS.firstNotNullOfOrNull { host -> fetchHfFiles(host, id) }
                ?: if (name.endsWith("-MNN")) MIMO_MNN_FILES.map { ModelFile(it) } else emptyList()
            LocalModel(
                id = id,
                displayName = name.removeSuffix("-MNN"),
                paramsB = parseParamsB(name),
                files = files,
            )
        }.filter { it.files.isNotEmpty() }
            .sortedByDescending { it.paramsB }
            .ifEmpty { seedModels() }
    }

    /** "MiMo-7B-RL-MNN" → 7.0 ; "Qwen2.5-0.5B" → 0.5 ; no match → 0. */
    internal fun parseParamsB(name: String): Double {
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*B\\b", RegexOption.IGNORE_CASE).find(name) ?: return 0.0
        return m.groupValues[1].toDoubleOrNull() ?: 0.0
    }
}

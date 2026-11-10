package com.zhibai.app.localai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Model downloader ported from MNN Chat's open-source model_downloader
 * framework (github.com/alibaba/MNN, apps/frameworks/model_downloader —
 * ModelFileDownloader/MsModelDownloader, Apache-2.0), adapted to our flat
 * directory layout:
 *
 *  - file sizes/sha256 come from ModelScope's repo/files API (no HEAD probes)
 *  - manual redirect handling (301..308 → Location) with Range resume against
 *    the final URL — .part files survive failures and app restarts
 *  - 10 retries per source with 1s backoff, then failover to the next source
 *  - sha256 + size validation before a .part is promoted to the final file
 *  - byte-delta progress with a 1s speed window (MNN Chat's DownloadInfo style)
 *
 * Sources stay CN-first (ModelScope API FilePath → resolve → hf-mirror →
 * huggingface.co last, short connect cap — the poisoned host).
 */
object ModelDownloader {
    data class Progress(
        val filesDone: Int,
        val filesTotal: Int,
        val bytesDone: Long,
        /** null when the source didn't report sizes (progress falls back to file counts). */
        val bytesTotal: Long?,
        val currentFile: String,
        /** Smoothed download speed in bytes/sec; 0 while idle. */
        val speedBps: Long,
    )

    // MNN Chat's client: redirects handled manually so Range survives; 30s
    // connect. Read timeout raised for large files on mobile networks.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // The DNS-poisoned host gets a short connect window (their30s would stall
    // the failover chain for nothing).
    private val poisonedClient: OkHttpClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun clientFor(urlStr: String): OkHttpClient =
        if (try { URL(urlStr).host } catch (_: Exception) { "" } == "huggingface.co") poisonedClient
        else client

    private const val MAX_RETRY = 10 // their maxRetry

    /** True when every file exists with the advertised size (or non-zero if unknown). */
    fun isInstalled(model: LocalModel, destDir: File): Boolean =
        model.files.isNotEmpty() && model.files.all { f ->
            val file = File(destDir, f.path)
            file.isFile && file.length() > 0 && (f.size <= 0 || file.length() == f.size)
        }

    suspend fun install(
        model: LocalModel,
        destDir: File,
        onProgress: (Progress) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        System.setProperty("java.net.preferIPv4Stack", "true")
        destDir.mkdirs()
        val filesTotal = model.files.size
        val bytesTotal = model.files.sumOf { it.size }.takeIf { it > 0 }

        var filesDone = 0
        var bytesDone = 0L
        var currentFile = ""
        var currentFileBytes = 0L
        var speedBps = 0L
        var windowStart = System.currentTimeMillis()
        var windowBytes = 0L
        var lastEmitMs = 0L

        fun emit(force: Boolean = false) {
            val now = System.currentTimeMillis()
            // Throttle like MNN Chat's lastProgressUpdateTime — 64KB deltas
            // would otherwise spam the island notification.
            if (!force && now - lastEmitMs < 250) return
            lastEmitMs = now
            onProgress(
                Progress(
                    filesDone = filesDone,
                    filesTotal = filesTotal,
                    bytesDone = bytesDone + currentFileBytes,
                    bytesTotal = bytesTotal,
                    currentFile = currentFile,
                    speedBps = speedBps,
                )
            )
        }

        // Seed progress from files already on disk (resume from a previous run).
        for (f in model.files) {
            val existing = File(destDir, f.path)
            if (existing.isFile && existing.length() > 0 &&
                (f.size <= 0 || existing.length() == f.size)
            ) {
                filesDone++
                bytesDone += existing.length()
            }
        }
        emit(force = true)

        for (file in model.files) {
            coroutineContext.ensureActive()
            val target = File(destDir, file.path)
            if (target.isFile && target.length() > 0 &&
                (file.size <= 0 || target.length() == file.size)
            ) {
                continue // already counted in the seed
            }
            target.parentFile?.mkdirs()
            val part = File(destDir, "${file.path}.part")
            currentFile = file.path
            currentFileBytes = if (part.exists()) part.length() else 0L
            windowStart = System.currentTimeMillis()
            windowBytes = 0L
            speedBps = 0L
            emit(force = true)

            downloadOneFile(model, file, part) { fileBytes ->
                val now = System.currentTimeMillis()
                if (fileBytes > currentFileBytes) windowBytes += fileBytes - currentFileBytes
                currentFileBytes = fileBytes
                val elapsed = now - windowStart
                if (elapsed >= 1000) {
                    speedBps = windowBytes * 1000 / elapsed
                    windowStart = now
                    windowBytes = 0L
                }
                emit()
            }

            // Promote .part → final (downloadOneFile already validated).
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            filesDone++
            bytesDone += target.length()
            currentFileBytes = 0L
            speedBps = 0L
            emit(force = true)
        }

        if (!isInstalled(model, destDir)) {
            throw IllegalStateException("model install incomplete: ${model.displayName}")
        }
    }

    /**
     * One file, all sources: failover keeps the .part so a later source can
     * RESUME where the previous one died (Range works across hosts).
     */
    private fun downloadOneFile(
        model: LocalModel,
        file: ModelFile,
        part: File,
        onFileBytes: (Long) -> Unit,
    ) {
        val errors = mutableListOf<String>()
        for (urlStr in sourceUrls(model, file.path)) {
            val host = try { URL(urlStr).host } catch (_: Exception) { urlStr }
            try {
                downloadFromSource(urlStr, file, part, onFileBytes)
                if (validate(file, part)) {
                    return
                }
                errors += "$host: 校验失败(size/sha256)"
                part.delete() // corrupt content — restart, don't resume garbage
            } catch (e: Exception) {
                // Keep .part: the next source resumes with Range.
                errors += "$host: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("[${file.path}] " + errors.joinToString("；"))
    }

    /** Their flow: probe for redirect → chunked Range GET with 10 retries / 1s sleep. */
    private fun downloadFromSource(
        urlStr: String,
        file: ModelFile,
        part: File,
        onFileBytes: (Long) -> Unit,
    ) {
        val finalUrl = probeRedirect(urlStr)
        var lastError: Exception? = null
        for (attempt in 0 until MAX_RETRY) {
            if (attempt > 0) Thread.sleep(1_000)
            val downloaded = if (part.exists()) part.length() else 0L
            if (file.size > 0 && downloaded >= file.size) {
                lastError = null
                break // complete — validate() decides
            }
            try {
                chunkGet(finalUrl, file, part, onFileBytes)
                lastError = null
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lastError != null) throw lastError
    }

    /**
     * Single GET to discover the final URL (their probe step): OkHttp has
     * redirects disabled, so a 3xx hands us Location — resolved against the
     * original URL because hf-mirror returns RELATIVE locations.
     */
    private fun probeRedirect(urlStr: String): String {
        val req = Request.Builder().url(urlStr).get().build()
        clientFor(urlStr).newCall(req).execute().use { resp ->
            if (resp.code in 301..308) {
                val loc = resp.header("Location")
                    ?: throw IOException("HTTP ${resp.code} without Location")
                return URL(URL(urlStr), loc).toString()
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return urlStr
        }
    }

    /** Range-resume append into .part; Accept-Encoding identity for byte accuracy. */
    private fun chunkGet(
        urlStr: String,
        file: ModelFile,
        part: File,
        onFileBytes: (Long) -> Unit,
    ) {
        var downloaded = if (part.exists()) part.length() else 0L
        val builder = Request.Builder().url(urlStr).get()
            .header("Accept-Encoding", "identity")
        if (downloaded > 0) builder.header("Range", "bytes=$downloaded-")

        clientFor(urlStr).newCall(builder.build()).execute().use { resp ->
            when {
                resp.code == 416 -> return // server says range unsatisfiable → already complete
                resp.code in 301..308 -> {
                    // Redirect after the probe (CDN rotation) — follow once here.
                    val loc = resp.header("Location") ?: throw IOException("HTTP ${resp.code}")
                    val next = URL(URL(urlStr), loc).toString()
                    chunkGet(next, file, part, onFileBytes)
                    return
                }
                !resp.isSuccessful -> throw IOException("HTTP ${resp.code}")
            }
            // Server ignored our Range (200 instead of 206) → restart the file.
            if (downloaded > 0 && resp.code == 200) {
                part.delete()
                downloaded = 0L
            }
            val body = resp.body ?: throw IOException("empty body")
            body.byteStream().use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(downloaded)
                    var pos = downloaded
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        raf.write(buffer, 0, n)
                        pos += n
                        onFileBytes(pos)
                    }
                }
            }
        }
    }

    /** Size check (when known) + sha256 check (when known) — MNN Chat's validate(). */
    private fun validate(file: ModelFile, part: File): Boolean {
        if (!part.isFile || part.length() == 0L) return false
        if (file.size > 0 && part.length() != file.size) return false
        val sha = file.sha256
        if (!sha.isNullOrEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha, ignoreCase = true)) return false
        }
        return true
    }

    /** Candidate direct-download URLs for one repo file, fastest source first. */
    private fun sourceUrls(model: LocalModel, path: String): List<String> = buildList {
        add("https://modelscope.cn/api/v1/models/${model.modelScopePath}/repo?FilePath=$path")
        add("https://modelscope.cn/models/${model.modelScopePath}/resolve/master/$path")
        add("https://hf-mirror.com/${model.id}/resolve/main/$path")
        // Poisoned in China — last resort, gets the short connect window via probe.
        add("https://huggingface.co/${model.id}/resolve/main/$path")
    }
}

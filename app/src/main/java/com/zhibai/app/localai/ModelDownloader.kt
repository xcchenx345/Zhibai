package com.zhibai.app.localai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads an MNN model directory file-by-file.
 *
 * Sources, fastest first: ModelScope API FilePath (domestic, tested working),
 * ModelScope resolve URL, hf-mirror.com, then huggingface.co as a last resort
 * (DNS-poisoned in China — kept with a short connect timeout only).
 *
 * No HEAD/Content-Length probing: it burned 3x15s per file on bad networks and
 * progress is driven by file counts anyway. On failure the exception carries
 * ONE line per source so the UI shows why each host failed.
 *
 * Files are written to <destDir>/<file>; a file is skipped when it already
 * exists with a non-zero size (resume-friendly re-install).
 */
object ModelDownloader {
    data class Progress(
        val filesDone: Int,
        val filesTotal: Int,
        val bytesDone: Long,
        val currentFile: String,
    )

    /** True when every registry file is present with a non-zero size. */
    fun isInstalled(model: LocalModel, destDir: File): Boolean =
        model.files.isNotEmpty() && model.files.all { f ->
            val file = File(destDir, f)
            file.isFile && file.length() > 0
        }

    suspend fun install(
        model: LocalModel,
        destDir: File,
        onProgress: (Progress) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        var bytesDone = 0L
        var done = 0
        for (path in model.files) {
            coroutineContext.ensureActive()
            val target = File(destDir, path)
            target.parentFile?.mkdirs()
            if (target.isFile && target.length() > 0) {
                bytesDone += target.length()
                done++
                onProgress(Progress(done, model.files.size, bytesDone, path))
                continue
            }
            val tmp = File(destDir, "$path.part")
            downloadFile(model, path, tmp) { readNow ->
                onProgress(Progress(done, model.files.size, bytesDone + readNow, path))
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            bytesDone += target.length()
            done++
            onProgress(Progress(done, model.files.size, bytesDone, path))
        }
        if (!isInstalled(model, destDir)) {
            throw IllegalStateException("model install incomplete: ${model.displayName}")
        }
    }

    private fun downloadFile(
        model: LocalModel,
        path: String,
        target: File,
        onBytes: (Long) -> Unit,
    ) {
        // Some mobile networks have broken IPv6 routes and some WAFs flag the
        // default Dalvik UA — both showed up as timeout/reset on-device while
        // desktop curl (IPv4 + browser UA) got 200 from the same hosts.
        System.setProperty("java.net.preferIPv4Stack", "true")
        val errors = mutableListOf<String>()
        for (urlStr in sourceUrls(model, path)) {
            val host = try { URL(urlStr).host } catch (_: Exception) { urlStr }
            var lastMsg: String? = null
            var succeeded = false
            for (attempt in 0 until 2) {
                if (attempt > 0) Thread.sleep(1_500)
                try {
                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    // The poisoned host only ever gets a short connect window.
                    conn.connectTimeout = if (host == "huggingface.co") 8_000 else 20_000
                    conn.readTimeout = 60_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                    )
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        conn.disconnect()
                        lastMsg = "HTTP $code"
                        continue
                    }
                    conn.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var read: Int
                            var total = 0L
                            while (input.read(buffer).also { read = it } >= 0) {
                                output.write(buffer, 0, read)
                                total += read
                                onBytes(total)
                            }
                        }
                    }
                    conn.disconnect()
                    if (target.length() > 0) {
                        succeeded = true
                        break
                    }
                    lastMsg = "空文件"
                } catch (e: Exception) {
                    lastMsg = e.message ?: e.javaClass.simpleName
                    target.delete()
                }
            }
            if (succeeded) return
            errors += "$host: $lastMsg"
        }
        throw IllegalStateException("[$path] " + errors.joinToString("；"))
    }

    /** Candidate direct-download URLs for one repo file, fastest source first. */
    private fun sourceUrls(model: LocalModel, path: String): List<String> = buildList {
        add("https://modelscope.cn/api/v1/models/${model.modelScopePath}/repo?FilePath=$path")
        add("https://modelscope.cn/models/${model.modelScopePath}/resolve/master/$path")
        add("https://hf-mirror.com/${model.id}/resolve/main/$path")
        add("https://huggingface.co/${model.id}/resolve/main/$path")
    }
}

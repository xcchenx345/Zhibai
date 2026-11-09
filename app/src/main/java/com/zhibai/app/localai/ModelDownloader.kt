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
 * Sources, in order: ModelScope resolve URLs (domestic, fast — the MNN org
 * mirrors taobao-mnn), then hf-mirror.com, then huggingface.co. Files are
 * written to <destDir>/<file> with parent dirs created; a file is skipped when
 * it already exists with the advertised size (resume-friendly re-install).
 */
object ModelDownloader {
    data class Progress(
        val filesDone: Int,
        val filesTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long?, // null when the server omitted Content-Length
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
        var bytesTotal: Long? = null
        var done = 0
        for (path in model.files) {
            coroutineContext.ensureActive()
            val target = File(destDir, path)
            target.parentFile?.mkdirs()
            val expected = contentLengthOf(model, path)
            if (target.isFile && target.length() > 0 &&
                (expected == null || target.length() == expected)
            ) {
                bytesDone += target.length()
                done++
                onProgress(Progress(done, model.files.size, bytesDone, bytesTotal, path))
                continue
            }
            val tmp = File(destDir, "$path.part")
            var fileBytes = 0L
            downloadFile(model, path, tmp) { readNow ->
                fileBytes = readNow
                onProgress(
                    Progress(done, model.files.size, bytesDone + fileBytes, bytesTotal, path)
                )
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                // Cross-dir rename fallback (shouldn't happen — same parent)
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            bytesDone += target.length()
            done++
            onProgress(Progress(done, model.files.size, bytesDone, bytesTotal, path))
        }
        if (!isInstalled(model, destDir)) {
            throw IllegalStateException("model install incomplete: ${model.displayName}")
        }
    }

    /** Best-effort Content-Length probe (first source that answers). */
    private fun contentLengthOf(model: LocalModel, path: String): Long? =
        sourceUrls(model, path).firstNotNullOfOrNull { url ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.requestMethod = "HEAD"
                val len = if (conn.responseCode in 200..299) conn.contentLengthLong else -1L
                conn.disconnect()
                len.takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }
        }

    private fun downloadFile(
        model: LocalModel,
        path: String,
        target: File,
        onBytes: (Long) -> Unit,
    ) {
        var lastError: Exception? = null
        for (urlStr in sourceUrls(model, path)) {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    lastError = IllegalStateException("HTTP $code for $urlStr")
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
                if (target.length() > 0) return
                lastError = IllegalStateException("empty download: $path")
            } catch (e: Exception) {
                lastError = e
                target.delete()
            }
        }
        throw lastError ?: IllegalStateException("no source for $path")
    }

    /** Candidate direct-download URLs for one repo file, fastest source first. */
    private fun sourceUrls(model: LocalModel, path: String): List<String> = buildList {
        add("https://modelscope.cn/models/${model.modelScopePath}/resolve/master/$path")
        add("https://hf-mirror.com/${model.id}/resolve/main/$path")
        add("https://huggingface.co/${model.id}/resolve/main/$path")
    }
}

package com.zhibai.app.data.api

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Direct web search via cn.bing.com + plain-page fetcher.
 * Replaces the official MiMo web_search plugin for normal chat: pure HTTP,
 * needs only the install-time INTERNET permission (no runtime grants).
 */
object BingSearch {
    data class Result(val title: String, val url: String, val snippet: String)

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** Search cn.bing.com and parse the organic result blocks (class="b_algo"). */
    fun search(query: String, limit: Int = 8): List<Result> {
        val endpoint = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") +
            "&count=$limit&setlang=zh-hans"
        val html = get(endpoint)
        val results = mutableListOf<Result>()
        val seen = mutableSetOf<String>()
        val blockHead = Regex("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>")
        val snippetRe = Regex("<p[^>]*>([\\s\\S]*?)</p>")
        for (chunk in html.split("class=\"b_algo\"").drop(1)) {
            if (results.size >= limit) break
            val head = blockHead.find(chunk) ?: continue
            val rawHref = head.groupValues[1]
            val title = stripTags(head.groupValues[2])
            val snippet = snippetRe.find(chunk)?.let { stripTags(it.groupValues[1]) } ?: ""
            val url = unwrapBingUrl(rawHref)
            if (title.isBlank() || !url.startsWith("http") || !seen.add(url)) continue
            results.add(Result(title = title, url = url, snippet = snippet))
        }
        return results
    }

    /** Human-readable, numbered result block for the model (numbering continues across calls). */
    fun formatResults(query: String, results: List<Result>, startNo: Int = 1): String = buildString {
        append("Web search results for \"").append(query).append('"')
        if (startNo > 1) append(" (numbering continues)")
        append(":\n")
        results.forEachIndexed { i, r ->
            append(startNo + i).append(". ").append(r.title).append('\n')
            append("   URL: ").append(r.url).append('\n')
            append("   ").append(r.snippet).append('\n')
        }
    }

    /** Fetch a page and return readable text (HTML stripped). */
    fun fetch(url: String, maxLen: Int = 5000): String {
        val conn = open(url, connectMs = 10_000, readMs = 20_000)
        try {
            val type = conn.contentType ?: ""
            val bytes = conn.inputStream.readBytes().let { if (it.size > 2_000_000) it.copyOf(2_000_000) else it }
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(type)?.groupValues?.get(1)
            val raw = decode(bytes, charset)
            val text = if (type.contains("html", true) || type.contains("xml", true) || raw.contains("<html", true)) {
                stripHtml(raw)
            } else raw
            val trimmed = text.trim()
            return if (trimmed.length > maxLen) trimmed.take(maxLen) + "\n...(truncated)" else trimmed
        } finally {
            conn.disconnect()
        }
    }

    // ---- internals ----

    private fun open(url: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        return conn
    }

    private fun get(url: String): String {
        val conn = open(url, connectMs = 15_000, readMs = 20_000)
        try {
            val bytes = conn.inputStream.readBytes()
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(conn.contentType ?: "")?.groupValues?.get(1)
            return decode(bytes, charset)
        } finally {
            conn.disconnect()
        }
    }

    /** Decode with declared charset, else strict UTF-8 with GBK fallback (zh pages). */
    private fun decode(bytes: ByteArray, charsetName: String?): String {
        if (charsetName != null) {
            try { return String(bytes, Charset.forName(charsetName)) } catch (_: Exception) { /* fall through */ }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            try { String(bytes, Charset.forName("GBK")) } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
        }
    }

    /** Bing result links are often /ck/a?...&u=a1<base64url> redirects → real URL. */
    private fun unwrapBingUrl(url: String): String {
        if (!url.contains("bing.com/ck/a")) return url
        return try {
            val query = URL(url).query ?: return url
            val u = query.split('&').firstOrNull { it.startsWith("u=") }?.substring(2) ?: return url
            val b64 = u.removePrefix("a1").replace('-', '+').replace('_', '/')
            val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            url
        }
    }

    private fun stripTags(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
        .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
        .replace(Regex("(?is)<noscript[\\s\\S]*?</noscript>"), " ")
        .replace(Regex("(?is)<svg[\\s\\S]*?</svg>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

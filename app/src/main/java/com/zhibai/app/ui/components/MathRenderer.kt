package com.zhibai.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * Offline KaTeX renderer.
 *
 * KaTeX CSS/JS + woff2 fonts are bundled under assets/katex/ and inlined into a
 * single self-contained HTML string. loadDataWithBaseURL cannot load relative
 * file:///android_asset/ scripts on modern WebView (security), so inlining is
 * the only offline path that actually works.
 */
private object KatexBundle {
    @Volatile private var ready = false
    @Volatile var cssInline: String = ""
        private set
    @Volatile var katexJs: String = ""
        private set
    @Volatile var autoRenderJs: String = ""
        private set

    fun ensureLoaded(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val am = context.assets
            val css = am.open("katex/katex.min.css").bufferedReader().readText()
            // Embed every woff2 as a data URI so @font-face works without file access.
            val fontNames = css
                .split("url(")
                .drop(1)
                .mapNotNull { chunk ->
                    Regex("""fonts/(KaTeX_[A-Za-z0-9_-]+\.woff2)""").find(chunk)?.groupValues?.get(1)
                }
                .distinct()
            val cssBuilder = StringBuilder(css)
            for (name in fontNames) {
                val b64 = am.open("katex/fonts/$name").use { input ->
                    val bytes = input.readBytes()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
                // Replace all non-data font URLs for this file (woff2 only; drop woff/ttf refs)
                val pattern = Regex("""url\(fonts/${Regex.escape(name)}\)(\s*format\([^)]+\))?""")
                cssBuilder.replace(
                    pattern,
                    "url(data:font/woff2;base64,$b64) format('woff2')"
                )
            }
            // Neutralize any remaining remote/local font URLs so nothing tries the network
            cssInline = cssBuilder
                .replace(Regex("""url\((?!data:)"""), "url(data:,")
                .toString()
            katexJs = am.open("katex/katex.min.js").bufferedReader().readText()
            autoRenderJs = am.open("katex/auto-render.min.js").bufferedReader().readText()
            ready = true
        }
    }
}

/** JS → native height callback (runs on a WebView bridge thread). */
internal class KatexHeightBridge(private val onHeightCssPx: (Float) -> Unit) {
    @JavascriptInterface
    fun reportHeight(h: Float) {
        if (h > 0f && h < 100_000f) onHeightCssPx(h)
    }
}

/**
 * Bare TeX from ```latex fences has no $ / \( delimiters — auto-render skips it.
 * Wrap as display math so KaTeX picks it up. Leave already-delimited input alone.
 */
internal fun ensureMathDelimiters(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return t
    if (t.contains("$$")) return t
    if (t.contains("\\(") || t.contains("\\[")) return t
    if (Regex("(?<!\\\\)\\$[^\\$]+\\$").containsMatchIn(t)) return t
    // Multi-line bare TeX: put $$ on their own lines so markdownToSimpleHtml
    // opens display mode on the first line and closes on the last.
    return if (t.contains('\n')) "\$\$\n$t\n\$\$" else "\$\$" + t + "\$\$"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathRenderer(
    content: String,
    modifier: Modifier = Modifier,
    studyMode: Boolean = false,
    isDarkTheme: Boolean = false
) {
    val context = LocalContext.current
    // CSS px reported by the page ≈ Compose dp (viewport width=device-width).
    var contentHeightDp by remember { mutableIntStateOf(0) }

    val bodyFont = if (studyMode)
        "'Georgia', 'Times New Roman', 'STKaiti', 'KaiTi', 'Kaiti SC', serif"
    else
        "-apple-system, BlinkMacSystemFont, sans-serif"
    val bodyBg = when {
        studyMode -> "#f7f2e9"
        else -> "transparent"
    }
    val bodyColor = when {
        studyMode -> "#2c2416"
        isDarkTheme -> "#E6E6E6"
        else -> "#1a1a1a"
    }
    val mutedColor = when {
        studyMode -> "rgba(44,36,22,0.65)"
        isDarkTheme -> "rgba(230,230,230,0.65)"
        else -> "rgba(0,0,0,0.6)"
    }
    val borderLeft = when {
        studyMode -> "rgba(44,36,22,0.25)"
        isDarkTheme -> "rgba(230,230,230,0.25)"
        else -> "rgba(0,0,0,0.2)"
    }
    val codeBg = when {
        isDarkTheme -> "rgba(255,255,255,0.08)"
        else -> "rgba(0,0,0,0.06)"
    }
    val preBg = when {
        isDarkTheme -> "rgba(255,255,255,0.05)"
        else -> "rgba(0,0,0,0.04)"
    }

    val html = remember(content, bodyFont, bodyBg, bodyColor, mutedColor, borderLeft, codeBg, preBg, studyMode, isDarkTheme) {
        KatexBundle.ensureLoaded(context)
        val normalized = ensureMathDelimiters(content)
        val htmlContent = markdownToSimpleHtml(normalized)
        // </script> inside JS/CSS would break the HTML parser — neutralize once.
        val js = KatexBundle.katexJs.replace("</script>", "<\\/script>")
        val ar = KatexBundle.autoRenderJs.replace("</script>", "<\\/script>")
        val css = KatexBundle.cssInline.replace("</style>", "<\\/style>")
        """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
    <style>$css</style>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body {
            max-width: 100%;
            overflow-x: auto;
            word-wrap: break-word;
            overflow-wrap: break-word;
        }
        body {
            font-family: $bodyFont;
            padding: 8px 12px;
            background: $bodyBg;
            font-size: 15px;
            line-height: 1.75;
            color: $bodyColor;
        }
        p { margin: 0.4em 0; }
        strong { font-weight: 600; }
        em { font-style: italic; }
        code {
            background: $codeBg;
            padding: 1px 4px;
            border-radius: 3px;
            font-size: 0.9em;
            word-break: break-all;
        }
        pre {
            background: $preBg;
            padding: 8px 12px;
            border-radius: 6px;
            overflow-x: auto;
            max-width: 100%;
            margin: 0.5em 0;
        }
        pre code { background: none; padding: 0; word-break: normal; }
        h1, h2, h3 { font-weight: 600; margin: 0.6em 0 0.3em; }
        h1 { font-size: 1.3em; }
        h2 { font-size: 1.15em; }
        h3 { font-size: 1.05em; }
        ul, ol { padding-left: 1.5em; margin: 0.3em 0; }
        li { margin: 0.15em 0; }
        blockquote {
            border-left: 3px solid $borderLeft;
            padding-left: 12px;
            color: $mutedColor;
            margin: 0.5em 0;
        }
        hr { border: none; border-top: 1px solid $borderLeft; margin: 0.8em 0; }
        .katex { font-size: 1.05em; max-width: 100%; }
        .katex-display {
            margin: 0.6em 0;
            overflow-x: auto;
            overflow-y: hidden;
            max-width: 100%;
            padding: 2px 0;
            -webkit-overflow-scrolling: touch;
        }
        .katex-display > .katex {
            text-align: left;
            max-width: 100%;
            white-space: normal;
            word-break: break-word;
        }
        .katex .mord, .katex .mbin, .katex .mrel, .katex .mopen, .katex .mclose, .katex .mpunct {
            max-width: 100%;
        }
    </style>
</head>
<body>
    $htmlContent
    <script>$js</script>
    <script>$ar</script>
    <script>
        function __reportKatexHeight() {
            try {
                var h = 0;
                if (document.body) h = Math.max(h, document.body.scrollHeight);
                if (document.documentElement) h = Math.max(h, document.documentElement.scrollHeight);
                if (h > 0 && window.heightBridge) window.heightBridge.reportHeight(h);
            } catch (e) {}
        }
        try {
            renderMathInElement(document.body, {
                delimiters: [
                    {left: '$$', right: '$$', display: true},
                    {left: '$', right: '$', display: false},
                    {left: '\\[', right: '\\]', display: true},
                    {left: '\\(', right: '\\)', display: false}
                ],
                throwOnError: false,
                errorColor: '#cc0000'
            });
        } catch (e) {}
        __reportKatexHeight();
        window.addEventListener('load', __reportKatexHeight);
        if (typeof ResizeObserver !== 'undefined') {
            try { new ResizeObserver(__reportKatexHeight).observe(document.documentElement); } catch (e) {}
        }
        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(function () { __reportKatexHeight(); });
        }
        setTimeout(__reportKatexHeight, 80);
        setTimeout(__reportKatexHeight, 400);
    </script>
</body>
</html>
        """.trimIndent()
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(0)
                addJavascriptInterface(
                    KatexHeightBridge { cssPx ->
                        val dp = cssPx.roundToInt()
                        post {
                            if (dp > 0 && dp != contentHeightDp) {
                                contentHeightDp = dp
                            }
                        }
                    },
                    "heightBridge"
                )
                // Fully self-contained HTML — no local/remote resource loads.
                tag = html
                loadDataWithBaseURL("https://app.zhibai.local/", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            // Only reload when the document actually changed — reloading on every
            // recomposition (streaming, alpha animation) blanked the formula.
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL("https://app.zhibai.local/", html, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                // WebView inside LazyColumn measures 0 without an explicit height;
                // apply the page-reported height once it arrives.
                if (contentHeightDp > 0) Modifier.height(contentHeightDp.dp)
                else Modifier.height(1.dp)
            )
    )
}

private fun markdownToSimpleHtml(md: String): String {
    val lines = md.lines()
    val result = StringBuilder()
    var inCodeBlock = false
    var inList = false
    var inDisplayMath = false
    var displayDelim = "$$" // or \[
    val mathBuf = StringBuilder()

    fun flushList() { if (inList) { result.append("</ul>"); inList = false } }
    fun emitMath(raw: String) {
        result.append("<div class=\"katex-display\">")
            .append(sanitizeMathHtml(raw))
            .append("</div>")
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (inCodeBlock) {
            if (trimmed.startsWith("```")) {
                result.append("</code></pre>")
                inCodeBlock = false
            } else {
                result.append(escapeHtml(line)).append("\n")
            }
            i++
            continue
        }

        if (inDisplayMath) {
            // Match close against trimmed, but slice trimmed too — using a
            // trimmed index on the raw line dropped leading spaces and corrupted
            // formulas whose closing $$ was indented.
            val close = if (displayDelim == "$$") trimmed.indexOf("$$") else trimmed.indexOf("\\]")
            if (close >= 0) {
                mathBuf.append(trimmed.substring(0, close + 2))
                emitMath(mathBuf.toString())
                mathBuf.clear()
                inDisplayMath = false
                val after = trimmed.substring(close + 2).trim()
                if (after.isNotEmpty()) {
                    result.append("<p>").append(inlineHtml(after)).append("</p>")
                }
            } else {
                mathBuf.append(trimmed).append('\n')
            }
            i++
            continue
        }

        if (trimmed.startsWith("```")) {
            flushList()
            result.append("<pre><code>")
            inCodeBlock = true
            i++
            continue
        }

        // Display math: $$ ... $$ or \[ ... \]
        if (trimmed.contains("$$")) {
            val first = trimmed.indexOf("$$")
            val rest = trimmed.substring(first + 2)
            val close = rest.indexOf("$$")
            if (close >= 0) {
                emitMath("$$" + rest.substring(0, close) + "$$")
                val after = rest.substring(close + 2).trim()
                if (after.isNotEmpty()) {
                    flushList()
                    result.append("<p>").append(inlineHtml(after)).append("</p>")
                }
                i++
                continue
            }
            flushList()
            inDisplayMath = true
            displayDelim = "$$"
            mathBuf.append("$$").append(rest)
            i++
            continue
        }
        if (trimmed.startsWith("\\[")) {
            val rest = trimmed.substring(2)
            val close = rest.indexOf("\\]")
            if (close >= 0) {
                emitMath("\\[" + rest.substring(0, close) + "\\]")
                i++
                continue
            }
            flushList()
            inDisplayMath = true
            displayDelim = "\\["
            mathBuf.append("\\[").append(rest)
            i++
            continue
        }

        if (trimmed.isBlank()) {
            flushList()
            i++
            continue
        }

        when {
            trimmed.startsWith("### ") -> { flushList(); result.append("<h3>${inlineHtml(trimmed.removePrefix("### "))}</h3>"); i++; continue }
            trimmed.startsWith("## ") -> { flushList(); result.append("<h2>${inlineHtml(trimmed.removePrefix("## "))}</h2>"); i++; continue }
            trimmed.startsWith("# ") -> { flushList(); result.append("<h1>${inlineHtml(trimmed.removePrefix("# "))}</h1>"); i++; continue }
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            if (!inList) { result.append("<ul>"); inList = true }
            result.append("<li>${inlineHtml(trimmed.removePrefix("- ").removePrefix("* "))}</li>")
            i++
            continue
        }

        if (trimmed.matches(Regex("^\\d+\\. .*"))) {
            if (!inList) { result.append("<ol>"); inList = true }
            result.append("<li>${inlineHtml(trimmed.replaceFirst(Regex("^\\d+\\. "), ""))}</li>")
            i++
            continue
        }

        flushList()

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            result.append("<hr>")
            i++
            continue
        }

        if (trimmed.startsWith("> ")) {
            result.append("<blockquote>${inlineHtml(trimmed.removePrefix("> "))}</blockquote>")
            i++
            continue
        }

        result.append("<p>${inlineHtml(trimmed)}</p>")
        i++
    }

    if (inList) result.append("</ul>")
    if (inCodeBlock) result.append("</code></pre>")
    if (inDisplayMath) emitMath(mathBuf.toString())

    return result.toString()
}

/**
 * Keep LaTeX intact for KaTeX; escape only what would break HTML parsing.
 * Must run on the raw formula — unescaped `<` (e.g. `a < b`) truncated the
 * document at parse time and the whole formula never rendered.
 */
private fun sanitizeMathHtml(s: String): String {
    return s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun inlineHtml(text: String): String {
    return escapeHtml(text)
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
}

private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

package com.mimo.app.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathRenderer(
    content: String,
    modifier: Modifier = Modifier
) {
    val htmlContent = markdownToSimpleHtml(content)

    val html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
    <script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"></script>
    <style>
        * { margin: 0; padding: 0; }
        body { 
            font-family: -apple-system, BlinkMacSystemFont, sans-serif;
            padding: 8px 12px;
            background: transparent;
            font-size: 15px;
            line-height: 1.7;
            color: #1a1a1a;
        }
        p { margin: 0.4em 0; }
        strong { font-weight: 600; }
        em { font-style: italic; }
        code { 
            background: rgba(0,0,0,0.06); 
            padding: 1px 4px; 
            border-radius: 3px; 
            font-size: 0.9em;
        }
        pre { 
            background: rgba(0,0,0,0.04); 
            padding: 8px 12px; 
            border-radius: 6px; 
            overflow-x: auto; 
            margin: 0.5em 0;
        }
        pre code { background: none; padding: 0; }
        h1, h2, h3 { font-weight: 600; margin: 0.6em 0 0.3em; }
        h1 { font-size: 1.3em; }
        h2 { font-size: 1.15em; }
        h3 { font-size: 1.05em; }
        ul, ol { padding-left: 1.5em; margin: 0.3em 0; }
        li { margin: 0.15em 0; }
        blockquote { 
            border-left: 3px solid rgba(0,0,0,0.2); 
            padding-left: 12px; 
            color: rgba(0,0,0,0.6); 
            margin: 0.5em 0;
        }
        hr { border: none; border-top: 1px solid rgba(0,0,0,0.15); margin: 0.8em 0; }
        .katex { font-size: 1.05em; }
        .katex-display { margin: 0.5em 0; overflow-x: auto; }
        .katex-display > .katex { text-align: left; }
    </style>
</head>
<body>
    $htmlContent
    <script>
        renderMathInElement(document.body, {
            delimiters: [
                {left: '$$', right: '$$', display: true},
                {left: '$', right: '$', display: false},
                {left: '\\[', right: '\\]', display: true},
                {left: '\\(', right: '\\)', display: false}
            ],
            throwOnError: false
        });
    </script>
</body>
</html>
    """.trimIndent()

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(0)
                loadDataWithBaseURL("https://mimo.app", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL("https://mimo.app", html, "text/html", "UTF-8", null)
        },
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
    )
}

private fun markdownToSimpleHtml(md: String): String {
    val lines = md.lines()
    val result = StringBuilder()
    var inCodeBlock = false
    var inList = false

    for (line in lines) {
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                result.append("</code></pre>")
                inCodeBlock = false
            } else {
                if (inList) { result.append("</ul>"); inList = false }
                result.append("<pre><code>")
                inCodeBlock = true
            }
            continue
        }
        if (inCodeBlock) {
            result.append(escapeHtml(line)).append("\n")
            continue
        }

        if (trimmed.isBlank()) {
            if (inList) { result.append("</ul>"); inList = false }
            continue
        }

        when {
            trimmed.startsWith("### ") -> { if (inList) { result.append("</ul>"); inList = false }; result.append("<h3>${inlineHtml(trimmed.removePrefix("### "))}</h3>"); continue }
            trimmed.startsWith("## ") -> { if (inList) { result.append("</ul>"); inList = false }; result.append("<h2>${inlineHtml(trimmed.removePrefix("## "))}</h2>"); continue }
            trimmed.startsWith("# ") -> { if (inList) { result.append("</ul>"); inList = false }; result.append("<h1>${inlineHtml(trimmed.removePrefix("# "))}</h1>"); continue }
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            if (!inList) { result.append("<ul>"); inList = true }
            result.append("<li>${inlineHtml(trimmed.removePrefix("- ").removePrefix("* "))}</li>")
            continue
        }

        if (trimmed.matches(Regex("^\\d+\\. .*"))) {
            if (!inList) { result.append("<ol>"); inList = true }
            result.append("<li>${inlineHtml(trimmed.replaceFirst(Regex("^\\d+\\. "), ""))}</li>")
            continue
        }

        if (inList) { result.append("</ul>"); inList = false }

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            result.append("<hr>")
            continue
        }

        if (trimmed.startsWith("> ")) {
            result.append("<blockquote>${inlineHtml(trimmed.removePrefix("> "))}</blockquote>")
            continue
        }

        result.append("<p>${inlineHtml(trimmed)}</p>")
    }

    if (inList) result.append("</ul>")
    if (inCodeBlock) result.append("</code></pre>")

    return result.toString()
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

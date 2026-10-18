package com.mimo.app.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichRenderer(
    content: String,
    type: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val html = remember(content, type) { buildHtml(content, type) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Match CodeBlock — must contrast with chat background
    val isDark = isSystemInDarkTheme()
    val frameBg = if (isDark) Color(0xFF2A2A2A) else Color(0xFFECECEC)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(frameBg)
    ) {
        // Action bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = type.uppercase(),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            // Export SVG button
            TextButton(
                onClick = {
                    // Base64 round-trip avoids JSON string escaping issues in evaluateJavascript
                    webViewRef?.evaluateJavascript(
                        "(function(){var s=document.querySelector('svg');" +
                            "if(!s)return null;" +
                            "try{return btoa(unescape(encodeURIComponent(s.outerHTML)));}catch(e){return null;}})()"
                    ) { result ->
                        val b64 = result?.removeSurrounding("\"")
                        if (b64 != null && b64 != "null" && b64.isNotBlank()) {
                            try {
                                val svg = String(
                                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT),
                                    Charsets.UTF_8
                                )
                                ClipboardHelper.copyWithToast(context, svg, label = "svg")
                            } catch (e: Exception) {
                                Toast.makeText(context, "SVG 解码失败", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "未找到 SVG（图表可能未渲染完成）", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(2.dp))
                Text("SVG", fontSize = 11.sp)
            }

            // Copy code button
            TextButton(
                onClick = {
                    ClipboardHelper.copyWithToast(context, content, label = "code")
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(2.dp))
                Text("Code", fontSize = 11.sp)
            }
        }

        // WebView
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    isVerticalScrollBarEnabled = true
                    isHorizontalScrollBarEnabled = false
                    loadDataWithBaseURL("https://mimo.app", html, "text/html", "UTF-8", null)
                }
            },
            update = { webView ->
                webView.loadDataWithBaseURL("https://mimo.app", html, "text/html", "UTF-8", null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 400.dp)
        )
    }
}

private fun buildHtml(content: String, type: String): String {
    val escaped = content
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    return when (type) {
        "mermaid" -> buildMermaidHtml(escaped)
        "plotly" -> buildPlotlyHtml(content) // Plotly needs raw JSON
        "vega", "vega-lite" -> buildVegaHtml(content) // Vega needs raw JSON
        "dot", "graphviz" -> buildGraphvizHtml(escaped)
        "latex", "tex" -> buildLatexHtml(content) // LaTeX needs raw text
        "mathml" -> buildMathMLHtml(content) // MathML needs raw XML
        "asciimath" -> buildAsciiMathHtml(content) // AsciiMath needs raw text
        "katex" -> buildKatexHtml(content) // KaTeX needs raw text
        else -> buildMermaidHtml(escaped)
    }
}

private fun buildMermaidHtml(content: String): String {
    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { 
            font-family: -apple-system, BlinkMacSystemFont, sans-serif;
            padding: 12px;
            background: transparent;
            overflow-x: auto;
        }
        .mermaid { 
            display: flex; 
            justify-content: center;
        }
        svg { max-width: 100%; height: auto; }
    </style>
</head>
<body>
    <pre class="mermaid">$content</pre>
    <script>
        mermaid.initialize({ 
            startOnLoad: true,
            theme: 'default',
            securityLevel: 'loose'
        });
    </script>
</body>
</html>
    """.trimIndent()
}

private fun buildPlotlyHtml(content: String): String {
    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <script src="https://cdn.plot.ly/plotly-2.35.0.min.js"></script>
    <style>
        * { margin: 0; padding: 0; }
        body { background: transparent; }
        #chart { width: 100%; min-height: 250px; }
    </style>
</head>
<body>
    <div id="chart"></div>
    <script>
        try {
            var data = $content;
            if (Array.isArray(data)) {
                Plotly.newPlot('chart', data, {margin: {t: 20, r: 20, b: 40, l: 40}});
            } else if (data.data) {
                Plotly.newPlot('chart', data.data, data.layout || {margin: {t: 20, r: 20, b: 40, l: 40}});
            }
        } catch(e) {
            document.body.innerHTML = '<p style="color:red;padding:12px;">Plotly error: ' + e.message + '</p>';
        }
    </script>
</body>
</html>
    """.trimIndent()
}

private fun buildVegaHtml(content: String): String {
    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <script src="https://cdn.jsdelivr.net/npm/vega@5"></script>
    <script src="https://cdn.jsdelivr.net/npm/vega-lite@5"></script>
    <script src="https://cdn.jsdelivr.net/npm/vega-embed@6"></script>
    <style>
        * { margin: 0; padding: 0; }
        body { background: transparent; padding: 8px; }
        #chart { width: 100%; }
    </style>
</head>
<body>
    <div id="chart"></div>
    <script>
        try {
            var spec = $content;
            vegaEmbed('#chart', spec, {actions: false});
        } catch(e) {
            document.body.innerHTML = '<p style="color:red;padding:12px;">Vega error: ' + e.message + '</p>';
        }
    </script>
</body>
</html>
    """.trimIndent()
}

private fun buildGraphvizHtml(content: String): String {
    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <script src="https://cdn.jsdelivr.net/npm/@viz-js/viz@3"></script>
    <style>
        * { margin: 0; padding: 0; }
        body { 
            background: transparent; 
            padding: 12px;
            display: flex;
            justify-content: center;
        }
        svg { max-width: 100%; height: auto; }
    </style>
</head>
<body>
    <div id="graph"></div>
    <script>
        Viz.instance().then(function(viz) {
            try {
                var svg = viz.renderSVGElement("$content");
                document.getElementById('graph').appendChild(svg);
            } catch(e) {
                document.body.innerHTML = '<p style="color:red;padding:12px;">Graphviz error: ' + e.message + '</p>';
            }
        });
    </script>
</body>
</html>
    """.trimIndent()
}

private fun buildLatexHtml(content: String): String {
    val safeContent = content
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("</script>", "<\\/script>")
    return """
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
            padding: 12px;
            background: transparent;
            font-size: 16px;
            line-height: 1.6;
        }
        .katex { font-size: 1.1em; }
        .katex-display { margin: 0.5em 0; overflow-x: auto; }
    </style>
</head>
<body>
    <div id="math">$safeContent</div>
    <script>
        renderMathInElement(document.getElementById('math'), {
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
}

private fun buildKatexHtml(content: String): String {
    return buildLatexHtml(content)
}

private fun buildMathMLHtml(content: String): String {
    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        * { margin: 0; padding: 0; }
        body { 
            padding: 12px;
            background: transparent;
            font-size: 16px;
        }
        math { font-size: 1.2em; }
    </style>
</head>
<body>
    $content
</body>
</html>
    """.trimIndent()
}

private fun buildAsciiMathHtml(content: String): String {
    val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <script src="https://cdn.jsdelivr.net/npm/asciimath-to-katex@0.1.0/dist/asciimath2katex.min.js"></script>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
    <script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
    <style>
        * { margin: 0; padding: 0; }
        body { 
            padding: 12px;
            background: transparent;
            font-size: 16px;
        }
        .katex { font-size: 1.1em; }
    </style>
</head>
<body>
    <div id="math"></div>
    <script>
        try {
            var latex = asciimath2katex('$escaped');
            katex.render(latex, document.getElementById('math'), {
                throwOnError: false,
                displayMode: true
            });
        } catch(e) {
            document.getElementById('math').textContent = '$escaped';
        }
    </script>
</body>
</html>
    """.trimIndent()
}

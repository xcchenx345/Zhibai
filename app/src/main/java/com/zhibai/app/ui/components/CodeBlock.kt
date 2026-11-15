package com.zhibai.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.io.File

// ── Lightweight regex syntax highlight (no external deps) ─────────────

private val JS_KEYWORDS = setOf(
    "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
    "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally",
    "for", "function", "if", "implements", "import", "in", "instanceof", "interface", "let",
    "new", "null", "of", "package", "private", "protected", "public", "return", "static",
    "super", "switch", "this", "throw", "true", "try", "typeof", "undefined", "var", "void",
    "while", "with", "yield"
)
private val PYTHON_KEYWORDS = setOf(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
    "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
    "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
    "True", "try", "while", "with", "yield"
)
private val JVM_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while",
    // Kotlin extras
    "fun", "val", "var", "object", "companion", "data", "sealed", "suspend", "override",
    "open", "internal", "inline", "reified", "operator", "infix", "lateinit", "vararg",
    "crossinline", "noinline", "typealias", "init", "constructor", "get", "set", "by",
    "in", "is", "as", "when", "null", "true", "false", "this", "super"
)
private val C_KEYWORDS = setOf(
    "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
    "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register",
    "restrict", "return", "short", "signed", "sizeof", "static", "struct", "switch",
    "typedef", "union", "unsigned", "void", "volatile", "while",
    // C++ extras
    "class", "namespace", "template", "typename", "public", "private", "protected", "virtual",
    "override", "final", "new", "delete", "try", "catch", "throw", "using", "nullptr",
    "constexpr", "noexcept", "static_cast", "dynamic_cast", "const_cast", "reinterpret_cast"
)
private val GO_KEYWORDS = setOf(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
    "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
    "return", "select", "struct", "switch", "type", "var", "nil", "true", "false", "iota"
)
private val RUST_KEYWORDS = setOf(
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
    "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
    "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
    "trait", "true", "type", "unsafe", "use", "where", "while"
)
private val SQL_KEYWORDS = setOf(
    "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BETWEEN", "BY", "CASE", "CHECK",
    "COLUMN", "CREATE", "CROSS", "DATABASE", "DELETE", "DESC", "DISTINCT", "DROP", "ELSE",
    "END", "EXISTS", "FOREIGN", "FROM", "GROUP", "HAVING", "IN", "INDEX", "INNER", "INSERT",
    "INTO", "IS", "JOIN", "KEY", "LEFT", "LIKE", "LIMIT", "NOT", "NULL", "OFFSET", "ON",
    "OR", "ORDER", "OUTER", "PRIMARY", "REFERENCES", "RIGHT", "SELECT", "SET", "TABLE",
    "THEN", "UNION", "UNIQUE", "UPDATE", "USING", "VALUES", "VIEW", "WHEN", "WHERE", "WITH"
)
private val SHELL_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
    "esac", "function", "in", "select", "time", "export", "local", "return", "exit",
    "echo", "printf", "read", "cd", "set", "unset", "source", "alias", "true", "false"
)
private val COMMON_KEYWORDS = setOf(
    "true", "false", "null", "nil", "None", "True", "False", "undefined", "this", "self"
)

private fun keywordsFor(lang: String): Set<String> = when (lang.lowercase()) {
    "javascript", "js", "jsx", "typescript", "ts", "tsx", "vue" -> JS_KEYWORDS + COMMON_KEYWORDS
    "python", "py" -> PYTHON_KEYWORDS
    "kotlin", "kt", "java", "scala", "groovy" -> JVM_KEYWORDS
    "c", "cpp", "c++", "cc", "cxx", "h", "hpp", "objective-c", "objc" -> C_KEYWORDS
    "go", "golang" -> GO_KEYWORDS
    "rust", "rs" -> RUST_KEYWORDS
    "sql", "mysql", "postgres", "postgresql", "sqlite" -> SQL_KEYWORDS
    "shell", "bash", "sh", "zsh", "fish" -> SHELL_KEYWORDS
    "json" -> setOf("true", "false", "null")
    else -> JS_KEYWORDS + PYTHON_KEYWORDS + JVM_KEYWORDS + COMMON_KEYWORDS
}

private fun hashCommentLang(lang: String): Boolean = when (lang.lowercase()) {
    "python", "py", "shell", "bash", "sh", "zsh", "yaml", "yml", "toml", "ruby", "rb",
    "perl", "r", "dockerfile", "makefile", "cmake", "conf", "ini" -> true
    else -> false
}

/** Build AnnotatedString with token colors for common languages. */
internal fun highlightAnnotatedCode(code: String, language: String, isDarkTheme: Boolean): AnnotatedString {
    val keywords = keywordsFor(language)
    val allowHashComment = hashCommentLang(language)

    val commentColor = if (isDarkTheme) Color(0xFF6A9955) else Color(0xFF008000)
    val stringColor = if (isDarkTheme) Color(0xFFCE9178) else Color(0xFFA31515)
    val numberColor = if (isDarkTheme) Color(0xFFB5CEA8) else Color(0xFF098658)
    val keywordColor = if (isDarkTheme) Color(0xFF569CD6) else Color(0xFF0000FF)
    val funcColor = if (isDarkTheme) Color(0xFFDCDCAA) else Color(0xFF795E26)
    val plainColor = if (isDarkTheme) Color(0xFFD4D4D4) else Color(0xFF1E1E1E)

    // Order matters: comments & strings first so keywords inside them stay plain.
    val parts = mutableListOf<Pair<Regex, Color>>()
    parts += Regex("//[^\\n]*") to commentColor
    parts += Regex("/\\*[\\s\\S]*?\\*/") to commentColor
    if (allowHashComment) parts += Regex("#[^\\n]*") to commentColor
    parts += Regex("\"\"\"[\\s\\S]*?\"\"\"") to stringColor
    parts += Regex("'''[\\s\\S]*?'''") to stringColor
    parts += Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"") to stringColor
    parts += Regex("'(?:\\\\.|[^'\\\\\\n])*'") to stringColor
    parts += Regex("`[^`\\n]*`") to stringColor
    parts += Regex("\\b\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFlLdD]?\\b") to numberColor
    if (keywords.isNotEmpty()) {
        val alt = keywords.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        parts += Regex("\\b(?:$alt)\\b") to keywordColor
    }
    parts += Regex("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()") to funcColor

    // Non-overlapping scan: first matching pattern wins per position.
    data class Hit(val start: Int, val end: Int, val color: Color)
    val hits = mutableListOf<Hit>()
    for ((regex, color) in parts) {
        for (m in regex.findAll(code)) {
            val s = m.range.first
            val e = m.range.last + 1
            val overlaps = hits.any { s < it.end && e > it.start }
            if (!overlaps) hits += Hit(s, e, color)
        }
    }
    hits.sortBy { it.start }

    return buildAnnotatedString {
        var cursor = 0
        for (hit in hits) {
            if (hit.start < cursor) continue
            if (hit.start > cursor) {
                withStyle(SpanStyle(color = plainColor)) { append(code.substring(cursor, hit.start)) }
            }
            withStyle(SpanStyle(color = hit.color)) { append(code.substring(hit.start, hit.end)) }
            cursor = hit.end
        }
        if (cursor < code.length) {
            withStyle(SpanStyle(color = plainColor)) { append(code.substring(cursor)) }
        }
    }
}

@Composable
fun CodeBlock(
    code: String,
    language: String = "",
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(false) }

    // 折叠贴顶: a collapsed block's header pins to the top of the chat area while
    // the block scrolls past. LazyColumn does not clip its items, so a zIndex +
    // translation paints the header over the messages above it.
    val density = LocalDensity.current
    val stickyTopPx = with(density) { 104.dp.toPx() }
    var winY by remember { mutableFloatStateOf(0f) }
    var blockH by remember { mutableFloatStateOf(0f) }
    val stuck = collapsed && winY < stickyTopPx && winY + blockH > stickyTopPx
    val stickDy = if (stuck) stickyTopPx - winY else 0f

    val codeBg = if (isDarkTheme) Color(0xFF2A2A2A) else Color(0xFFECECEC)
    // Re-highlight only when code / language / theme actually changes
    val highlighted = remember(code, language, isDarkTheme) {
        highlightAnnotatedCode(code, language, isDarkTheme)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                winY = coords.positionInWindow().y
                blockH = coords.size.height.toFloat()
            }
            .graphicsLayer {
                translationY = stickDy
                shadowElevation = if (stuck) 12f else 0f
            }
            .zIndex(if (stuck) 10f else 0f)
            .clip(RoundedCornerShape(8.dp))
            .background(codeBg)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { collapsed = !collapsed }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (collapsed) "展开代码" else "折叠代码",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (language.isNotBlank()) language else "code",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (collapsed) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${code.lineCount()} 行",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // Copy
                TextButton(
                    onClick = {
                        ClipboardHelper.copyWithToast(context, code, label = "code")
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(11.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(if (copied) "已复制" else "复制", fontSize = 10.sp)
                }

                // Save
                TextButton(
                    onClick = {
                        try {
                            val dir = File(android.os.Environment.getExternalStorageDirectory(), "知白")
                            if (!dir.exists()) dir.mkdirs()
                            val ext = when (language.lowercase()) {
                                "kotlin", "kt" -> "kt"
                                "python", "py" -> "py"
                                "javascript", "js" -> "js"
                                "typescript", "ts" -> "ts"
                                "java" -> "java"
                                "c" -> "c"
                                "cpp", "c++" -> "cpp"
                                "csharp", "c#" -> "cs"
                                "go" -> "go"
                                "rust", "rs" -> "rs"
                                "swift" -> "swift"
                                "shell", "bash", "sh" -> "sh"
                                "sql" -> "sql"
                                "html" -> "html"
                                "css" -> "css"
                                "json" -> "json"
                                "yaml", "yml" -> "yaml"
                                "toml" -> "toml"
                                "xml" -> "xml"
                                else -> "txt"
                            }
                            val fileName = "code_${System.currentTimeMillis()}.$ext"
                            File(dir, fileName).writeText(code)
                            android.widget.Toast.makeText(context, "已保存到 知白/$fileName", android.widget.Toast.LENGTH_SHORT).show()
                            saved = true
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(11.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(if (saved) "已保存" else "保存", fontSize = 10.sp)
                }

                // View in new activity
                TextButton(
                    onClick = {
                        try {
                            val dir = File(android.os.Environment.getExternalStorageDirectory(), "知白")
                            if (!dir.exists()) dir.mkdirs()
                            val ext = when (language.lowercase()) {
                                "kotlin", "kt" -> "kt"
                                "python", "py" -> "py"
                                "javascript", "js" -> "js"
                                else -> "txt"
                            }
                            val fileName = "view_${System.currentTimeMillis()}.$ext"
                            val file = File(dir, fileName)
                            file.writeText(code)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "text/plain")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "无法打开: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(11.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("查看", fontSize = 10.sp)
                }
            }
        }

        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )

        // Code content — syntax highlighted (hidden while collapsed)
        if (!collapsed) {
            Text(
                text = highlighted,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

private fun String.lineCount(): Int = if (isEmpty()) 0 else count { it == '\n' } + 1

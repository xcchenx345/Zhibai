package com.zhibai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RICH_TYPES = setOf(
    "mermaid", "plotly", "vega", "vega-lite",
    "dot", "graphviz", "latex", "tex",
    "mathml", "asciimath", "katex"
)

data class ContentPart(
    val type: String,
    val language: String = "",
    val content: String
)

/** Fullwidth / typographic chars Chinese models love to emit for GFM tables. */
internal fun normalizeTableChars(line: String): String {
    if (line.none { it.code > 0x7F }) return line
    val sb = StringBuilder(line.length)
    for (ch in line) {
        sb.append(
            when (ch) {
                '｜' -> '|'          // FULLWIDTH VERTICAL LINE
                '∣' -> '|'          // SET MINUS / looks like pipe
                '─', '━', '–', '—', '－' -> '-' // dashes Chinese IMEs substitute
                else -> ch
            }
        )
    }
    return sb.toString()
}

/** True for a GFM delimiter row: `|---|`, `--- | ---`, `|:--|--:|`, `===`-free. */
internal fun isTableSeparatorLine(line: String): Boolean {
    val s = normalizeTableChars(line.trim())
    if (s.isEmpty() || !s.contains('-')) return false
    // Allow optional outer pipes, colons for alignment, spaces, pipes, hyphens.
    // Reject anything else (including `| a | b |` data rows).
    return s.matches(Regex("""^\|?[\s:|-]+\|?$"""))
}

fun parseContent(text: String): List<ContentPart> {
    val parts = mutableListOf<ContentPart>()
    val lines = text.lines()
    var i = 0
    var textBuffer = StringBuilder()

    fun flushText() {
        val t = textBuffer.toString().trim()
        if (t.isNotBlank()) parts.add(ContentPart("text", content = t))
        textBuffer = StringBuilder()
    }

    while (i < lines.size) {
        val line = lines[i]

        // Code block
        if (line.trimStart().startsWith("```")) {
            flushText()
            val lang = line.trimStart().removePrefix("```").trim().lowercase()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++
            val code = codeLines.joinToString("\n").trimEnd('\n')
            when {
                lang in RICH_TYPES -> parts.add(ContentPart(lang, lang, code))
                lang.isNotBlank() -> parts.add(ContentPart("code", lang, code))
                else -> parts.add(ContentPart("code", "", code))
            }
            continue
        }

        // Horizontal rule (not a table separator — those need a header row above)
        if (line.trim() == "---" || line.trim() == "***" || line.trim() == "___") {
            flushText()
                parts.add(ContentPart("divider", content = ""))
            i++
            continue
        }

        // GFM table: header row + delimiter row. Accept pipe-wrapped and bare styles,
        // and fullwidth pipes (｜) which MiMo/Chinese models often emit.
        if (normalizeTableChars(line).contains('|') && i + 1 < lines.size &&
            isTableSeparatorLine(lines[i + 1])
        ) {
            flushText()
            val tableLines = mutableListOf<String>()
            tableLines.add(normalizeTableChars(line))
            i++
            while (i < lines.size && lines[i].trim().isNotBlank() &&
                normalizeTableChars(lines[i]).contains('|') &&
                !lines[i].trimStart().startsWith("```")
            ) {
                // Keep separator row in the stream; MarkdownTable skips lines[1].
                tableLines.add(normalizeTableChars(lines[i]))
                i++
            }
            parts.add(ContentPart("table", content = tableLines.joinToString("\n")))
            continue
        }

        textBuffer.appendLine(line)
        i++
    }
    flushText()

    if (parts.isEmpty() && text.isNotBlank()) {
        parts.add(ContentPart("text", content = text))
    }

    return parts
}

fun parseTextWithMath(text: String): List<ContentPart> {
    val parts = mutableListOf<ContentPart>()
    // Match $$...$$ (display math) and $...$ (inline math), but not escaped \$
    val regex = Regex("\\$\\$([\\s\\S]*?)\\$\\$|(?<!\\\\)\\$([^\\$]+?)\\$")
    var lastIndex = 0

    for (match in regex.findAll(text)) {
        if (match.range.first > lastIndex) {
            val before = text.substring(lastIndex, match.range.first)
            if (before.isNotBlank()) parts.add(ContentPart("text", content = before))
        }

        val displayMath = match.groupValues[1]
        val inlineMath = match.groupValues[2]
        val mathContent = if (displayMath.isNotBlank()) displayMath else inlineMath
        if (mathContent.isNotBlank()) {
            parts.add(ContentPart("katex", "katex", mathContent.trim()))
        }

        lastIndex = match.range.last + 1
    }

    if (lastIndex < text.length) {
        val remaining = text.substring(lastIndex)
        if (remaining.isNotBlank()) parts.add(ContentPart("text", content = remaining))
    }

    if (parts.isEmpty() && text.isNotBlank()) {
        parts.add(ContentPart("text", content = text))
    }

    return parts
}

@Composable
fun RichMessageContent(
    content: String,
    color: Color,
    isDarkTheme: Boolean,
    fontSize: Int = 15,
    modifier: Modifier = Modifier,
    studyMode: Boolean = false,
    annotations: List<com.zhibai.app.data.model.Annotation> = emptyList(),
    onOpenUrl: ((String) -> Unit)? = null
) {
    // Cache parsed parts — only re-parse when content actually changes
    val parts = remember(content) { parseContent(content) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((index, part) in parts.withIndex()) {
            // key() lets Compose skip recomposing parts that didn't change
            key(index, part.type) {
                when (part.type) {
                    "text" -> {
                        if (containsMath(part.content)) {
                            MathRenderer(
                                content = part.content,
                                studyMode = studyMode,
                                isDarkTheme = isDarkTheme
                            )
                        } else {
                            MarkdownText(
                                markdown = part.content,
                                color = color,
                                fontSize = fontSize,
                                lineHeight = fontSize + 7,
                                studyMode = studyMode,
                                annotations = annotations,
                                onOpenUrl = onOpenUrl
                            )
                        }
                    }
                    "code" -> CodeBlock(code = part.content, language = part.language, isDarkTheme = isDarkTheme)
                    "table" -> MarkdownTable(tableText = part.content, isDarkTheme = isDarkTheme)
                    "divider" -> HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    "katex", "latex", "tex", "mathml", "asciimath" -> MathRenderer(
                        content = part.content,
                        studyMode = studyMode,
                        isDarkTheme = isDarkTheme
                    )
                    else -> RichRenderer(content = part.content, type = part.type, studyMode = studyMode)
                }
            }
        }
    }
}

private fun containsMath(text: String): Boolean {
    // $...$ / $$...$$ (but not escaped \$), plus TeX-style \( \) and \[ \]
    return text.contains(Regex("(?<!\\\\)\\$[^\\$]+\\$")) ||
        text.contains("$$") ||
        text.contains("\\(") ||
        text.contains("\\[")
}

@Composable
fun MarkdownTable(tableText: String, isDarkTheme: Boolean) {
    val lines = tableText.lines()
        .filter { it.trim().isNotBlank() }
        .map { normalizeTableChars(it) }
    // Need at least header + separator; a header-only table degrades to mono text.
    if (lines.size < 2 || !isTableSeparatorLine(lines[1])) {
        Text(
            text = tableText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (isDarkTheme) Color(0xFFE0E0E0) else Color(0xFF1A1A1A)
        )
        return
    }

    val headerCells = parseTableRow(lines[0])
    val dataLines = lines.drop(2)
    val allRows = listOf(headerCells) + dataLines.map { parseTableRow(it) }
    if (allRows.isEmpty() || headerCells.all { it.isBlank() }) {
        Text(
            text = tableText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (isDarkTheme) Color(0xFFE0E0E0) else Color(0xFF1A1A1A)
        )
        return
    }
    val colCount = maxOf(allRows.maxOf { it.size }, 1)

    val borderColor = if (isDarkTheme) Color(0xFF555555) else Color(0xFFCCCCCC)
    val headerBg = if (isDarkTheme) Color(0xFF3A3A3A) else Color(0xFFE8E8E8)
    val rowBg = if (isDarkTheme) Color(0xFF2A2A2A) else Color(0xFFFFFFFF)
    val altRowBg = if (isDarkTheme) Color(0xFF333333) else Color(0xFFF5F5F5)
    val textColor = if (isDarkTheme) Color(0xFFE0E0E0) else Color(0xFF1A1A1A)

    // Fixed column widths from content — weight() collapses to 0 inside
    // horizontalScroll (unbounded max width), which made the whole table vanish.
    val charWidthDp = 8
    val padH = 10
    val colWidthsDp = (0 until colCount).map { col ->
        val maxChars = allRows.maxOf { row ->
            visibleCellWidth(row.getOrElse(col) { "" })
        }.coerceIn(3, 36) // ~3..36 chars; CJK counts as 2
        (maxChars * charWidthDp + padH * 2).dp
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            Column {
                // Header
                Row(modifier = Modifier.background(headerBg)) {
                    for (col in 0 until colCount) {
                        val cellText = headerCells.getOrElse(col) { "" }.trim()
                        Box(
                            modifier = Modifier
                                .width(colWidthsDp[col])
                                .border(0.5.dp, borderColor)
                        ) {
                            Text(
                                text = cellText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = padH.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                // Data rows
                dataLines.forEachIndexed { rowIdx, line ->
                    val cells = parseTableRow(line)
                    val bg = if (rowIdx % 2 == 0) rowBg else altRowBg
                    Row(modifier = Modifier.background(bg)) {
                        for (col in 0 until colCount) {
                            val cellText = cells.getOrElse(col) { "" }.trim()
                            Box(
                                modifier = Modifier
                                    .width(colWidthsDp[col])
                                    .border(0.5.dp, borderColor)
                            ) {
                                Text(
                                    text = cellText,
                                    fontSize = 13.sp,
                                    color = textColor,
                                    maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = padH.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseTableRow(line: String): List<String> {
    var s = normalizeTableChars(line.trim())
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    // Split on unescaped pipes so `a \| b` stays one cell.
    return s.split(Regex("(?<!\\\\)\\|")).map { it.replace("\\|", "|").trim() }
}

/** Display width: CJK / fullwidth ≈ 2 columns, ASCII ≈ 1. Caps long cells so one word can't blow the row. */
private fun visibleCellWidth(s: String): Int {
    var w = 0
    for (ch in s.trim()) {
        w += if (ch.code > 0x2E80) 2 else 1
        if (w >= 48) break
    }
    return w
}

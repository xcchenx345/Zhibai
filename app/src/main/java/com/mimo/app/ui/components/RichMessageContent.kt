package com.mimo.app.ui.components

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

        // Horizontal rule
        if (line.trim() == "---" || line.trim() == "***" || line.trim() == "___") {
            flushText()
                parts.add(ContentPart("divider", content = ""))
            i++
            continue
        }

        // Table: detect lines with | that form a table
        if (line.trim().startsWith("|") && line.trim().endsWith("|") && i + 1 < lines.size) {
            val nextLine = lines[i + 1].trim()
            if (nextLine.matches(Regex("\\|\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|"))) {
                flushText()
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                    tableLines.add(lines[i])
                    i++
                }
                parts.add(ContentPart("table", content = tableLines.joinToString("\n")))
                continue
            }
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
    modifier: Modifier = Modifier
) {
    val parts = parseContent(content)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (part in parts) {
            when (part.type) {
                "text" -> {
                    if (containsMath(part.content)) {
                        MathRenderer(content = part.content)
                    } else {
                        MarkdownText(markdown = part.content, color = color, fontSize = fontSize, lineHeight = fontSize + 7)
                    }
                }
                "code" -> CodeBlock(code = part.content, language = part.language, isDarkTheme = isDarkTheme)
                "table" -> MarkdownTable(tableText = part.content, isDarkTheme = isDarkTheme)
                "divider" -> HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                "katex", "latex", "tex", "mathml", "asciimath" -> MathRenderer(content = part.content)
                else -> RichRenderer(content = part.content, type = part.type)
            }
        }
    }
}

private fun containsMath(text: String): Boolean {
    return text.contains(Regex("(?<!\\\\)\\$[^\\$]+\\$")) || text.contains("$$")
}

@Composable
fun MarkdownTable(tableText: String, isDarkTheme: Boolean) {
    val lines = tableText.lines().filter { it.trim().isNotBlank() }
    if (lines.size < 2) return

    val headerCells = parseTableRow(lines[0])
    val dataLines = lines.drop(2)
    val allRows = listOf(headerCells) + dataLines.map { parseTableRow(it) }
    val colCount = allRows.maxOf { it.size }

    val borderColor = if (isDarkTheme) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val headerBg = if (isDarkTheme) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val rowBg = if (isDarkTheme) MaterialTheme.colorScheme.surface.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
    val altRowBg = if (isDarkTheme) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
    val textColor = if (isDarkTheme) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(8.dp))
    ) {
        // Header
        Row(modifier = Modifier.background(headerBg)) {
            for (col in 0 until colCount) {
                val cellText = headerCells.getOrElse(col) { "" }.trim()
                Box(modifier = Modifier.weight(1f).border(0.5.dp, borderColor)) {
                    Text(
                        text = cellText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
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
                    Box(modifier = Modifier.weight(1f).border(0.5.dp, borderColor)) {
                        Text(
                            text = cellText,
                            fontSize = 13.sp,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun parseTableRow(line: String): List<String> {
    return line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split("|")
}

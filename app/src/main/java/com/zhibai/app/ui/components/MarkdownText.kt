package com.zhibai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.data.model.Annotation

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: Int = 15,
    lineHeight: Int = 22,
    studyMode: Boolean = false,
    annotations: List<Annotation> = emptyList(),
    onOpenUrl: ((String) -> Unit)? = null
) {
    val annotatedString = remember(markdown, color, fontSize, lineHeight, studyMode, annotations, onOpenUrl) {
        buildAnnotatedString {
            parseMarkdown(markdown, color, fontSize.sp, lineHeight.sp, annotations, onOpenUrl)
        }
    }

    val customTypeface = com.zhibai.app.ui.theme.LocalCustomTypeface.current

    // SelectionContainer swallows LinkAnnotation taps — skip it when references need clicks.
    val body: @Composable () -> Unit = {
        if (customTypeface != null && !studyMode) {
            TypefaceText(
                text = annotatedString,
                color = color,
                fontSize = fontSize.sp,
                lineHeight = lineHeight.sp,
                typeface = customTypeface,
                modifier = modifier
            )
        } else {
            Text(
                text = annotatedString,
                modifier = modifier,
                color = color,
                fontSize = fontSize.sp,
                lineHeight = lineHeight.sp,
                fontFamily = if (studyMode) FontFamily.Serif else FontFamily.Default,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineBreak = LineBreak.Paragraph
                )
            )
        }
    }

    if (onOpenUrl != null && annotations.isNotEmpty()) {
        body()
    } else {
        SelectionContainer { body() }
    }
}

private fun AnnotatedString.Builder.parseMarkdown(
    text: String,
    baseColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    annotations: List<Annotation> = emptyList(),
    onOpenUrl: ((String) -> Unit)? = null
) {
    val lines = text.split("\n")
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Code block
        if (line.trimStart().startsWith("```")) {
            val codeLines = mutableListOf<String>()
            val language = line.trimStart().removePrefix("```").trim()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++ // Skip closing ```

            // Add code block
            pushStyle(SpanStyle(
                background = baseColor.copy(alpha = 0.1f),
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize * 0.9f
            ))
            append("\n")
            codeLines.forEachIndexed { index, codeLine ->
                append(codeLine)
                if (index < codeLines.size - 1) append("\n")
            }
            append("\n")
            pop()
            if (i < lines.size) append("\n")
            continue
        }

        // GFM table: header row + |---| separator + body rows
        // (shared normalizer accepts fullwidth ｜ and en/em dashes)
        if (normalizeTableChars(line).contains("|") && i + 1 < lines.size &&
            isTableSeparatorLine(lines[i + 1])
        ) {
            val header = splitTableRow(line)
            val body = mutableListOf<List<String>>()
            i += 2 // skip header + separator
            while (i < lines.size && lines[i].trim().isNotBlank() &&
                normalizeTableChars(lines[i]).contains("|")
            ) {
                body.add(splitTableRow(lines[i]))
                i++
            }

            val colCount = maxOf(header.size, body.maxOfOrNull { it.size } ?: 0)
            if (colCount > 0) {
                val widths = IntArray(colCount)
                val allRows = listOf(header) + body
                for (row in allRows) {
                    for (c in 0 until colCount) {
                        val len = visibleWidth(row.getOrNull(c) ?: "").coerceAtMost(60)
                        if (len > widths[c]) widths[c] = len
                    }
                }

                append("\n")
                renderTableRow(header, widths, colCount, true, baseColor, fontSize, annotations, onOpenUrl)
                pushStyle(SpanStyle(color = baseColor.copy(alpha = 0.55f)))
                append(widths.joinToString("─┼─") { "─".repeat(it.coerceAtLeast(2)) })
                pop()
                append("\n")
                for (row in body) {
                    renderTableRow(row, widths, colCount, false, baseColor, fontSize, annotations, onOpenUrl)
                }
                append("\n")
            }
            continue
        }

        // Heading
        when {
            line.startsWith("### ") -> {
                pushStyle(SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize * 1.1f
                ))
                parseInlineMarkdown(line.removePrefix("### "), baseColor, fontSize, annotations, onOpenUrl)
                pop()
                append("\n")
            }
            line.startsWith("## ") -> {
                pushStyle(SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize * 1.2f
                ))
                parseInlineMarkdown(line.removePrefix("## "), baseColor, fontSize, annotations, onOpenUrl)
                pop()
                append("\n")
            }
            line.startsWith("# ") -> {
                pushStyle(SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize * 1.3f
                ))
                parseInlineMarkdown(line.removePrefix("# "), baseColor, fontSize, annotations, onOpenUrl)
                pop()
                append("\n")
            }
            // List item
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val indent = line.length - line.trimStart().length
                append("  ".repeat(indent / 2))
                append("• ")
                parseInlineMarkdown(line.trimStart().removePrefix("- ").removePrefix("* "), baseColor, fontSize, annotations, onOpenUrl)
                append("\n")
            }
            // Numbered list
            line.trimStart().matches(Regex("^\\d+\\. .*")) -> {
                val indent = line.length - line.trimStart().length
                append("  ".repeat(indent / 2))
                val number = line.trimStart().substringBefore(".")
                append("$number. ")
                parseInlineMarkdown(line.trimStart().substringAfter(". "), baseColor, fontSize, annotations, onOpenUrl)
                append("\n")
            }
            // Blockquote
            line.trimStart().startsWith("> ") || line.trimStart() == ">" -> {
                pushStyle(SpanStyle(
                    color = baseColor.copy(alpha = 0.7f),
                    fontStyle = FontStyle.Italic
                ))
                append("  │ ")
                val quoteText = if (line.trimStart().startsWith("> ")) line.trimStart().removePrefix("> ") else ""
                parseInlineMarkdown(quoteText, baseColor.copy(alpha = 0.7f), fontSize, annotations, onOpenUrl)
                pop()
                append("\n")
            }
            // Horizontal rule - skip, handled by RichMessageContent
            line.trim() == "---" || line.trim() == "***" || line.trim() == "___" -> {
                // Don't render here, just skip
            }
            // Normal text
            else -> {
                parseInlineMarkdown(line, baseColor, fontSize, annotations, onOpenUrl)
                append("\n")
            }
        }
        i++
    }
}

private fun splitTableRow(row: String): List<String> {
    var s = normalizeTableChars(row.trim())
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    return s.split(Regex("(?<!\\\\)\\|")).map { it.replace("\\|", "|").trim() }
}

private fun visibleWidth(s: String): Int {
    var w = 0
    for (ch in s) {
        w += if (ch.code > 0x2E80) 2 else 1
    }
    return w
}

private fun AnnotatedString.Builder.renderTableRow(
    row: List<String>,
    widths: IntArray,
    colCount: Int,
    bold: Boolean,
    baseColor: Color,
    fontSize: TextUnit,
    annotations: List<Annotation> = emptyList(),
    onOpenUrl: ((String) -> Unit)? = null
) {
    pushStyle(SpanStyle(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    for (c in 0 until colCount) {
        val raw = (row.getOrNull(c) ?: "").take(60)
        parseInlineMarkdown(raw, baseColor, fontSize, annotations, onOpenUrl)
        if (c < colCount - 1) {
            val pad = (widths[c] - visibleWidth(raw)).coerceAtLeast(0)
            append(" ".repeat(pad))
            append(" │ ")
        }
    }
    pop()
    append("\n")
}

private fun AnnotatedString.Builder.parseInlineMarkdown(
    text: String,
    baseColor: Color,
    fontSize: TextUnit,
    annotations: List<Annotation> = emptyList(),
    onOpenUrl: ((String) -> Unit)? = null
) {
    var i = 0
    while (i < text.length) {
        when {
            // [reference:N] → small gray superscript chip, tap opens source
            text.startsWith("[reference:", i) -> {
                val close = text.indexOf(']', i)
                val n = if (close > i) {
                    text.substring(i + "[reference:".length, close).toIntOrNull()
                } else null
                if (n != null && n >= 1) {
                    val url = annotations.getOrNull(n - 1)?.url
                    val label = n.toString()
                    if (url != null && onOpenUrl != null) {
                        val link = LinkAnnotation.Url(url) { _ -> onOpenUrl(url) }
                        withLink(link) {
                            pushStyle(SpanStyle(
                                color = Color(0xFF8A8A8A),
                                background = Color(0xFF8A8A8A).copy(alpha = 0.18f),
                                fontSize = fontSize * 0.62f,
                                fontWeight = FontWeight.Medium,
                                baselineShift = BaselineShift.Superscript
                            ))
                            append(" $label ")
                            pop()
                        }
                    } else {
                        pushStyle(SpanStyle(
                            color = Color(0xFF8A8A8A),
                            background = Color(0xFF8A8A8A).copy(alpha = 0.18f),
                            fontSize = fontSize * 0.62f,
                            fontWeight = FontWeight.Medium,
                            baselineShift = BaselineShift.Superscript
                        ))
                        append(" $label ")
                        pop()
                    }
                    i = close + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            // Bold **text** or __text__
            i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            i + 1 < text.length && text[i] == '_' && text[i + 1] == '_' -> {
                val end = text.indexOf("__", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            // Italic *text* or _text_
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            text[i] == '_' && (i == 0 || text[i - 1] == ' ') -> {
                val end = text.indexOf('_', i + 1)
                if (end != -1 && (end == text.length - 1 || text[end + 1] == ' ')) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            // Inline code `code`
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(
                        background = baseColor.copy(alpha = 0.1f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize * 0.9f
                    ))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            // Link [text](url)
            text[i] == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        val linkText = text.substring(i + 1, closeBracket)
                        val url = text.substring(closeBracket + 2, closeParen)
                        pushStringAnnotation(tag = "URL", annotation = url)
                        pushStyle(SpanStyle(
                            color = baseColor.copy(alpha = 0.8f),
                            textDecoration = TextDecoration.Underline
                        ))
                        append(linkText)
                        pop()
                        pop()
                        i = closeParen + 1
                    } else {
                        append(text[i])
                        i++
                    }
                } else {
                    append(text[i])
                    i++
                }
            }
            // Strikethrough ~~text~~
            i + 1 < text.length && text[i] == '~' && text[i + 1] == '~' -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            // Regular character
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

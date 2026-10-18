package com.mimo.app.ui.components

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: Int = 15,
    lineHeight: Int = 22
) {
    val annotatedString = buildAnnotatedString {
        parseMarkdown(markdown, color, fontSize.sp, lineHeight.sp)
    }

    SelectionContainer {
        Text(
            text = annotatedString,
            modifier = modifier,
            color = color,
            fontSize = fontSize.sp,
            lineHeight = lineHeight.sp
        )
    }
}

private fun AnnotatedString.Builder.parseMarkdown(
    text: String,
    baseColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit
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

        // Heading
        when {
            line.startsWith("### ") -> {
                pushStyle(SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize * 1.1f
                ))
                parseInlineMarkdown(line.removePrefix("### "), baseColor, fontSize)
                pop()
                append("\n")
            }
            line.startsWith("## ") -> {
                pushStyle(SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize * 1.2f
                ))
                parseInlineMarkdown(line.removePrefix("## "), baseColor, fontSize)
                pop()
                append("\n")
            }
            line.startsWith("# ") -> {
                pushStyle(SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize * 1.3f
                ))
                parseInlineMarkdown(line.removePrefix("# "), baseColor, fontSize)
                pop()
                append("\n")
            }
            // List item
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val indent = line.length - line.trimStart().length
                append("  ".repeat(indent / 2))
                append("• ")
                parseInlineMarkdown(line.trimStart().removePrefix("- ").removePrefix("* "), baseColor, fontSize)
                append("\n")
            }
            // Numbered list
            line.trimStart().matches(Regex("^\\d+\\. .*")) -> {
                val indent = line.length - line.trimStart().length
                append("  ".repeat(indent / 2))
                val number = line.trimStart().substringBefore(".")
                append("$number. ")
                parseInlineMarkdown(line.trimStart().substringAfter(". "), baseColor, fontSize)
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
                parseInlineMarkdown(quoteText, baseColor.copy(alpha = 0.7f), fontSize)
                pop()
                append("\n")
            }
            // Horizontal rule - skip, handled by RichMessageContent
            line.trim() == "---" || line.trim() == "***" || line.trim() == "___" -> {
                // Don't render here, just skip
            }
            // Normal text
            else -> {
                parseInlineMarkdown(line, baseColor, fontSize)
                append("\n")
            }
        }
        i++
    }
}

private fun AnnotatedString.Builder.parseInlineMarkdown(
    text: String,
    baseColor: Color,
    fontSize: TextUnit
) {
    var i = 0
    while (i < text.length) {
        when {
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

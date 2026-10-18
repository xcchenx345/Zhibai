package com.mimo.app.data.api

/**
 * Filter message content for TTS reading.
 * Removes code blocks, charts, lists, URLs, markdown formatting, etc.
 * Returns only readable prose text.
 */
fun filterForSpeech(text: String): String {
    val lines = text.lines()
    val result = StringBuilder()
    var inCodeBlock = false
    var inList = false

    for (line in lines) {
        val trimmed = line.trim()

        // Skip code blocks entirely
        if (trimmed.startsWith("```")) {
            inCodeBlock = !inCodeBlock
            continue
        }
        if (inCodeBlock) continue

        // Skip mermaid/plotly/vega/graphviz blocks
        if (trimmed.startsWith("<!--") && trimmed.endsWith("-->")) continue

        // Skip horizontal rules
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") continue

        // Skip image links
        if (trimmed.matches(Regex("!\\[.*]\\(.*\\)"))) continue

        // Skip pure URLs
        if (trimmed.matches(Regex("https?://\\S+"))) continue

        // Skip list items (-, *, 1.)
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.matches(Regex("^\\d+\\. .*"))) {
            // Extract just the text content, remove list markers
            val content = trimmed
                .removePrefix("- ")
                .removePrefix("* ")
                .replace(Regex("^\\d+\\. "), "")
            if (content.isNotBlank()) {
                val cleaned = cleanInlineMarkdown(content)
                if (cleaned.isNotBlank()) {
                    result.append(cleaned).append(" ")
                }
            }
            continue
        }

        // Skip heading markers but keep text
        if (trimmed.startsWith("#")) {
            val content = trimmed.replace(Regex("^#+\\s*"), "")
            if (content.isNotBlank()) {
                result.append(cleanInlineMarkdown(content)).append(" ")
            }
            continue
        }

        // Regular text
        if (trimmed.isNotBlank()) {
            val cleaned = cleanInlineMarkdown(trimmed)
            if (cleaned.isNotBlank()) {
                result.append(cleaned).append(" ")
            }
        }
    }

    return result.toString().trim()
}

private fun cleanInlineMarkdown(text: String): String {
    return text
        // Remove images
        .replace(Regex("!\\[.*?]\\(.*?\\)"), "")
        // Remove links but keep text
        .replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
        // Remove bold/italic markers
        .replace(Regex("\\*\\*\\*?(.*?)\\*\\*\\*?"), "$1")
        .replace(Regex("___?(.*?)___?"), "$1")
        // Remove inline code
        .replace(Regex("`([^`]+)`"), "$1")
        // Remove strikethrough
        .replace(Regex("~~(.*?)~~"), "$1")
        // Remove HTML tags
        .replace(Regex("<[^>]+>"), "")
        // Clean up extra spaces
        .replace(Regex("\\s+"), " ")
        .trim()
}

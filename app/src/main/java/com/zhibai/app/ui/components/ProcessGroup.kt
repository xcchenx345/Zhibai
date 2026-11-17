package com.zhibai.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.data.model.ChatMessage
import com.zhibai.app.i18n.LangManager
import com.zhibai.app.ui.theme.MiMoColors

/**
 * 过程折叠组 (MiMo official style): one 「共用时 N 秒」header collapses the whole
 * run timeline — thinking rows, search rows, tool operations — and expanding it
 * lists every operation as its own tappable row (dotted separators), each with
 * its own expandable detail.
 */
@Composable
fun ProcessGroupCard(
    messages: List<ChatMessage>,
    fontSize: Int,
    isDarkTheme: Boolean
) {
    if (messages.isEmpty()) return
    val textColor = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText
    val secondaryColor = textColor.copy(alpha = 0.65f)

    val isStreaming = messages.any { it.isStreaming }
    var expanded by remember(messages.first().id) { mutableStateOf(isStreaming) }

    // 共用时: wall-clock span of the group, at least 1s
    val totalSec = remember(messages) {
        val ts = messages.map { it.timestamp }
        (((ts.max() - ts.min()) / 1000L).toInt()).coerceAtLeast(1)
    }

    // Flatten rows: step rows as-is; tool cards → one op row each; tool_group JSON → op rows
    val rows = remember(messages) { buildGroupRows(messages) }

    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "groupChevron"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isDarkTheme) MiMoColors.ThinkingBgDark else MiMoColors.ThinkingBg
            )
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        // Summary header — tap to fold the whole timeline
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp)
        ) {
            Text(
                LangManager.t("step.totalTime", "共用时 {sec} 秒").replace("{sec}", totalSec.toString()),
                fontSize = (fontSize - 4).sp,
                color = secondaryColor
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) LangManager.t("chat.collapse", "收起") else LangManager.t("common.expand", "展开"),
                modifier = Modifier
                    .size(14.dp)
                    .rotate(chevron),
                tint = secondaryColor
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(100))
        ) {
            Column {
                rows.forEachIndexed { index, row ->
                    DottedDivider(color = secondaryColor)
                    GroupRow(row = row, fontSize = fontSize, textColor = textColor, secondaryColor = secondaryColor)
                    if (index == rows.lastIndex) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

/** One flattened operation row inside the group. */
private data class GroupRowModel(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val running: Boolean
)

/** Matches both the zh-cn wording (persisted history) and the current language's
 *  step.* values, so icon picking survives language switches. */
internal fun stepRowIcon(content: String): ImageVector {
    fun prefix(key: String, zhFallback: String): String =
        LangManager.t(key, zhFallback).substringBefore("{").trim()
    val think = listOf("思考", LangManager.t("step.thinking", "正在思考"), LangManager.t("step.thought", "已深度思考"))
    val search = listOf("搜索", prefix("step.search", "搜索 {n} 个关键词，参考 {m} 篇资料"))
    val browse = listOf("浏览", prefix("step.fetch", "浏览页面，参考了 {n} 篇资料"))
    return when {
        think.any { content.contains(it) } -> Icons.Default.Lightbulb
        search.any { it.isNotEmpty() && content.startsWith(it) } -> Icons.Default.Search
        browse.any { it.isNotEmpty() && content.startsWith(it) } -> Icons.Default.Public
        else -> Icons.Default.Description
    }
}

private fun buildGroupRows(messages: List<ChatMessage>): List<GroupRowModel> {
    val rows = mutableListOf<GroupRowModel>()
    for (m in messages) {
        when (m.role) {
            "step" -> rows.add(
                GroupRowModel(
                    icon = stepRowIcon(m.content),
                    title = m.content,
                    detail = m.reasoningContent,
                    running = m.isStreaming
                )
            )
            "tool" -> {
                val (legacyTitle, legacyResult) = parseLegacyTool(m.content)
                rows.add(
                    GroupRowModel(
                        icon = iconForToolKey(m.toolIcon),
                        title = m.toolTitle ?: legacyTitle,
                        detail = legacyResult,
                        running = m.toolStatus == "running"
                    )
                )
            }
            "tool_group" -> {
                // Flatten the collapsed ops JSON into individual rows
                try {
                    val arr = com.google.gson.JsonParser.parseString(m.content).asJsonArray
                    for (el in arr) {
                        val o = el.asJsonObject
                        rows.add(
                            GroupRowModel(
                                icon = iconForToolKey(null),
                                title = o.get("tool")?.asString ?: LangManager.t("step.opFallback", "操作"),
                                detail = o.get("result")?.asString ?: "",
                                running = false
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }
    return rows
}

private fun parseLegacyTool(content: String): Pair<String, String> {
    val match = Regex("^\\[([^\\]]+)\\]").find(content)
    return (match?.groupValues?.get(1) ?: LangManager.t("step.toolFallback", "工具")) to
        content.replaceFirst(Regex("^\\[[^\\]]+\\]\\s*"), "")
}

private fun iconForToolKey(key: String?): ImageVector = when (key) {
    "shell" -> Icons.Default.Description
    "android" -> Icons.Default.PhoneAndroid
    "web" -> Icons.Default.Public
    "search" -> Icons.Default.Search
    "time" -> Icons.Default.Description
    "subagent" -> Icons.Default.AccountTree
    else -> Icons.Default.Description
}

@Composable
private fun GroupRow(
    row: GroupRowModel,
    fontSize: Int,
    textColor: androidx.compose.ui.graphics.Color,
    secondaryColor: androidx.compose.ui.graphics.Color
) {
    var open by remember(row.title) { mutableStateOf(false) }
    val chevron by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = tween(180),
        label = "rowChevron"
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 8.dp)
        ) {
            Icon(
                if (row.running) Icons.Default.HourglassEmpty else row.icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = secondaryColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                row.title,
                fontSize = (fontSize - 3).sp,
                color = textColor.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = if (open) LangManager.t("chat.collapse", "收起") else LangManager.t("common.expand", "展开"),
                modifier = Modifier
                    .size(13.dp)
                    .rotate(chevron),
                tint = secondaryColor
            )
        }
        if (open) {
            // 表内自动下拉: follow the growing detail while it streams
            val detail = row.detail
            val scrollState = rememberScrollState()
            LaunchedEffect(detail.length, row.running) {
                if (row.running) scrollState.scrollTo(scrollState.maxValue)
            }
            Box(
                modifier = Modifier
                    .padding(start = 21.dp, end = 8.dp, bottom = 8.dp)
                    .heightIn(max = 240.dp)
                    .verticalScroll(scrollState)
            ) {
                if (detail.isBlank()) {
                    Text(
                        if (row.running) LangManager.t("step.running", "进行中…") else LangManager.t("step.noDetail", "无详情"),
                        fontSize = (fontSize - 5).sp,
                        color = secondaryColor.copy(alpha = 0.7f)
                    )
                } else {
                    Text(
                        detail,
                        fontSize = (fontSize - 4).sp,
                        fontFamily = FontFamily.Monospace,
                        color = secondaryColor,
                        lineHeight = (fontSize).sp
                    )
                }
            }
        }
    }
}

/** Dotted rule between operation rows (the reference design). */
@Composable
private fun DottedDivider(color: androidx.compose.ui.graphics.Color) {
    val dashColor = color.copy(alpha = 0.25f)
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        val dash = 4.dp.toPx()
        val gap = 4.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = dashColor,
                start = androidx.compose.ui.geometry.Offset(x, size.height / 2),
                end = androidx.compose.ui.geometry.Offset(
                    minOf(x + dash, size.width), size.height / 2
                ),
                strokeWidth = 1.dp.toPx()
            )
            x += dash + gap
        }
    }
}

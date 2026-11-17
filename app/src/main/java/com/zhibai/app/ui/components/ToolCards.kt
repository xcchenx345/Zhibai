package com.zhibai.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.data.model.ChatMessage
import com.zhibai.app.i18n.LangManager
import com.zhibai.app.ui.theme.MiMoColors

private val AddGreen = Color(0xFF2E9E4F)
private val DelRed = Color(0xFFCF3A3A)

/** Map an icon key (see toolIconKey) to a Material icon. */
private fun iconFor(key: String?): ImageVector = when (key) {
    "shell" -> Icons.Default.Terminal
    "android" -> Icons.Default.Android
    "file" -> Icons.Default.Description
    "file_edit" -> Icons.Default.Edit
    "file_del" -> Icons.Default.Delete
    "web" -> Icons.Default.Public
    "search" -> Icons.Default.Search
    "device" -> Icons.Default.PhoneAndroid
    "time" -> Icons.Default.Schedule
    "skill" -> Icons.Default.Build
    "task" -> Icons.Default.TaskAlt
    "subagent" -> Icons.Default.AccountTree
    "phone" -> Icons.Default.Phone
    "security" -> Icons.Default.Lock
    "fun" -> Icons.Default.AcUnit
    else -> Icons.Default.Info
}

/** Parse legacy "[tool] result" content into (title, result). */
private fun parseLegacy(content: String): Pair<String, String> {
    val match = Regex("^\\[([^\\]]+)\\]").find(content)
    return (match?.groupValues?.get(1) ?: "Tool") to
        content.replaceFirst(Regex("^\\[[^\\]]+\\]\\s*"), "")
}

/**
 * One tool-call card:
 *  - collapsed title = what the tool IS DOING (not the generic "Tool"),
 *  - tool-type icon (shell / android / file / …), hourglass while running,
 *  - pulsing glow border while running,
 *  - live output visible while running (no waiting for completion),
 *  - tap to expand the full output.
 */
@Composable
fun ToolCard(
    message: ChatMessage,
    fontSize: Int,
    isDarkTheme: Boolean
) {
    val running = message.toolStatus == "running"
    val (legacyTitle, legacyResult) = remember(message.id, message.content) { parseLegacy(message.content) }
    val title = message.toolTitle ?: legacyTitle
    val result = message.content
    val live = message.toolLive.orEmpty()
    var expanded by remember(message.id) { mutableStateOf(false) }

    val textColor = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText
    val bg = if (isDarkTheme) MiMoColors.ThinkingBgDark else MiMoColors.ThinkingBg

    // Pulsing glow while the tool is executing.
    val glow by rememberInfiniteTransition(label = "toolGlow").animateFloat(
        initialValue = 0.15f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "toolGlowAnim"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(
                if (running) Modifier.background(
                    Color.White.copy(alpha = if (isDarkTheme) glow * 0.10f else 0f),
                    RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Icon tile
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(textColor.copy(alpha = if (running) glow * 0.35f + 0.15f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (running) Icons.Default.HourglassEmpty else iconFor(message.toolIcon),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = textColor.copy(alpha = 0.9f)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = (fontSize - 2).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        running -> LangManager.t("tool.running", "运行中")
                        message.toolStatus == "error" -> LangManager.t("tool.failed", "执行失败")
                        message.toolStatus == "ok" -> LangManager.t("agent.taskDone", "已完成")
                        else -> legacyResult.firstLine()
                    },
                    fontSize = (fontSize - 5).sp,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = textColor.copy(alpha = 0.7f)
            )
        }

        // Live output — visible while running without expanding (first lines, fade by clamp).
        if (running && live.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = if (isDarkTheme) 0.25f else 0.06f))
                    .padding(8.dp)
            ) {
                Text(
                    text = live.takeLast(600),
                    fontSize = (fontSize - 5).sp,
                    fontFamily = FontFamily.Monospace,
                    color = textColor.copy(alpha = 0.75f),
                    lineHeight = (fontSize - 1).sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (expanded && !running && result.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = result,
                    fontSize = (fontSize - 3).sp,
                    fontFamily = if (result.contains('\n')) FontFamily.Monospace else FontFamily.Default,
                    color = textColor.copy(alpha = 0.85f),
                    lineHeight = (fontSize + 2).sp
                )
            }
        } else if (expanded && running && live.isBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                LangManager.t("tool.waitingOutput", "等待输出…"),
                fontSize = (fontSize - 4).sp,
                color = textColor.copy(alpha = 0.55f)
            )
        }
    }
}

/**
 * End-of-run file-change summary card:
 * 「已更改 N 个文件，+X -Y」with 撤销 / 打开查看 actions and the affected-file list.
 * role = "tool_files", content = JSON [{"path","added","removed"},…]
 */
@Composable
fun FileChangesCard(
    message: ChatMessage,
    fontSize: Int,
    isDarkTheme: Boolean,
    onUndo: (() -> Unit)? = null
) {
    val changes = remember(message.id, message.content) {
        try {
            com.google.gson.JsonParser.parseString(message.content).asJsonArray.map { el ->
                val o = el.asJsonObject
                Triple(
                    o.get("path")?.asString ?: "",
                    o.get("added")?.asInt ?: 0,
                    o.get("removed")?.asInt ?: 0
                )
            }
        } catch (_: Exception) { emptyList() }
    }
    if (changes.isEmpty()) return
    val totalAdded = changes.sumOf { it.second }
    val totalRemoved = changes.sumOf { it.third }
    var expanded by remember(message.id) { mutableStateOf(false) }
    var undone by remember(message.id) { mutableStateOf(false) }
    var reviewing by remember(message.id) { mutableStateOf(false) }

    val textColor = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText
    val bg = if (isDarkTheme) MiMoColors.ThinkingBgDark else MiMoColors.ThinkingBg

    Column(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(textColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = textColor.copy(alpha = 0.9f)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (undone)
                        LangManager.t("tool.filesUndone", "已撤销 {n} 个文件的更改").replace("{n}", changes.size.toString())
                    else
                        LangManager.t("tool.filesChanged", "已更改 {n} 个文件").replace("{n}", changes.size.toString()),
                    fontSize = (fontSize - 2).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    Text(
                        text = "+$totalAdded",
                        fontSize = (fontSize - 5).sp,
                        color = AddGreen,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "-$totalRemoved",
                        fontSize = (fontSize - 5).sp,
                        color = DelRed,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { expanded = !expanded },
                tint = textColor.copy(alpha = 0.7f)
            )
        }

        // Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CardActionButton(
                text = if (undone) LangManager.t("tool.undone", "已撤销") else LangManager.t("tool.undo", "撤销"),
                enabled = !undone && onUndo != null,
                textColor = textColor,
                onClick = {
                    if (!undone && onUndo != null) {
                        onUndo()
                        undone = true
                    }
                }
            )
            CardActionButton(
                text = if (reviewing) LangManager.t("tool.collapseFiles", "收起文件") else LangManager.t("tool.openReview", "打开查看（审阅）"),
                enabled = true,
                textColor = textColor,
                onClick = { reviewing = !reviewing }
            )
        }

        if (expanded || reviewing) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                changes.forEach { (path, added, removed) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Text(
                            text = path.substringAfterLast('/').ifBlank { path },
                            fontSize = (fontSize - 4).sp,
                            color = textColor.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (added > 0) {
                            Text("+$added", fontSize = (fontSize - 5).sp, color = AddGreen)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (removed > 0) {
                            Text("-$removed", fontSize = (fontSize - 5).sp, color = DelRed)
                        }
                    }
                    Text(
                        text = path,
                        fontSize = (fontSize - 6).sp,
                        color = textColor.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (reviewing) {
            Spacer(modifier = Modifier.height(8.dp))
            changes.forEach { (path, _, _) ->
                FileReviewRow(path = path, fontSize = fontSize, isDarkTheme = isDarkTheme)
            }
        }
    }
}

@Composable
private fun CardActionButton(
    text: String,
    enabled: Boolean,
    textColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(textColor.copy(alpha = if (enabled) 0.12f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = textColor.copy(alpha = if (enabled) 0.9f else 0.4f)
        )
    }
}

/** One affected file: tap to fold/unfold its current on-disk content for review. */
@Composable
private fun FileReviewRow(path: String, fontSize: Int, isDarkTheme: Boolean) {
    var open by remember(path) { mutableStateOf(false) }
    val textColor = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText
    val content = remember(open, LangManager.currentCode) {
        if (!open) "" else try {
            val f = java.io.File(path)
            if (f.exists() && f.length() < 200_000) f.readText()
            else LangManager.t("tool.fileMissing", "(文件不存在或过大)")
        } catch (e: Exception) {
            LangManager.t("tool.fileReadError", "(无法读取: {error})").replace("{error}", e.message ?: "")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = if (isDarkTheme) 0.2f else 0.05f))
            .clickable { open = !open }
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = path.substringAfterLast('/'),
                fontSize = (fontSize - 4).sp,
                fontWeight = FontWeight.Medium,
                color = textColor.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (open) LangManager.t("chat.collapse", "收起") else LangManager.t("code.view", "查看"),
                fontSize = (fontSize - 5).sp,
                color = textColor.copy(alpha = 0.55f)
            )
        }
        if (open) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = content,
                fontSize = (fontSize - 5).sp,
                fontFamily = FontFamily.Monospace,
                color = textColor.copy(alpha = 0.75f),
                lineHeight = (fontSize - 1).sp
            )
        }
    }
}

private fun String.firstLine(): String = lineSequence().firstOrNull()?.take(80) ?: ""

/**
 * File presentation card (role="file_card", show_file tool): type badge + icon +
 * filename + relative time, tap opens the in-app viewer. Style matches 产物中心.
 */
@Composable
fun FileCard(
    message: ChatMessage,
    fontSize: Int,
    isDarkTheme: Boolean,
    onOpen: (String) -> Unit = {}
) {
    val path = message.fileCardPath ?: return
    val file = remember(path) { java.io.File(path) }
    if (!file.exists()) return
    val ext = file.extension.lowercase()
    val (kindLabel, icon) = when (ext) {
        "jpg", "jpeg", "png", "webp", "gif" -> LangManager.t("file.type.image", "图片") to Icons.Default.Image
        "mp4", "mkv", "webm", "3gp", "mov" -> LangManager.t("file.type.video", "视频") to Icons.Default.VideoFile
        "mp3", "wav", "ogg", "m4a", "aac", "flac" -> LangManager.t("file.type.audio", "音频") to Icons.Default.AudioFile
        "html", "htm" -> LangManager.t("file.type.page", "落地页") to Icons.Default.Public
        "md", "txt", "pdf", "doc", "docx", "json", "kt", "java", "py", "js", "ts", "xml" -> LangManager.t("file.type.text", "文本") to Icons.Default.Description
        else -> LangManager.t("file.type.other", "文件") to Icons.Default.Description
    }
    val textColor = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText
    val bg = if (isDarkTheme) MiMoColors.ThinkingBgDark else MiMoColors.ThinkingBg
    val relTime = remember(message.timestamp, LangManager.currentCode) {
        val diff = System.currentTimeMillis() - message.timestamp
        when {
            diff < 60_000 -> LangManager.t("time.justNow", "刚刚")
            diff < 3_600_000 -> LangManager.t("time.minutesAgo", "{n} 分钟前").replace("{n}", (diff / 60_000).toString())
            diff < 86_400_000 -> LangManager.t("time.hoursAgo", "{n} 小时前").replace("{n}", (diff / 3_600_000).toString())
            else -> LangManager.t("time.daysAgo", "{n} 天前").replace("{n}", (diff / 86_400_000).toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onOpen(path) }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(textColor.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(textColor.copy(alpha = 0.7f))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(kindLabel, fontSize = 9.sp, color = textColor.copy(alpha = 0.8f))
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = textColor.copy(alpha = 0.8f)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            file.name,
            fontSize = (fontSize - 2).sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            relTime,
            fontSize = (fontSize - 6).sp,
            color = textColor.copy(alpha = 0.55f)
        )
    }
}

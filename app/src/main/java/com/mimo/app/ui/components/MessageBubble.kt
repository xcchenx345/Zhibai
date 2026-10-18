package com.mimo.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mimo.app.data.model.Annotation
import com.mimo.app.data.model.ChatMessage
import com.mimo.app.ui.theme.MiMoColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    isDarkTheme: Boolean,
    fontSize: Int = 15,
    modifier: Modifier = Modifier,
    onEdit: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onReadAloud: ((String) -> Unit)? = null,
    isSpeaking: Boolean = false
) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isToolGroup = message.role == "tool_group"
    var showContextMenu by remember { mutableStateOf(false) }
    var showRawTextDialog by remember { mutableStateOf(false) }
    var thinkingExpanded by remember { mutableStateOf(message.isStreaming) }
    var toolExpanded by remember { mutableStateOf(false) }
    var groupExpanded by remember { mutableStateOf(false) }
    var expandedStepIndex by remember { mutableStateOf(-1) }

    // Parse tool message: "[tool_name] result"
    val toolName = if (isTool) {
        val match = Regex("^\\[([^\\]]+)\\]").find(message.content)
        match?.groupValues?.get(1) ?: "Tool"
    } else ""
    val toolResult = if (isTool) {
        message.content.replaceFirst(Regex("^\\[[^\\]]+\\]\\s*"), "")
    } else ""

    // Parse tool_group message: JSON array of {tool, result}
    val toolSteps = if (isToolGroup) {
        try {
            com.google.gson.JsonParser.parseString(message.content).asJsonArray.map { el ->
                val obj = el.asJsonObject
                (obj.get("tool")?.asString ?: "Tool") to (obj.get("result")?.asString ?: "")
            }
        } catch (_: Exception) { emptyList() }
    } else emptyList()

    // Auto-collapse when streaming ends
    LaunchedEffect(message.isStreaming) {
        if (!message.isStreaming && message.reasoningContent.isNotBlank()) {
            thinkingExpanded = false
        }
    }

    // Collapsed group of executed operations: expand → list of steps → expand step → detail
    if (isToolGroup) {
        val groupColor = if (isDarkTheme) MiMoColors.ThinkingBgDark else MiMoColors.ThinkingBg
        val textColor = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(groupColor)
                    .clickable { groupExpanded = !groupExpanded }
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Operations (${toolSteps.size})",
                        fontSize = (fontSize - 3).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (groupExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = textColor
                    )
                }
                if (groupExpanded) {
                    Spacer(modifier = Modifier.height(6.dp))
                    toolSteps.forEachIndexed { index, (stepTool, stepResult) ->
                        val stepOpen = expandedStepIndex == index
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (stepOpen) MaterialTheme.colorScheme.surface.copy(alpha = 0.4f) else Color.Transparent)
                                .clickable {
                                    expandedStepIndex = if (stepOpen) -1 else index
                                }
                                .padding(vertical = 6.dp, horizontal = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "${index + 1}. $stepTool",
                                    fontSize = (fontSize - 4).sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (stepOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = textColor.copy(alpha = 0.7f)
                                )
                            }
                            if (stepOpen && stepResult.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stepResult,
                                    fontSize = (fontSize - 3).sp,
                                    color = textColor.copy(alpha = 0.8f),
                                    lineHeight = (fontSize + 2).sp
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // Tool message: collapsible block above the answer, like thinking
    if (isTool) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDarkTheme) MiMoColors.ThinkingBgDark else MiMoColors.ThinkingBg)
                    .clickable { toolExpanded = !toolExpanded }
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = toolName,
                        fontSize = (fontSize - 3).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (toolExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText
                    )
                }
                if (toolExpanded && toolResult.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        toolResult,
                        fontSize = (fontSize - 2).sp,
                        color = if (isDarkTheme) MiMoColors.ThinkingTextDark.copy(alpha = 0.8f) else MiMoColors.ThinkingText.copy(alpha = 0.8f),
                        lineHeight = (fontSize + 3).sp
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = if (isUser) "You" else "MiMo",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        if (message.imageUri != null) {
            val imageUris = message.imageUri.split("|").filter { it.isNotBlank() }
            // Absolute file paths (persisted copies) vs content:// URIs
            fun coilModel(src: String): Any =
                if (src.startsWith("/")) java.io.File(src) else src
            if (imageUris.size > 1) {
                Row(
                    modifier = Modifier.padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    imageUris.take(5).forEach { uri ->
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(coilModel(uri)).crossfade(true).build(),
                            contentDescription = "Attached image",
                            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(coilModel(imageUris.first())).crossfade(true).build(),
                    contentDescription = "Attached image",
                    modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 280.dp).heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }

        if (message.fileName != null && message.imageUri == null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MiMoColors.Primary.copy(alpha = 0.1f),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = MiMoColors.Primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = message.fileName, fontSize = 12.sp, color = MiMoColors.Primary, maxLines = 1)
                }
            }
        }

        // Thinking block (collapsible)
        if (message.reasoningContent.isNotBlank()) {
            Column(
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDarkTheme) MiMoColors.ThinkingBgDark else MiMoColors.ThinkingBg)
                    .clickable { thinkingExpanded = !thinkingExpanded }
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Thinking",
                        fontSize = (fontSize - 3).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (thinkingExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText
                    )
                }
                if (thinkingExpanded) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        message.reasoningContent,
                        fontSize = (fontSize - 2).sp,
                        color = if (isDarkTheme) MiMoColors.ThinkingTextDark.copy(alpha = 0.8f) else MiMoColors.ThinkingText.copy(alpha = 0.8f),
                        lineHeight = (fontSize + 3).sp
                    )
                }
            }
        }

        if (message.content.isNotBlank() || message.isStreaming) {
            Box {
                if (isUser) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 2.dp),
                        color = MiMoColors.UserBubble,
                        shadowElevation = 0.dp
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(message.content.ifBlank { if (message.isStreaming) "..." else "" }, color = MiMoColors.OnPrimary, fontSize = fontSize.sp, lineHeight = (fontSize + 7).sp)
                            if (message.isStreaming) {
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier.width(40.dp).height(2.dp).clip(RoundedCornerShape(1.dp)),
                                    color = MiMoColors.OnPrimary.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                } else {
                    // No bubble for AI responses.
                    // Long-press lives on the Column (not a full-size overlay) so
                    // CodeBlock / Mermaid buttons stay clickable.
                    Column(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .combinedClickable(onClick = {}, onLongClick = { showContextMenu = true })
                    ) {
                        RichMessageContent(
                            content = message.content.ifBlank { if (message.isStreaming) "..." else "" },
                            color = if (isDarkTheme) MiMoColors.TextPrimaryDark else MiMoColors.TextPrimary,
                            isDarkTheme = isDarkTheme,
                            fontSize = fontSize
                        )
                        if (message.isStreaming) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.width(40.dp).height(2.dp).clip(RoundedCornerShape(1.dp)),
                                color = MiMoColors.Primary
                            )
                        }
                    }
                }

                DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isSpeaking) "Stop" else "Read Aloud") },
                        onClick = { showContextMenu = false; onReadAloud?.invoke(message.content) },
                        leadingIcon = { Icon(if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("复制") },
                        onClick = { showContextMenu = false; onCopy?.invoke(message.content) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                    )
                    if (!isUser) {
                        DropdownMenuItem(
                            text = { Text("选择文本") },
                            onClick = { showContextMenu = false; showRawTextDialog = true },
                            leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) }
                        )
                    }
                }
            }
        }

        if (!message.isStreaming && message.content.isNotBlank()) {
            Row(modifier = Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isUser && onEdit != null) {
                    TextButton(onClick = { onEdit(message.content) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("编辑", fontSize = 11.sp)
                    }
                }
                if (!isUser && onCopy != null) {
                    TextButton(onClick = { onCopy(message.content) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("复制", fontSize = 11.sp)
                    }
                }
            }
        }

        if (!isUser && message.annotations?.isNotEmpty() == true) {
            Spacer(modifier = Modifier.height(8.dp))
            SearchCitations(annotations = message.annotations, isDarkTheme = isDarkTheme)
        }
    }

    // Raw text dialog for selecting/copying unrendered markdown
    if (showRawTextDialog) {
        AlertDialog(
            onDismissRequest = { showRawTextDialog = false },
            title = { Text("原始文本") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = message.content,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onCopy?.invoke(message.content)
                    showRawTextDialog = false
                }) {
                    Text("复制全部")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRawTextDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
internal fun SearchCitations(annotations: List<Annotation>, isDarkTheme: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(12.dp))
            .background(if (isDarkTheme) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable { expanded = !expanded }.padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isDarkTheme) MiMoColors.TextSecondaryDark else MiMoColors.TextSecondary)
            Spacer(modifier = Modifier.width(6.dp))
            Text("搜索结果（${annotations.size}）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkTheme) MiMoColors.TextSecondaryDark else MiMoColors.TextSecondary, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (isDarkTheme) MiMoColors.TextSecondaryDark else MiMoColors.TextSecondary)
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            annotations.forEachIndexed { index, annotation ->
                if (index > 0) Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text("${index + 1}.", fontSize = 12.sp, color = if (isDarkTheme) MiMoColors.TextSecondaryDark else MiMoColors.TextSecondary, modifier = Modifier.padding(end = 6.dp, top = 1.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(annotation.title ?: "Untitled", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (isDarkTheme) MiMoColors.TextPrimaryDark else MiMoColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (annotation.siteName != null) Text(annotation.siteName, fontSize = 11.sp, color = if (isDarkTheme) MiMoColors.TextSecondaryDark else MiMoColors.TextSecondary)
                    }
                }
            }
        }
    }
}

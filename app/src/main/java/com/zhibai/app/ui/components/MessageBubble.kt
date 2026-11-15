package com.zhibai.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zhibai.app.data.model.Annotation
import com.zhibai.app.data.model.ChatMessage
import com.zhibai.app.ui.theme.MiMoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** One step inside a collapsed tool_group card; old/new present only for precise edits. */
private data class ToolStep(
    val tool: String,
    val result: String,
    val oldText: String? = null,
    val newText: String? = null
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    isDarkTheme: Boolean,
    fontSize: Int = 15,
    modifier: Modifier = Modifier,
    studyMode: Boolean = false,
    isAgent: Boolean = false,
    onEdit: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onReadAloud: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    isSpeaking: Boolean = false
) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isToolGroup = message.role == "tool_group"
    val isStep = message.role == "step"
    var showContextMenu by remember { mutableStateOf(false) }
    var showRawTextDialog by remember { mutableStateOf(false) }
    var toolExpanded by remember { mutableStateOf(false) }
    var groupExpanded by remember { mutableStateOf(false) }
    var expandedStepIndex by remember { mutableStateOf(-1) }
    var copied by remember { mutableStateOf(false) }
    // Leave-app confirm for search results / [reference:N] chips
    var pendingLeaveUrl by remember { mutableStateOf<String?>(null) }
    val appContext = LocalContext.current

    fun requestOpenUrl(url: String) {
        pendingLeaveUrl = url
    }

    // Parse tool message: "[tool_name] result"
    val toolName = if (isTool) {
        val match = Regex("^\\[([^\\]]+)\\]").find(message.content)
        match?.groupValues?.get(1) ?: "Tool"
    } else ""
    val toolResult = if (isTool) {
        message.content.replaceFirst(Regex("^\\[[^\\]]+\\]\\s*"), "")
    } else ""

    // Parse tool_group message: JSON array of {tool, result[, oldText, newText]}
    val toolSteps = if (isToolGroup) {
        try {
            com.google.gson.JsonParser.parseString(message.content).asJsonArray.map { el ->
                val obj = el.asJsonObject
                ToolStep(
                    tool = obj.get("tool")?.asString ?: "Tool",
                    result = obj.get("result")?.asString ?: "",
                    oldText = obj.get("oldText")?.asString,
                    newText = obj.get("newText")?.asString
                )
            }
        } catch (_: Exception) { emptyList() }
    } else emptyList()

    // Auto-collapse when streaming ends
    LaunchedEffect(message.isStreaming) {
        if (!message.isStreaming && message.reasoningContent.isNotBlank()) {
            // handled by ProcessFlowCard
        }
    }

    // Flat process-step row (normal chat search runs): one line per phase,
    // tap to expand the detail. Matches the reference design —
    // 「已深度思考」「搜索 N 个关键词，参考 M 篇资料」「浏览页面，参考了 N 篇资料」「正在思考」
    if (isStep) {
        ProcessStepRow(message = message, fontSize = fontSize, isDarkTheme = isDarkTheme)
        return
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
                    toolSteps.forEachIndexed { index, step ->
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
                                    text = "${index + 1}. ${step.tool}",
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
                            if (stepOpen) {
                                Spacer(modifier = Modifier.height(4.dp))
                                val old = step.oldText
                                val new = step.newText
                                if (old != null && new != null) {
                                    // Precise edit → side-by-side diff instead of plain text
                                    DiffView(oldText = old, newText = new)
                                } else if (step.result.isNotBlank()) {
                                    Text(
                                        step.result,
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

        // Process flow: independent 思考 / 搜索 / 查看页面 sections
        if (!isUser && !message.flatProcess && !HighRiskRejection.isReply(message.content) &&
            (message.reasoningContent.isNotBlank() || !message.annotations.isNullOrEmpty())
        ) {
            ProcessFlowCard(
                message = message,
                fontSize = fontSize,
                isDarkTheme = isDarkTheme,
                onOpenUrl = { requestOpenUrl(it) }
            )
        }

        if (message.content.isNotBlank() || message.isStreaming) {
            Box {
                if (isUser) {
                    val customBubble = com.zhibai.app.ui.theme.LocalCustomBubbleColor.current
                    val bubbleColor = if (customBubble != Color.Unspecified) customBubble else MiMoColors.UserBubble
                    // Auto-collapse long sent messages
                    var bubbleExpanded by remember { mutableStateOf(false) }
                    var bubbleHeightPx by remember { mutableIntStateOf(0) }
                    val density = LocalDensity.current
                    val collapsedMaxHeight = 240.dp
                    val collapsedMaxPx = with(density) { collapsedMaxHeight.toPx() }
                    val bubbleTooTall = bubbleHeightPx > collapsedMaxPx
                    val bubbleScrollState = rememberScrollState()
                    Surface(
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 2.dp),
                        color = bubbleColor,
                        shadowElevation = 0.dp
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Do NOT fillMaxWidth here — that forces the bubble to full
                            // row width. Width must wrap the text so short messages stay small.
                            Box(
                                modifier = Modifier
                                    .then(
                                        if (!bubbleExpanded) Modifier.heightIn(max = collapsedMaxHeight)
                                        else Modifier
                                    )
                                    .clipToBounds()
                            ) {
                                Column(
                                    modifier = Modifier.then(
                                        if (!bubbleExpanded) Modifier.verticalScroll(bubbleScrollState)
                                        else Modifier
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.onSizeChanged { bubbleHeightPx = it.height }
                                    ) {
                                        Text(
                                            message.content.ifBlank { if (message.isStreaming) "..." else "" },
                                            color = MiMoColors.OnPrimary,
                                            fontSize = fontSize.sp,
                                            lineHeight = (fontSize + 7).sp,
                                            fontFamily = if (studyMode) androidx.compose.ui.text.font.FontFamily.Serif else null
                                        )
                                        if (message.isStreaming) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                modifier = Modifier.width(40.dp).height(2.dp).clip(RoundedCornerShape(1.dp)),
                                                color = MiMoColors.OnPrimary.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                                // Opacity fade at the fold — same color as the bubble
                                if (!bubbleExpanded && bubbleTooTall &&
                                    bubbleScrollState.value < bubbleScrollState.maxValue
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .height(40.dp)
                                            .background(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.Transparent,
                                                        bubbleColor.copy(alpha = 0.75f),
                                                        bubbleColor
                                                    )
                                                )
                                            )
                                    )
                                }
                            }
                            if (bubbleTooTall) {
                                // Center within the bubble's own width — fillMaxWidth would
                                // stretch the Surface back to full row width.
                                Text(
                                    text = if (bubbleExpanded) "收起" else "展开全部",
                                    fontSize = 12.sp,
                                    color = MiMoColors.OnPrimary.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .clickable { bubbleExpanded = !bubbleExpanded }
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                } else {
                    // No bubble for AI responses.
                    // Long-press lives on the Column (not a full-size overlay) so
                    // CodeBlock / Mermaid buttons stay clickable.
                    // Ink-wash: streaming content fades in gradually
                    var inkAlpha by remember { mutableFloatStateOf(0.4f) }
                    LaunchedEffect(message.isStreaming) {
                        if (message.isStreaming) {
                            inkAlpha = 0.4f
                            // Gradually increase alpha as content streams in
                            while (true) {
                                kotlinx.coroutines.delay(80)
                                inkAlpha = (inkAlpha + 0.06f).coerceAtMost(1f)
                            }
                        } else {
                            inkAlpha = 1f
                        }
                    }
                    Column(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .graphicsLayer { alpha = inkAlpha }
                            .combinedClickable(onClick = {}, onLongClick = { showContextMenu = true })
                    ) {
                        RichMessageContent(
                            content = message.content.ifBlank { if (message.isStreaming) "..." else "" },
                            color = if (isDarkTheme) MiMoColors.TextPrimaryDark else MiMoColors.TextPrimary,
                            isDarkTheme = isDarkTheme,
                            fontSize = fontSize,
                            studyMode = studyMode,
                            annotations = message.annotations.orEmpty(),
                            onOpenUrl = { requestOpenUrl(it) }
                        )
                        if (message.isStreaming) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.width(40.dp).height(2.dp).clip(RoundedCornerShape(1.dp)),
                                color = MaterialTheme.colorScheme.primary
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
            val actionColor = if (studyMode) Color(0xFF6B5D48) else MaterialTheme.colorScheme.primary
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isUser && onEdit != null) {
                    TextButton(onClick = { onEdit(message.content) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(14.dp), tint = actionColor)
                    }
                }
                if (!isUser && onCopy != null && !HighRiskRejection.isReply(message.content)) {
                    // Copy button: icon-only, briefly shows checkmark after press
                    LaunchedEffect(copied) {
                        if (copied) { delay(1500); copied = false }
                    }
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val scale by animateFloatAsState(
                        targetValue = if (isPressed) 0.88f else 1f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                        label = "copyScale"
                    )
                    IconButton(
                        onClick = {
                            onCopy(message.content)
                            copied = true
                        },
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .size(28.dp)
                            .scale(scale)
                    ) {
                        Icon(
                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            modifier = Modifier.size(14.dp),
                            tint = if (copied) MiMoColors.Success else actionColor
                        )
                    }
                }
                if (!isUser && !isAgent && onRegenerate != null && !HighRiskRejection.isReply(message.content)) {
                    val interactionSource2 = remember { MutableInteractionSource() }
                    val isPressed2 by interactionSource2.collectIsPressedAsState()
                    val scale2 by animateFloatAsState(
                        targetValue = if (isPressed2) 0.88f else 1f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                        label = "regenScale"
                    )
                    IconButton(
                        onClick = { onRegenerate() },
                        interactionSource = interactionSource2,
                        modifier = Modifier
                            .size(28.dp)
                            .scale(scale2)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "重新回答",
                            modifier = Modifier.size(14.dp),
                            tint = actionColor
                        )
                    }
                }
            }
        }
    }

    // Leave-app confirm for external search / reference links
    if (pendingLeaveUrl != null) {
        val url = pendingLeaveUrl!!
        AlertDialog(
            onDismissRequest = { pendingLeaveUrl = null },
            title = { Text("即将离开应用") },
            text = {
                Text(
                    "将打开外部浏览器访问：\n\n$url\n\n是否继续？",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingLeaveUrl = null
                    try {
                        appContext.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        )
                    } catch (_: Exception) {}
                }) {
                    Text("继续", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLeaveUrl = null }) {
                    Text("取消")
                }
            }
        )
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

/**
 * Process card with independent collapsible sections:
 *  思考 → reasoning timeline
 *  搜索 → numbered search results (tap → leave-app confirm)
 *  查看页面 → browsed pages (tap → leave-app confirm)
 * Each row is collapsed by default (Think auto-opens while streaming).
 */
/**
 * One compact process row (search-run timeline): icon + label + right chevron,
 * tap to expand the detail underneath. Consecutive rows stack with hairline
 * separators like a single list.
 */
@Composable
private fun ProcessStepRow(
    message: ChatMessage,
    fontSize: Int,
    isDarkTheme: Boolean
) {
    var open by remember(message.id) { mutableStateOf(false) }
    val textColor = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText
    val secondaryColor = textColor.copy(alpha = 0.65f)
    val icon = when {
        message.content.contains("思考") -> Icons.Default.Lightbulb
        message.content.startsWith("搜索") -> Icons.Default.Search
        else -> Icons.Default.Description
    }
    val chevron by animateFloatAsState(if (open) 90f else 0f, label = "stepChevron")
    Column(modifier = Modifier.fillMaxWidth(0.92f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 7.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = secondaryColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message.content,
                fontSize = (fontSize - 3).sp,
                fontWeight = FontWeight.Medium,
                color = textColor.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = if (open) "收起" else "展开",
                modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = chevron },
                tint = secondaryColor
            )
        }
        if (open && message.reasoningContent.isNotBlank()) {
            Text(
                message.reasoningContent,
                fontSize = (fontSize - 3).sp,
                color = secondaryColor,
                lineHeight = (fontSize + 2).sp,
                modifier = Modifier.padding(start = 22.dp, end = 8.dp, bottom = 7.dp)
            )
        } else if (open) {
            Text(
                if (message.isStreaming) "进行中…" else "无详情",
                fontSize = (fontSize - 4).sp,
                color = secondaryColor,
                modifier = Modifier.padding(start = 22.dp, bottom = 7.dp)
            )
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = textColor.copy(alpha = 0.12f)
        )
    }
}

@Composable
private fun ProcessFlowCard(
    message: ChatMessage,
    fontSize: Int,
    isDarkTheme: Boolean,
    onOpenUrl: (String) -> Unit
) {
    val hasThink = message.reasoningContent.isNotBlank()
    val annotations = message.annotations.orEmpty()
    val hasSearch = annotations.isNotEmpty()
    if (!hasThink && !hasSearch) return

    var thinkOpen by remember(message.id) { mutableStateOf(message.isStreaming) }
    var searchOpen by remember(message.id) { mutableStateOf(false) }
    var pagesOpen by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.isStreaming) {
        if (!message.isStreaming) thinkOpen = false
    }

    // Live timer while thinking streams; final duration after done.
    var liveMs by remember { mutableLongStateOf(message.reasoningDurationMs) }
    LaunchedEffect(message.isStreaming, message.reasoningDurationMs, hasThink) {
        if (message.isStreaming && hasThink && message.reasoningDurationMs == 0L) {
            val start = System.currentTimeMillis()
            while (isActive) {
                liveMs = System.currentTimeMillis() - start
                delay(100)
            }
        } else {
            liveMs = message.reasoningDurationMs
        }
    }
    val secs = if (message.isStreaming) liveMs / 1000 else (liveMs / 1000).coerceAtLeast(1)
    val timerText = if (message.isStreaming) {
        val tenths = (liveMs % 1000) / 100
        if (secs >= 60) "%d:%02d.%d".format(secs / 60, secs % 60, tenths)
        else "%d.%d秒".format(secs, tenths)
    } else {
        if (secs >= 60) "%d:%02d秒".format(secs / 60, secs % 60)
        else "${secs}秒"
    }

    val bg = if (isDarkTheme) MiMoColors.ThinkingBgDark else MiMoColors.ThinkingBg
    val textColor = if (isDarkTheme) MiMoColors.ThinkingTextDark else MiMoColors.ThinkingText
    val secondaryColor = textColor.copy(alpha = 0.7f)

    Column(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .fillMaxWidth(0.85f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(vertical = 4.dp)
    ) {
        // ---- 思考 ----
        if (hasThink) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { thinkOpen = !thinkOpen }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = secondaryColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (message.isStreaming) "思考" else "思考",
                    fontSize = (fontSize - 3).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = timerText,
                    fontSize = (fontSize - 4).sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondaryColor,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Icon(
                    if (thinkOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = secondaryColor
                )
            }
            if (thinkOpen) {
                if (message.isStreaming) {
                    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                    val previewHeightDp = (screenHeightDp * 0.12f).coerceIn(60f, 120f).dp
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .heightIn(max = previewHeightDp)
                    ) {
                        val tail = if (message.reasoningContent.length > 400) {
                            "…" + message.reasoningContent.takeLast(400)
                        } else message.reasoningContent
                        Text(
                            tail,
                            fontSize = (fontSize - 2).sp,
                            color = textColor.copy(alpha = 0.65f),
                            lineHeight = (fontSize + 3).sp,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        message.reasoningContent,
                        fontSize = (fontSize - 2).sp,
                        color = textColor.copy(alpha = 0.8f),
                        lineHeight = (fontSize + 3).sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // ---- 搜索 ----
        if (hasSearch) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { searchOpen = !searchOpen }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = secondaryColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "搜索",
                    fontSize = (fontSize - 3).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${annotations.size} 条结果",
                    fontSize = (fontSize - 4).sp,
                    color = secondaryColor,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Icon(
                    if (searchOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = secondaryColor
                )
            }
            if (searchOpen) {
                annotations.forEachIndexed { index, annotation ->
                    val url = annotation.url
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !url.isNullOrBlank()) {
                                if (!url.isNullOrBlank()) onOpenUrl(url)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Numbered gray chip — matches [reference:N] style
                        Box(
                            modifier = Modifier
                                .padding(top = 1.dp, end = 8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF8A8A8A).copy(alpha = 0.2f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontSize = (fontSize - 5).sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF8A8A8A)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                annotation.title ?: annotation.url ?: "Untitled",
                                fontSize = (fontSize - 3).sp,
                                fontWeight = FontWeight.Medium,
                                color = textColor.copy(alpha = 0.9f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!annotation.summary.isNullOrBlank()) {
                                Text(
                                    annotation.summary,
                                    fontSize = (fontSize - 5).sp,
                                    color = secondaryColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else if (annotation.siteName != null) {
                                Text(
                                    annotation.siteName,
                                    fontSize = (fontSize - 5).sp,
                                    color = secondaryColor
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- 查看页面 ----
        if (hasSearch) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pagesOpen = !pagesOpen }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = secondaryColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "查看页面",
                    fontSize = (fontSize - 3).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${annotations.size}",
                    fontSize = (fontSize - 4).sp,
                    color = secondaryColor,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Icon(
                    if (pagesOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = secondaryColor
                )
            }
            if (pagesOpen) {
                annotations.forEachIndexed { index, annotation ->
                    val url = annotation.url
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !url.isNullOrBlank()) {
                                if (!url.isNullOrBlank()) onOpenUrl(url)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier
                                .size(13.dp)
                                .padding(top = 2.dp),
                            tint = secondaryColor
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${index + 1}. ${annotation.title ?: annotation.url ?: "Untitled"}",
                                fontSize = (fontSize - 3).sp,
                                fontWeight = FontWeight.Medium,
                                color = textColor.copy(alpha = 0.9f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (annotation.siteName != null) {
                                Text(
                                    annotation.siteName,
                                    fontSize = (fontSize - 5).sp,
                                    color = secondaryColor
                                )
                            }
                        }
                    }
                }
            }
        }
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

package com.zhibai.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.zhibai.app.ui.components.MessageBubble
import com.zhibai.app.ui.theme.MiMoColors
import com.zhibai.app.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val isImage: Boolean,
    val isAudio: Boolean = false,
    val isVideo: Boolean = false
)

/** Input-bar voice pipeline: Idle → Recording (waveform) → Transcribing (loading line). */
private enum class VoicePhase { Idle, Recording, Transcribing }

private fun buildChatWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val header = java.nio.ByteBuffer.allocate(44).apply {
        order(java.nio.ByteOrder.LITTLE_ENDIAN)
        put("RIFF".toByteArray())
        putInt(36 + pcm.size)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(sampleRate)
        putInt(sampleRate * 2)
        putShort(2)
        putShort(16)
        put("data".toByteArray())
        putInt(pcm.size)
    }
    return header.array() + pcm
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, onNavigateToPhoneAgent: () -> Unit = {}) {
    val session by viewModel.currentSession.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val enableThinking by viewModel.enableThinking.collectAsState()
    val enableSearch by viewModel.enableSearch.collectAsState()
    val showSettings by viewModel.showSettings.collectAsState()
    val savedApiKey by viewModel.apiKey.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val pendingToolCall by viewModel.pendingToolCall.collectAsState()
    val pendingQuestionnaire by viewModel.pendingQuestionnaire.collectAsState()
    val pendingPermission by viewModel.pendingPermission.collectAsState()
    val toolExecuting by viewModel.toolExecuting.collectAsState()
    val ttsVoice by viewModel.ttsVoice.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val forceNewSession by viewModel.forceNewSessionOnModelSwitch.collectAsState()
    val customPrompt by viewModel.customPrompt.collectAsState()
    val agentEnabled by viewModel.agentEnabled.collectAsState()
    val agentPermissionMode by viewModel.agentPermissionMode.collectAsState()
    val agentApiKey by viewModel.agentApiKey.collectAsState()
    val agentBaseUrl by viewModel.agentBaseUrl.collectAsState()
    val speedStats by viewModel.speedStats.collectAsState()
    val modelIds by viewModel.modelIds.collectAsState()
    val localAiEnabled by viewModel.localAiEnabled.collectAsState()
    val localAiModel by viewModel.localAiModel.collectAsState()
    val showSpeedPanel = currentModel.contains("ultraspeed") && speedStats != null
    val showSkillsCenter by viewModel.showSkillsCenter.collectAsState()
    val skillList by viewModel.skillList.collectAsState()
    val skillContent by viewModel.skillContent.collectAsState()
    val showArchivePage by viewModel.showArchivePage.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var selectedFiles by remember { mutableStateOf<List<SelectedFile>>(emptyList()) }
    var editingFileIndex by remember { mutableStateOf(-1) }
    var showModelSelector by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var editingSession by remember { mutableStateOf<com.zhibai.app.data.model.ChatSession?>(null) }
    var editTitleText by remember { mutableStateOf("") }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var showTaskPanel by remember { mutableStateOf(false) }
    var studyMode by remember { mutableStateOf(false) }
    // Batch-select mode for chat list (long-press to enter)
    var batchSelectMode by remember { mutableStateOf(false) }
    var batchSelectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    // ---- Input bar redesign state ----
    var plusExpanded by remember { mutableStateOf(false) }
    var showThinkingSheet by remember { mutableStateOf(false) }
    var showMaxCostDialog by remember { mutableStateOf(false) }
    var showContextDetail by remember { mutableStateOf(false) }

    // Voice ASR: Idle → Recording (waveform) → Transcribing (loading line) → typewriter fill
    var voicePhase by remember { mutableStateOf(VoicePhase.Idle) }
    var waveAmps by remember { mutableStateOf(List(36) { 0f }) }
    val keepRecording = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var recordingThread by remember { mutableStateOf<Thread?>(null) }
    val pcmBuffer = remember { java.io.ByteArrayOutputStream() }
    var typewriterTarget by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val showScrollToBottom = remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current

    // Initialize TTS service with API key
    val ttsService = remember { com.zhibai.app.data.api.TTSService() }
    LaunchedEffect(savedApiKey) { ttsService.updateApiKey(savedApiKey) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun startAsrRecording() {
        voicePhase = VoicePhase.Recording
        waveAmps = List(36) { 0f }
        pcmBuffer.reset()
        keepRecording.set(true)
        recordingThread = Thread {
            try {
                val sampleRate = 16000
                val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
                val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
                val minBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val recorder = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    sampleRate, channelConfig, audioFormat, maxOf(minBuf, 8192)
                )
                recorder.startRecording()
                val buf = ShortArray(1024)
                val maxSeconds = 60
                val maxSamples = sampleRate.toLong() * maxSeconds
                var totalRead = 0L
                while (keepRecording.get() && totalRead < maxSamples) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        val bytes = ByteArray(read * 2)
                        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().put(buf, 0, read)
                        pcmBuffer.write(bytes)
                        totalRead += read
                        // Speech RMS is tiny (~0.02–0.1); combine peak + boosted RMS
                        // so quiet talking still moves the bars visibly.
                        var sum = 0.0
                        var peak = 0.0
                        for (i in 0 until read) {
                            val v = kotlin.math.abs(buf[i].toDouble()) / 32768.0
                            if (v > peak) peak = v
                            sum += v * v
                        }
                        val rms = kotlin.math.sqrt(sum / read)
                        val level = (rms * 6.0 + peak * 2.0).coerceIn(0.0, 1.0).toFloat()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (voicePhase == VoicePhase.Recording) {
                                waveAmps = waveAmps.drop(1) + level
                            }
                        }
                    }
                }
                try {
                    recorder.stop()
                    recorder.release()
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }.apply { start() }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAsrRecording()
        else toast("需要麦克风权限")
    }

    fun requestAsrRecording() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startAsrRecording()
        } else {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopAsrAndUpload() {
        if (voicePhase != VoicePhase.Recording) return
        keepRecording.set(false)
        voicePhase = VoicePhase.Transcribing
        waveAmps = List(36) { 0f }
        val pcm = pcmBuffer.toByteArray()
        coroutineScope.launch {
            try {
                if (pcm.isEmpty()) {
                    voicePhase = VoicePhase.Idle
                    toast("没有录到声音")
                    return@launch
                }
                val wav = buildChatWav(pcm, 16000)
                val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
                val text = viewModel.speechToText(b64, "audio/wav")
                voicePhase = VoicePhase.Idle
                if (!text.isNullOrBlank()) {
                    typewriterTarget = text
                } else {
                    toast(viewModel.lastAsrError ?: "识别失败，请检查 API Key 或网络")
                }
            } catch (e: Exception) {
                voicePhase = VoicePhase.Idle
                toast(e.message ?: "识别失败")
            }
        }
    }

    fun cancelAsrRecording() {
        keepRecording.set(false)
        voicePhase = VoicePhase.Idle
        waveAmps = List(36) { 0f }
    }

    // Stop the mic if the screen goes away mid-recording
    DisposableEffect(Unit) {
        onDispose { cancelAsrRecording() }
    }

    // Typewriter fill → grows the pill field line by line (same path as normal typing)
    LaunchedEffect(typewriterTarget) {
        val full = typewriterTarget ?: return@LaunchedEffect
        inputText = ""
        val stepDelay = if (full.length > 120) 12L else if (full.length > 40) 20L else 30L
        for (i in 1..full.length) {
            inputText = full.substring(0, i)
            delay(stepDelay)
        }
        typewriterTarget = null
    }

    // Track scroll position + bottom pin (auto-follow only while user is at bottom)
    val isAtBottom = remember { mutableStateOf(true) }
    // Auto-follow intent. Disabled when the USER scrolls away; re-enabled when a scroll
    // ends back at the end of content. [ourScroll] marks OUR programmatic scrolls so
    // they never count as "user scrolled away".
    var followEnabled by remember { mutableStateOf(true) }
    var ourScroll by remember { mutableStateOf(false) }
    // Live height of the floating input stack — list bottom inset + at-bottom detection
    var inputAreaHeightPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState, inputAreaHeightPx) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) {
                true to false // (atBottom, showButton)
            } else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()
                val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
                // Line below the last item NOT covered by the floating input
                val visibleBelow = (viewportHeight - inputAreaHeightPx).coerceAtLeast(0)
                // px from that line down to the content end:
                //   >0  short list fully visible, 0  pinned to end, <0  scrolled up.
                // Only computable when the REAL last item is on screen — otherwise the
                // bottom-most visible item is a mid-list one and we're definitely above.
                val gapToLine = if (lastVisible != null && lastVisible.index == total - 1) {
                    (info.viewportStartOffset + visibleBelow) - (lastVisible.offset + lastVisible.size)
                } else {
                    Int.MIN_VALUE / 2
                }
                // Old formula (index >= total-1 || linesBelow <= 2) was true in almost
                // every state — it made auto-follow fight the user and flicker.
                val atBottom = gapToLine >= -(fontSize * 4)
                val showButton = !atBottom && !followEnabled
                atBottom to showButton
            }
        }.distinctUntilChanged().collect { (atBottom, showButton) ->
            isAtBottom.value = atBottom
            showScrollToBottom.value = showButton
        }
    }
    // User scroll starts → stop following; scroll ends while back at the end → resume.
    // ourScroll is part of the flow so its own toggles re-evaluate (and can't race:
    // it flips only around our scroll, which always ends with an at-bottom re-enable).
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.isScrollInProgress, ourScroll, isAtBottom.value) }
            .collect { (scrolling, ours, atBottom) ->
                if (scrolling && !ours) followEnabled = false
                else if (!scrolling && atBottom) followEnabled = true
            }
    }
    // Fresh session → follow again (open pinned to its bottom)
    LaunchedEffect(session?.id) { followEnabled = true }

    // ===== attachment limits (lifted when hidden "unlimited" setting is on) =====
    val unlimitedAttach by viewModel.unlimitedAttachments.collectAsState()

    fun uriSize(uri: Uri): Long = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
            } else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }

    fun imageCapacity(): Int = when {
        unlimitedAttach -> Int.MAX_VALUE
        selectedFiles.any { it.isVideo || it.isAudio } -> 2   // video/audio attached → images capped at 2
        else -> 6
    }

    // Photo Picker multi-select (GetContent is single-only by contract)
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val capacity = imageCapacity()
        val currentImages = selectedFiles.count { f -> f.isImage }
        val room = capacity - currentImages
        if (room <= 0) {
            toast(
                if (selectedFiles.any { f -> f.isVideo || f.isAudio }) "已附加视频/音频，图片最多 2 张"
                else "图片最多 $capacity 张"
            )
            return@rememberLauncherForActivityResult
        }
        val accepted = uris.take(room)
        if (accepted.size < uris.size) {
            toast(
                if (selectedFiles.any { f -> f.isVideo || f.isAudio }) "超出限制，已添加前 ${accepted.size} 张（视频/音频模式下图片最多 2 张）"
                else "图片最多 $capacity 张，已添加前 ${accepted.size} 张"
            )
        }
        val added = accepted.map { uri ->
            SelectedFile(uri = uri, name = getFileName(context, uri), isImage = true)
        }
        selectedFiles = selectedFiles + added
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val name = getFileName(context, it)
            val mimeType = context.contentResolver.getType(it)
            val isImage = mimeType?.startsWith("image/") == true
            val isAudio = mimeType?.startsWith("audio/") == true
            val isVideo = mimeType?.startsWith("video/") == true
            val size = uriSize(it)

            if (isVideo) {
                if (!unlimitedAttach && selectedFiles.any { f -> f.isVideo }) {
                    toast("仅支持一份视频"); return@let
                }
                val capMb = if (unlimitedAttach) 37 else 20   // 37MB = API base64 hard cap
                if (size > capMb * 1024L * 1024L) {
                    toast("视频需在 ${capMb}MB 以内"); return@let
                }
            }
            if (isAudio) {
                if (!unlimitedAttach && selectedFiles.any { f -> f.isAudio }) {
                    toast("仅支持一份音频"); return@let
                }
                val capMb = if (unlimitedAttach) 37 else 10
                if (size > capMb * 1024L * 1024L) {
                    toast("音频需在 ${capMb}MB 以内"); return@let
                }
            }
            if (isImage && selectedFiles.count { f -> f.isImage } >= imageCapacity()) {
                toast("图片数量已达上限"); return@let
            }
            selectedFiles = selectedFiles + SelectedFile(uri = it, name = name, isImage = isImage, isAudio = isAudio, isVideo = isVideo)
        }
    }

    // Scroll the message list to the TRUE end of content. A plain scrollToItem(size-1)
    // top-aligns a last message taller than the viewport — it lands on the message's
    // HEAD, not its tail, so streaming text stays hidden below and every token jump
    // looked like flicker. When the last item is already visible we scrollBy the exact
    // delta in one transaction (no intermediate frame). ourScroll marks the whole
    // programmatic scroll so the intent watcher never treats it as a user scroll.
    suspend fun scrollChatToEnd() {
        val size = session?.messages?.size ?: 0
        if (size <= 0) return
        // bottom contentPadding ≈ input height (200.dp fallback before first measure)
        val paddingPx = inputAreaHeightPx
        ourScroll = true
        try {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            if (lastVisible != null && lastVisible.index >= size - 1) {
                // At the end region already → exact delta, single frame
                val delta = (lastVisible.offset + lastVisible.size + paddingPx) - info.viewportEndOffset
                if (delta > 0) listState.scroll { scrollBy(delta.toFloat()) }
            } else {
                // Far above → jump near the end, wait one frame for layout, fine-tune
                listState.scrollToItem(size - 1)
                withFrameNanos { }
                val info2 = listState.layoutInfo
                val lv = info2.visibleItemsInfo.lastOrNull()
                if (lv != null) {
                    val delta = (lv.offset + lv.size + paddingPx) - info2.viewportEndOffset
                    if (delta > 0) listState.scroll { scrollBy(delta.toFloat()) }
                }
            }
        } finally {
            ourScroll = false
        }
    }

    // Auto-follow: only while the user hasn't scrolled away (followEnabled).
    LaunchedEffect(
        session?.messages?.size,
        session?.messages?.lastOrNull()?.content?.length,
        session?.messages?.lastOrNull()?.reasoningContent?.length,
        isGenerating,
        followEnabled
    ) {
        if ((session?.messages?.size ?: 0) > 0 && followEnabled && !listState.isScrollInProgress) {
            scrollChatToEnd()
        }
    }

    // Back handler for drawer
    if (drawerState.isOpen) {
        BackHandler { coroutineScope.launch { drawerState.close() } }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface) // bottom bg = sidebar color
    ) {
    // ---- Push Drawer ----
    val drawerWidth = 300.dp
    val drawerProgress by animateFloatAsState(
        targetValue = if (drawerState.isOpen) 1f else 0f,
        animationSpec = tween(350, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
        label = "pushDrawer"
    )
    val density = LocalDensity.current
    val dragOffset = remember { mutableFloatStateOf(0f) }
    val maxPx = with(density) { drawerWidth.toPx() }
    val dragProgress = (drawerProgress + dragOffset.value / maxPx).coerceIn(0f, 1f)

    // Once drawerProgress settles at target, clear residual dragOffset
    LaunchedEffect(drawerProgress) {
        if (drawerProgress == 0f || drawerProgress == 1f) {
            dragOffset.floatValue = 0f
        }
    }

    // Drawer panel
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(drawerWidth)
            .graphicsLayer {
                translationX = -drawerWidth.toPx() * (1f - dragProgress)
            }
            .zIndex(10f)
    ) {
        ModalDrawerSheet(
            modifier = Modifier.fillMaxHeight(),
            drawerContainerColor = MaterialTheme.colorScheme.surface
        ) {
            // ---- Header ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MiMo",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiMoColors.Primary
                    )
                    Text(
                        text = "Xiaomi AI Assistant",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // ---- New chat ----
            NavigationDrawerItem(
                icon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = {
                    Text(
                        text = "新对话",
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                },
                selected = false,
                onClick = {
                    viewModel.createNewSession()
                    coroutineScope.launch { drawerState.close() }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // ---- Skills Center ----
            NavigationDrawerItem(
                icon = {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = {
                    Text(
                        text = "技能中心",
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                },
                selected = false,
                onClick = {
                    viewModel.toggleSkillsCenter()
                    coroutineScope.launch { drawerState.close() }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // ---- Archive ----
            NavigationDrawerItem(
                icon = {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = {
                    Text(
                        text = "档案",
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                },
                selected = false,
                onClick = {
                    viewModel.toggleArchivePage()
                    coroutineScope.launch { drawerState.close() }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ---- Search ----
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索对话...", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
            )

            // ---- Section label: 对话列表 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "对话列表",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val filteredSessions = remember(sessions, searchQuery) {
                val validSessions = sessions.filter { it.messages.isNotEmpty() && !it.archived }
                if (searchQuery.isBlank()) {
                    validSessions.sortedByDescending { it.lastActiveAt.coerceAtLeast(it.createdAt) }
                } else {
                    val query = searchQuery.lowercase()
                    validSessions.filter { sess ->
                        sess.title.lowercase().contains(query) ||
                        sess.messages.any { msg ->
                            msg.content.lowercase().contains(query) ||
                            msg.reasoningContent.lowercase().contains(query)
                        }
                    }.sortedByDescending { it.lastActiveAt.coerceAtLeast(it.createdAt) }
                }
            }

            // Helper to find matching snippet
            val filteredSessionsWithSnippets = remember(filteredSessions, searchQuery) {
                if (searchQuery.isBlank()) {
                    filteredSessions.map { it to null }
                } else {
                    val q = searchQuery.lowercase()
                    filteredSessions.map { sess ->
                        var snippet: String? = null
                        for (msg in sess.messages) {
                            val content = msg.content.lowercase()
                            val reasoning = msg.reasoningContent.lowercase()
                            val idx = content.indexOf(q).takeIf { it >= 0 } ?: reasoning.indexOf(q).takeIf { it >= 0 } ?: continue
                            val source = if (content.contains(q)) msg.content else msg.reasoningContent
                            val start = maxOf(0, idx - 20)
                            val end = minOf(source.length, idx + q.length + 30)
                            snippet = (if (start > 0) "..." else "") + source.substring(start, end) + (if (end < source.length) "..." else "")
                            break
                        }
                        sess to snippet
                    }
                }
            }

            // Batch select toolbar
            if (batchSelectMode && batchSelectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${batchSelectedIds.size} 项",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        viewModel.restoreSessions(batchSelectedIds)
                        batchSelectMode = false; batchSelectedIds = emptySet()
                    }) { Text("恢复", fontSize = 13.sp) }
                    TextButton(onClick = {
                        viewModel.archiveSessions(batchSelectedIds)
                        batchSelectMode = false; batchSelectedIds = emptySet()
                    }) { Text("归档", fontSize = 13.sp) }
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text("删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        batchSelectMode = false; batchSelectedIds = emptySet()
                    }) { Text("取消", fontSize = 13.sp) }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredSessionsWithSnippets) { (sess, matchSnippet) ->
                    val isSelected = sess.id == session?.id
                    val isThisGenerating = isGenerating && isSelected
                    val isBatchChecked = sess.id in batchSelectedIds
                    var showMenu by remember { mutableStateOf(false) }
                    var showRename by remember { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    isBatchChecked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else -> Color.Transparent
                                }
                            )
                            .combinedClickable(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (batchSelectMode) {
                                        batchSelectedIds = if (isBatchChecked)
                                            batchSelectedIds - sess.id
                                        else
                                            batchSelectedIds + sess.id
                                    } else {
                                        viewModel.selectSession(sess.id)
                                        coroutineScope.launch { drawerState.close() }
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (batchSelectMode) {
                                        batchSelectedIds = if (isBatchChecked)
                                            batchSelectedIds - sess.id
                                        else
                                            batchSelectedIds + sess.id
                                    } else {
                                        // Enter batch mode + show menu
                                        batchSelectMode = true
                                        batchSelectedIds = setOf(sess.id)
                                        showMenu = true
                                    }
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left icon: checkbox in batch mode, chat bubble otherwise
                            if (batchSelectMode) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isBatchChecked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isBatchChecked) {
                                        Icon(Icons.Default.Check, contentDescription = null,
                                            modifier = Modifier.size(14.dp), tint = Color.White)
                                    }
                                }
                            } else {
                                Icon(
                                    Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sess.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                                )
                                if (matchSnippet != null) {
                                    val q = searchQuery.lowercase()
                                    val snippetLower = matchSnippet.lowercase()
                                    val matchIdx = snippetLower.indexOf(q)
                                    if (matchIdx >= 0) {
                                        val annotatedString = buildAnnotatedString {
                                            append(matchSnippet.substring(0, matchIdx))
                                            pushStyle(SpanStyle(
                                                background = MiMoColors.Primary.copy(alpha = 0.3f),
                                                fontWeight = FontWeight.Bold
                                            ))
                                            append(matchSnippet.substring(matchIdx, matchIdx + searchQuery.length))
                                            pop()
                                            append(matchSnippet.substring(matchIdx + searchQuery.length))
                                        }
                                        Text(
                                            text = annotatedString,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            // Status: spinner if generating, hollow ring otherwise
                            Box(
                                modifier = Modifier.size(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isThisGenerating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .border(
                                                width = 1.5.dp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                                shape = CircleShape
                                            )
                                    )
                                }
                            }
                        }
                        // Long-press context menu
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false; showRename = false }
                        ) {
                            if (!showRename) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        showRename = true
                                        editTitleText = sess.title
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (sess.archived) "取消归档" else "归档") },
                                    leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        viewModel.toggleArchiveSession(sess.id)
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        pendingDeleteId = sess.id
                                        showDeleteConfirm = true
                                    }
                                )
                            } else {
                                OutlinedTextField(
                                    value = editTitleText,
                                    onValueChange = { editTitleText = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    singleLine = true,
                                    placeholder = { Text("输入新标题", fontSize = 13.sp) },
                                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                                )
                                DropdownMenuItem(
                                    text = { Text("保存") },
                                    onClick = {
                                        if (editTitleText.isNotBlank()) {
                                            viewModel.renameSession(sess.id, editTitleText.trim())
                                        }
                                        showMenu = false
                                        showRename = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("取消") },
                                    onClick = { showMenu = false; showRename = false }
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ---- Settings ----
            NavigationDrawerItem(
                icon = {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = {
                    Text(
                        text = "设置",
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                },
                selected = false,
                onClick = {
                    viewModel.toggleSettings()
                    coroutineScope.launch { drawerState.close() }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // Main content as card, shifts right + grays out when drawer opens
    // Adaptive corner radius from actual screen
    val screenCornerRadius: androidx.compose.ui.unit.Dp = remember {
        var radiusPx = 0
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val decorView = (context as? android.app.Activity)?.window?.decorView
                val insets = decorView?.rootWindowInsets
                val method = insets?.javaClass?.getMethod("getRoundedCorner", Int::class.javaPrimitiveType)
                val corner = method?.invoke(insets, 0)
                if (corner != null) {
                    val rMethod = corner.javaClass.getMethod("getRadius")
                    radiusPx = (rMethod.invoke(corner) as? Int) ?: 0
                }
            }
        } catch (_: Exception) {}
        if (radiusPx > 0) with(density) { radiusPx.toDp() } else 32.dp
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val p = dragOffset.value / maxPx
                        if (!drawerState.isOpen) {
                            if (p > 0.3f) {
                                // Snap open: don't reset dragOffset — it stays at max,
                                // drawerProgress animates 0→1, combined stays clamped at 1
                                coroutineScope.launch { drawerState.open() }
                            } else {
                                // Not far enough — snap dragOffset back
                                dragOffset.value = 0f
                            }
                        } else {
                            if (p < -0.2f) {
                                // Snap close: don't reset dragOffset — it stays at -max,
                                // drawerProgress animates 1→0, combined stays clamped at 0
                                coroutineScope.launch { drawerState.close() }
                            } else {
                                dragOffset.value = 0f
                            }
                        }
                    },
                    onDragCancel = { dragOffset.value = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    val raw = drawerProgress * maxPx + dragOffset.value
                    val newRaw = (raw + dragAmount).coerceIn(0f, maxPx)
                    dragOffset.value = newRaw - drawerProgress * maxPx
                }
            }
            .pointerInput(drawerState.isOpen) {
                if (drawerState.isOpen) {
                    detectTapGestures {
                        coroutineScope.launch { drawerState.close() }
                    }
                }
            }
            .graphicsLayer {
                val progress = (drawerProgress + dragOffset.value / maxPx).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(1f, 0.5f)
                val scale = 1f - 0.06f * progress
                scaleX = scale
                scaleY = scale
                translationX = drawerWidth.toPx() * progress
                clip = true
                // Corner radius always = screen radius; when fully closed (off-screen) it doesn't matter
                shape = RoundedCornerShape(screenCornerRadius)
                shadowElevation = (4 * progress).dp.toPx()
            }
            .background(MaterialTheme.colorScheme.background)
            .border(
                width = (0.5f * dragProgress).dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f * dragProgress),
                shape = RoundedCornerShape(screenCornerRadius)
            )
    ) {
        // No real top bar — chrome floats over content with a fade mask.
        val hasMessages = session?.messages?.isNotEmpty() == true
        val speechMode = viewModel.isTTSMode || viewModel.isASRMode
        val showChrome = hasMessages || speechMode
        val modelLabel = when {
            currentModel.contains("agent") && currentModel.contains("ultraspeed") -> "Agent Ultra"
            currentModel.contains("agent") -> "Agent"
            currentModel.contains("ultraspeed") -> "Ultra"
            currentModel.contains("pro") -> "Pro"
            currentModel.contains("flash") -> "Flash"
            else -> currentModel.substringAfterLast("-").take(12).ifBlank { "Model" }
        }
        Scaffold(
            topBar = {}
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    // Room for floating menu / title so TTS·ASR content isn't under the chrome
                    .padding(top = if (speechMode) 52.dp else 0.dp)
            ) {
                // Model selector — capsule rows matching the new input bar style
                if (showModelSelector) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showModelSelector = false }
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("切换模型", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(12.dp))
                                com.zhibai.app.ui.components.CapsuleModelBar(
                                    currentModel = currentModel,
                                    modelIds = modelIds,
                                    agentEnabled = agentEnabled,
                                    onSelectModel = { model ->
                                        viewModel.setModel(model)
                                        showModelSelector = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Content: Chat or TTS
                if (viewModel.isTTSMode) {
                    BackHandler { viewModel.setModel(modelIds.firstOrNull { !it.startsWith("tts") && it != "asr" } ?: "mimo-v2.6-flash") }
                    TTSScreen(viewModel = viewModel, initialMode = viewModel.ttsScreenMode)
                } else if (viewModel.isASRMode) {
                    BackHandler { viewModel.setModel(modelIds.firstOrNull() ?: "mimo-v2.6-flash") }
                    ASRScreen()
                } else {
                val blurIntensity = viewModel.inputBlurIntensity.collectAsState().value
                val isGlass = viewModel.inputStyle.collectAsState().value == "glass"
                val isAgent = currentModel.contains("agent")
                val density = LocalDensity.current
                val inputBottomPadding = with(density) { inputAreaHeightPx.toDp() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            // Clear floating menu / title chrome at the top
                            top = if (showChrome) 72.dp else 56.dp,
                            end = 16.dp,
                            bottom = if (inputAreaHeightPx > 0) inputBottomPadding else 200.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (session?.messages?.isEmpty() != false) {
                            item {
                                EmptyStateView(
                                    currentModel = currentModel,
                                    modelIds = modelIds,
                                    agentEnabled = agentEnabled,
                                    localAiEnabled = localAiEnabled,
                                    localAiModel = localAiModel,
                                    onSelectModel = { viewModel.setModel(it) }
                                )
                            }
                        }

                        itemsIndexed(
                            session?.messages ?: emptyList(),
                            key = { _, msg -> msg.id }
                        ) { _, message ->
                            val msgEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1f)
                            val entered = remember { mutableStateOf(false) }
                            LaunchedEffect(message.id) { entered.value = true }
                            val msgOffset by animateFloatAsState(
                                targetValue = if (entered.value) 0f else 40f,
                                animationSpec = tween(450, easing = msgEasing),
                                label = "msgSlide"
                            )
                            val msgAlpha by animateFloatAsState(
                                targetValue = if (entered.value) 1f else 0f,
                                animationSpec = tween(350, easing = msgEasing),
                                label = "msgFade"
                            )
                            Box(
                                modifier = Modifier
                                    .animateItem()
                                    .offset(y = msgOffset.dp)
                                    .alpha(msgAlpha)
                            ) {
                                Column {
                                    MessageBubble(
                                        message = message,
                                        isDarkTheme = isDarkTheme,
                                        fontSize = fontSize,
                                        isAgent = isAgent,
                                        studyMode = studyMode,
                                        onEdit = { text ->
                                            inputText = text
                                            editingMessageId = message.id
                                        },
                                        onCopy = { text ->
                                            com.zhibai.app.ui.components.ClipboardHelper.copyWithToast(context, text, label = "消息")
                                        },
                                        onReadAloud = { text ->
                                            if (isSpeaking) {
                                                viewModel.stopSpeaking()
                                            } else {
                                                viewModel.readAloud(text)
                                            }
                                        },
                                        onRegenerate = if (message.role == "assistant" && !message.isStreaming) {
                                            { viewModel.regenerate() }
                                        } else null,
                                        isSpeaking = isSpeaking
                                    )
                                    // AI reply mentions a support hotline — one-tap dial card
                                    if (message.role == "assistant" && !message.isStreaming) {
                                        val matched = remember(message.content, message.id) {
                                            com.zhibai.app.ui.components.findHotlinesInText(message.content)
                                        }
                                        com.zhibai.app.ui.components.HotlineSuggestionCard(hotlines = matched)
                                    }
                                }
                            }
                        }
                    }

                    // UltraSpeed live speed panel — floats above messages so it never gets clipped
                    if (showSpeedPanel) {
                        SpeedPanel(
                            stats = speedStats!!,
                            isDarkTheme = isDarkTheme,
                            fontSize = fontSize,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .heightIn(max = 180.dp)
                        )
                    }

                    // Scroll to bottom button — capsule style matching the new input bar,
                    // sit above the measured input height (not a hardcoded offset).
                    if (showScrollToBottom.value) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(
                                    end = 16.dp,
                                    bottom = with(LocalDensity.current) { inputAreaHeightPx.toDp() } + 14.dp
                                )
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    coroutineScope.launch {
                                        // True end of content (animateScrollToItem would
                                        // top-align a last message taller than the screen)
                                        scrollChatToEnd()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Agent task panel + snowflake indicator (above input bar)
                    val agentActive by viewModel.agentTaskManager.isActive.collectAsState()
                    if (isAgent) {
                        val agentTasks by viewModel.agentTaskManager.tasks.collectAsState()
                        val agentResult = remember(agentTasks, agentActive) {
                            viewModel.agentTaskManager.getSessionResult()
                        }
                        // Task panel — toggleable via top bar button, persists across sessions
                        if (showTaskPanel) {
                            com.zhibai.app.ui.components.AgentTaskPanel(
                                taskManager = viewModel.agentTaskManager,
                                result = agentResult,
                                isActive = agentActive,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                            )
                        }
                        if (agentActive || isGenerating) {
                            com.zhibai.app.ui.components.AgentSnowflakeIndicator(
                                result = agentResult,
                                isActive = true,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 100.dp)
                            )
                        }
                    }

                    // Snow layer — works in normal chat AND Agent. Two triggers:
                    // 1. an Agent run whose whole checklist got checked (celebration)
                    // 2. the model calling the let_it_snow tool (manual)
                    // Both bump the ViewModel counter; the layer mounts while it is
                    // above zero and then snows forever (counter survives rotation).
                    var inputBoxFocused by remember { mutableStateOf(false) }
                    LaunchedEffect(agentActive) {
                        if (agentActive) {
                            viewModel.agentTaskManager.armCelebration()
                        } else if (viewModel.agentTaskManager.consumeCelebration()) {
                            viewModel.snowNow()
                        }
                    }
                    val snowTrigger by viewModel.manualSnowRequest.collectAsState()
                    if (snowTrigger > 0) {
                        com.zhibai.app.ui.components.SnowCelebration(
                            active = true,
                            // Clicking the input box melts the whole snowfall away
                            // (gently); it starts snowing again when the box closes.
                            melting = inputBoxFocused,
                            // zIndex: the snow draws ON TOP of the input bar — it used
                            // to compose before the bar, so flakes fell behind it and
                            // vanished (「飞入输入框看不见」).
                            modifier = Modifier.matchParentSize().zIndex(5f)
                        )
                    }

                    // Floating glass input bar + AI disclaimer + glow overlay
                    // Mask (top cutouts transparent / rest opaque) lives in GlassInputBar.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onSizeChanged { inputAreaHeightPx = it.height }
                    ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    // AI questionnaire card — floats above input bar
                    if (pendingQuestionnaire != null) {
                        QuestionnaireCard(
                            questions = pendingQuestionnaire!!,
                            onSubmit = { viewModel.submitQuestionnaire(it) },
                            onCancel = { viewModel.cancelQuestionnaire() }
                        )
                    }
                    com.zhibai.app.ui.components.GlassInputBar(
                        isGlass = viewModel.inputStyle.collectAsState().value == "glass",
                        blurColorHex = viewModel.inputBlurColor.collectAsState().value,
                        shadowEnabled = viewModel.inputShadowEnabled.collectAsState().value,
                        blurIntensity = viewModel.inputBlurIntensity.collectAsState().value,
                        isTyping = inputText.isNotBlank() || selectedFiles.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            // ---- "+" expanded panel: attachments + thinking + search + context ----
                            AnimatedVisibility(
                                visible = plusExpanded,
                                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val thinkingLevel = viewModel.thinkingLevel.collectAsState().value
                                    val thinkingLabels = listOf("关闭", "低", "中", "高", "MAX")
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // Image
                                        AssistChip(
                                            onClick = {
                                                imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                plusExpanded = false
                                            },
                                            label = { Text("图片", fontSize = 11.sp) },
                                            leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(14.dp)) },
                                            modifier = Modifier.height(32.dp)
                                        )
                                        // File (audio merged into file upload)
                                        AssistChip(
                                            onClick = {
                                                filePicker.launch("*/*")
                                                plusExpanded = false
                                            },
                                            label = { Text("文件", fontSize = 11.sp) },
                                            leadingIcon = { Icon(Icons.Default.AttachFile, null, Modifier.size(14.dp)) },
                                            modifier = Modifier.height(32.dp)
                                        )
                                        // Thinking — bottom sheet (gauge icon)
                                        AssistChip(
                                            onClick = {
                                                showThinkingSheet = true
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            label = {
                                                Text(
                                                    if (thinkingLevel == 0) "思考"
                                                    else "思考·${thinkingLabels[thinkingLevel]}",
                                                    fontSize = 11.sp
                                                )
                                            },
                                            leadingIcon = { Icon(Icons.Default.Speed, null, Modifier.size(14.dp)) },
                                            modifier = Modifier.height(32.dp)
                                        )
                                        // Web search toggle — FilterChip shows clear selected state
                                        FilterChip(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.toggleSearch()
                                            },
                                            selected = enableSearch,
                                            label = {
                                                Text(
                                                    if (enableSearch) "联网·开" else "联网",
                                                    fontSize = 11.sp
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Search,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = if (enableSearch) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            trailingIcon = {
                                                if (enableSearch) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(12.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            },
                                            modifier = Modifier.height(32.dp)
                                        )
                                    }

                                    // Context usage — tap for detail + compress
                                    val estTokens by viewModel.estimatedTokens.collectAsState()
                                    val maxTokens = com.zhibai.app.viewmodel.ChatViewModel.MAX_CONTEXT_TOKENS
                                    val tokenFraction = (estTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
                                    val pct = (tokenFraction * 100).toInt()
                                    val maxM = maxTokens / 1_000_000.0
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(50))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                            .clickable { showContextDetail = true }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val tokenArcColor = if (tokenFraction > 0.75f) Color(0xFFFF9800)
                                        else MaterialTheme.colorScheme.primary
                                        Canvas(modifier = Modifier.size(16.dp)) {
                                            val strokeW = 2.dp.toPx()
                                            val r = size.minDimension / 2 - strokeW / 2
                                            drawCircle(
                                                color = Color.Gray.copy(alpha = 0.3f),
                                                radius = r,
                                                style = Stroke(width = strokeW)
                                            )
                                            drawArc(
                                                color = tokenArcColor,
                                                startAngle = -90f,
                                                sweepAngle = 360f * tokenFraction,
                                                useCenter = false,
                                                style = Stroke(width = strokeW, cap = StrokeCap.Round),
                                                topLeft = Offset(strokeW / 2, strokeW / 2),
                                                size = Size(r * 2, r * 2)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = String.format("%d/%.1fM Tokens (%d%%)", estTokens, maxM, pct),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Selected files preview
                            if (selectedFiles.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    selectedFiles.forEachIndexed { index, file ->
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Icon(
                                                if (file.isImage) Icons.Default.Image
                                                else if (file.isVideo) Icons.Default.PlayArrow
                                                else Icons.Default.InsertDriveFile,
                                                contentDescription = file.name,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                                    .size(24.dp)
                                            )
                                            IconButton(
                                                onClick = { selectedFiles = selectedFiles.filterIndexed { i, _ -> i != index } },
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(20.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Remove",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Editing indicator
                            if (editingMessageId != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "编辑消息",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    TextButton(onClick = { editingMessageId = null }) {
                                        Text("取消", fontSize = 11.sp)
                                    }
                                }
                            }

                            // Main row: [+] [input] [mic → send cluster]
                            // Buttons track the center field height, but stop growing at BUTTON_MAX_H
                            // while the pill itself can go up to INPUT_MAX_H.
                            var centerHeightPx by remember { mutableIntStateOf(0) }
                            val density = LocalDensity.current
                            val inputMaxH = 110.dp
                            // One-line field height = "+" button width (40dp)
                            val inputMinH = 40.dp
                            val buttonMaxH = 56.dp
                            val centerHeight = with(density) {
                                if (centerHeightPx > 0) centerHeightPx.toDp() else inputMinH
                            }.coerceAtLeast(inputMinH).coerceAtMost(inputMaxH)
                            val buttonHeight = centerHeight.coerceAtMost(buttonMaxH)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // "+" → ×  (morphicons-style Procrustes path morph + spring)
                                Box(
                                    modifier = Modifier
                                        .width(40.dp)
                                        .height(buttonHeight)
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (plusExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                        )
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            plusExpanded = !plusExpanded
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    com.zhibai.app.ui.components.MorphIcon(
                                        from = com.zhibai.app.ui.components.StrokeIcon.Plus,
                                        to = com.zhibai.app.ui.components.StrokeIcon.Close,
                                        active = plusExpanded,
                                        color = if (plusExpanded) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(22.dp),
                                        contentDescription = "更多选项"
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Center: pill input / waveform / loading — animated transitions
                                AnimatedContent(
                                    targetState = voicePhase,
                                    transitionSpec = {
                                        when {
                                            // Waveform → loading line: bars collapse, line draws in
                                            initialState == VoicePhase.Recording &&
                                                targetState == VoicePhase.Transcribing -> {
                                                ContentTransform(
                                                    initialContentExit = fadeOut(tween(140)) +
                                                        shrinkVertically(
                                                            tween(220, easing = FastOutSlowInEasing),
                                                            shrinkTowards = Alignment.CenterVertically
                                                        ),
                                                    targetContentEnter = fadeIn(tween(200, delayMillis = 100)) +
                                                        expandHorizontally(
                                                            tween(260, delayMillis = 100, easing = FastOutSlowInEasing),
                                                            expandFrom = Alignment.Start
                                                        )
                                                )
                                            }
                                            // Loading line → input: line shrinks to the RIGHT and vanishes,
                                            // then the text field fades in (typewriter starts filling).
                                            initialState == VoicePhase.Transcribing &&
                                                targetState == VoicePhase.Idle -> {
                                                ContentTransform(
                                                    initialContentExit = shrinkHorizontally(
                                                        tween(300, easing = FastOutSlowInEasing),
                                                        shrinkTowards = Alignment.End
                                                    ) + fadeOut(tween(100, delayMillis = 200)),
                                                    targetContentEnter = fadeIn(tween(180, delayMillis = 120)) +
                                                        slideInVertically(
                                                            tween(200, delayMillis = 120)
                                                        ) { it / 6 }
                                                )
                                            }
                                            // Idle → Recording (mic tap): field out, waveform in
                                            initialState == VoicePhase.Idle &&
                                                targetState == VoicePhase.Recording -> {
                                                ContentTransform(
                                                    initialContentExit = fadeOut(tween(120)),
                                                    targetContentEnter = fadeIn(tween(180, delayMillis = 60))
                                                )
                                            }
                                            else -> ContentTransform(
                                                initialContentExit = fadeOut(tween(120)),
                                                targetContentEnter = fadeIn(tween(160))
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentKey = { it }
                                ) { phase ->
                                    when (phase) {
                                    VoicePhase.Idle -> {
                                        // BasicTextField: tight padding so one line = 40dp (same as "+" width)
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = inputText,
                                            onValueChange = { inputText = it },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = inputMinH, max = inputMaxH)
                                                .onFocusChanged { inputBoxFocused = it.isFocused }
                                                .onSizeChanged { centerHeightPx = it.height }
                                                .animateContentSize()
                                                .background(
                                                    MaterialTheme.colorScheme.surface,
                                                    RoundedCornerShape(50)
                                                )
                                                .padding(horizontal = 14.dp, vertical = 9.dp),
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = fontSize.sp,
                                                lineHeight = (fontSize + 5).sp
                                            ),
                                            minLines = 1,
                                            maxLines = 8,
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                                MaterialTheme.colorScheme.primary
                                            ),
                                            decorationBox = { innerTextField ->
                                                Box {
                                                    if (inputText.isEmpty()) {
                                                        Text(
                                                            text = if (editingMessageId != null) "修改消息内容..." else "输入消息...",
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            fontSize = fontSize.sp,
                                                            lineHeight = (fontSize + 5).sp
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            }
                                        )
                                    }
                                    VoicePhase.Recording -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(inputMinH)
                                                .onSizeChanged { centerHeightPx = it.height }
                                                .clip(RoundedCornerShape(50))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(horizontal = 14.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val primaryCol = MaterialTheme.colorScheme.primary
                                            Canvas(modifier = Modifier.fillMaxSize()) {
                                                val n = waveAmps.size
                                                if (n == 0) return@Canvas
                                                val barW = size.width / (n * 1.6f)
                                                val gap = barW * 0.6f
                                                val maxH = size.height * 0.85f
                                                waveAmps.forEachIndexed { i, amp ->
                                                    // Low floor keeps a idle baseline; signal already gain-compensated
                                                    val h = (amp.coerceIn(0.04f, 1f)) * maxH
                                                    val x = i * (barW + gap)
                                                    drawRoundRect(
                                                        color = primaryCol.copy(alpha = 0.85f),
                                                        topLeft = Offset(x, (size.height - h) / 2f),
                                                        size = Size(barW, h),
                                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    VoicePhase.Transcribing -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(inputMinH)
                                                .onSizeChanged { centerHeightPx = it.height }
                                                .clip(RoundedCornerShape(50))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(horizontal = 16.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            // Indeterminate line: soft left→right sweep
                                            val loadingProgress by animateFloatAsState(
                                                targetValue = 1f,
                                                animationSpec = infiniteRepeatable(
                                                    animation = tween(1100, easing = FastOutSlowInEasing),
                                                    repeatMode = RepeatMode.Reverse
                                                ),
                                                label = "asrLoad"
                                            )
                                            Box(modifier = Modifier.fillMaxWidth()) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(loadingProgress)
                                                        .height(3.dp)
                                                        .clip(RoundedCornerShape(50))
                                                        .background(
                                                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                                                colors = listOf(
                                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                                                )
                                                            )
                                                        )
                                                        .align(Alignment.CenterStart)
                                                )
                                            }
                                            Text(
                                                text = "识别中...",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        }
                                    }
                                    }
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Mic → send: while voice is active the mic fattens and swallows send.
                                // Total right-side width stays constant so layout doesn't jump.
                                val voiceActive = voicePhase != VoicePhase.Idle
                                val springSpec = spring<Dp>(
                                    dampingRatio = 0.75f,
                                    stiffness = 420f
                                )
                                val springFloat = spring<Float>(
                                    dampingRatio = 0.75f,
                                    stiffness = 420f
                                )
                                val micWidth by animateDpAsState(
                                    targetValue = if (voiceActive) (40.dp + 6.dp + 56.dp) else 40.dp,
                                    animationSpec = springSpec,
                                    label = "micW"
                                )
                                val midGap by animateDpAsState(
                                    targetValue = if (voiceActive) 0.dp else 6.dp,
                                    animationSpec = springSpec,
                                    label = "micGap"
                                )
                                val sendWidth by animateDpAsState(
                                    targetValue = if (voiceActive) 0.dp else 56.dp,
                                    animationSpec = springSpec,
                                    label = "sendW"
                                )
                                val sendAlpha by animateFloatAsState(
                                    targetValue = if (voiceActive) 0f else 1f,
                                    animationSpec = springFloat,
                                    label = "sendA"
                                )

                                // Mic circle — wide capsule (replaces send)
                                Box(
                                    modifier = Modifier
                                        .width(micWidth)
                                        .height(buttonHeight)
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            when (voicePhase) {
                                                VoicePhase.Recording -> MaterialTheme.colorScheme.primary
                                                VoicePhase.Transcribing -> MaterialTheme.colorScheme.surfaceVariant
                                                VoicePhase.Idle -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                            }
                                        )
                                        .clickable {
                                            when (voicePhase) {
                                                VoicePhase.Idle -> {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    requestAsrRecording()
                                                }
                                                VoicePhase.Recording -> {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    stopAsrAndUpload()
                                                }
                                                VoicePhase.Transcribing -> { /* locked */ }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    when (voicePhase) {
                                        VoicePhase.Transcribing -> CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        else -> {
                                            val recording = voicePhase == VoicePhase.Recording
                                            com.zhibai.app.ui.components.MorphIcon(
                                                from = com.zhibai.app.ui.components.StrokeIcon.Mic,
                                                to = com.zhibai.app.ui.components.StrokeIcon.Check,
                                                active = recording,
                                                color = if (recording) Color.White
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(20.dp),
                                                contentDescription = if (recording) "完成录音" else "语音输入"
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(midGap))

                                // Send / Stop — collapses to 0 width while mic owns the space
                                if (sendWidth > 0.5.dp) {
                                    val sendInteraction = remember { MutableInteractionSource() }
                                    val sendPressed by sendInteraction.collectIsPressedAsState()
                                    val sendScale by animateFloatAsState(
                                        targetValue = if (sendPressed) 0.90f else 1f,
                                        animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
                                        label = "sendScale"
                                    )
                                    val canSend = inputText.isNotBlank() || selectedFiles.isNotEmpty()
                                    // Stop: light = black circle / white square; dark = inverted
                                    val stopBg = if (isDarkTheme) Color.White else Color.Black
                                    val stopFg = if (isDarkTheme) Color.Black else Color.White
                                    // Smooth bg color transitions on send → generating
                                    val btnBg by animateColorAsState(
                                        targetValue = when {
                                            isGenerating -> stopBg
                                            canSend -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                        },
                                        label = "sendBtnBg"
                                    )
                                    val glyphColor by animateColorAsState(
                                        targetValue = when {
                                            isGenerating -> stopFg
                                            canSend -> Color.White
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        },
                                        label = "sendGlyph"
                                    )
                                    // Send: up-arrow flies out on tap, then stop square springs in
                                    var arrowFlyOut by remember { mutableStateOf(false) }
                                    var squareIn by remember { mutableStateOf(false) }
                                    LaunchedEffect(isGenerating) {
                                        if (isGenerating) {
                                            delay(150)
                                            squareIn = true
                                        } else {
                                            squareIn = false
                                            arrowFlyOut = false
                                        }
                                    }
                                    val arrowY by animateDpAsState(
                                        targetValue = if (arrowFlyOut) (-32).dp else 0.dp,
                                        animationSpec = tween(220, easing = FastOutLinearInEasing),
                                        label = "arrowFlyY"
                                    )
                                    val arrowAlpha by animateFloatAsState(
                                        targetValue = if (arrowFlyOut) 0f else 1f,
                                        animationSpec = tween(180),
                                        label = "arrowFlyA"
                                    )
                                    val squareScale by animateFloatAsState(
                                        targetValue = if (squareIn) 1f else 0f,
                                        animationSpec = spring(dampingRatio = 0.65f, stiffness = 520f),
                                        label = "stopSquare"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(sendWidth)
                                            .height(buttonHeight)
                                            .graphicsLayer { alpha = sendAlpha }
                                            .scale(sendScale)
                                            .clip(RoundedCornerShape(50))
                                            .background(btnBg)
                                            .clickable(
                                                interactionSource = sendInteraction,
                                                indication = null
                                            ) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                if (isGenerating) {
                                                    viewModel.stopGeneration()
                                                } else if (voicePhase == VoicePhase.Idle && canSend) {
                                                    arrowFlyOut = true
                                                    if (editingMessageId != null) {
                                                        viewModel.editAndResend(editingMessageId!!, inputText)
                                                        editingMessageId = null
                                                    } else {
                                                        val imageUris = selectedFiles.filter { it.isImage }.map { it.uri }
                                                        val audioFile = selectedFiles.firstOrNull { it.isAudio }
                                                        val videoFile = selectedFiles.firstOrNull { it.isVideo }
                                                        val otherFile = selectedFiles.firstOrNull { !it.isImage && !it.isAudio && !it.isVideo }
                                                        val fileToSend = audioFile ?: videoFile ?: otherFile
                                                        viewModel.sendMessage(
                                                            text = inputText,
                                                            imageUris = imageUris,
                                                            fileUri = fileToSend?.uri,
                                                            fileName = fileToSend?.name
                                                        )
                                                    }
                                                    inputText = ""
                                                    selectedFiles = emptyList()
                                                    plusExpanded = false
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // Up-arrow → flies out of the capsule on send
                                        Icon(
                                            Icons.Default.ArrowUpward,
                                            contentDescription = "Send",
                                            tint = glyphColor,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .graphicsLayer {
                                                    translationY = arrowY.toPx()
                                                    alpha = arrowAlpha
                                                }
                                        )
                                        // Rounded stop square → springs into center after the arrow leaves
                                        if (squareScale > 0.01f) {
                                            Box(
                                                modifier = Modifier
                                                    .size(13.dp)
                                                    .graphicsLayer {
                                                        scaleX = squareScale
                                                        scaleY = squareScale
                                                        alpha = squareScale
                                                    }
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(glyphColor)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Disclaimer sits on an opaque strip so text never shows through below the input
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        Text(
                            text = "非小米官方APP，内容由AI生成仅供参考",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp)
                        )
                    }
                    }
                } // end input area Box

                // Error banner
                error?.let { errorMsg ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MiMoColors.Error.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MiMoColors.Error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMsg,
                                fontSize = 13.sp,
                                color = MiMoColors.Error
                            )
                        }
                    }
                }

                // Tool call confirmation
                if (pendingToolCall != null) {
                    val call = pendingToolCall!!
                    val engine = viewModel.agentEngine
                    val riskLevel = engine?.tools?.find { it.name == call.tool }?.riskLevel ?: 3
                    val needsConfirm = riskLevel >= 3

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MiMoColors.Primary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Build,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MiMoColors.Primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "工具调用",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MiMoColors.Primary
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = when {
                                        riskLevel <= 2 -> MiMoColors.Success.copy(alpha = 0.15f)
                                        riskLevel <= 3 -> Color(0xFFFFA500).copy(alpha = 0.15f)
                                        else -> MiMoColors.Error.copy(alpha = 0.15f)
                                    }
                                ) {
                                    Text(
                                        "风险 $riskLevel",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        color = when {
                                            riskLevel <= 2 -> MiMoColors.Success
                                            riskLevel <= 3 -> Color(0xFFFFA500)
                                            else -> MiMoColors.Error
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                call.tool,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (call.args.isNotEmpty()) {
                                Text(
                                    call.args.entries.joinToString(", ") { "${it.key}: ${it.value}" },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (call.reason.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    call.reason,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = FontStyle.Italic
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.denyToolCall() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !toolExecuting
                                ) {
                                    Text("拒绝")
                                }
                                Button(
                                    onClick = { viewModel.approveToolCall() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !toolExecuting,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    if (toolExecuting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MiMoColors.OnPrimary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(if (toolExecuting) "Executing..." else "Approve")
                                }
                            }
                        }
                    }
                } // end if pendingToolCall
                } // end messages Box (weight 1f)

                } // end else (chat mode)
            } // end Column

            // ===== Floating chrome (no real top bar) =====
            // Solid block covers the button/title row entirely; gradient below fades content out.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .zIndex(1f)
                    .pointerInput(Unit) { /* clicks pass through */ }
            ) {
                // Opaque strip behind buttons + title
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(MaterialTheme.colorScheme.background)
                )
                // Fade below the buttons
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .align(Alignment.TopCenter)
                        .padding(top = 56.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f)
                                )
                            )
                        )
                )
            }

            // Floating circular menu button — same height as the right capsule (48dp).
            AnimatedVisibility(
                visible = !drawerState.isOpen,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.85f, animationSpec = tween(180)),
                exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.85f, animationSpec = tween(140)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 8.dp)
                    .zIndex(3f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), CircleShape)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            keyboardController?.hide()
                            coroutineScope.launch { drawerState.open() }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "Menu",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Title + action capsule — only with a live conversation / TTS / ASR.
            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 4 },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 4 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(2f)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Centered title with its own solid mask so content never shows through.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp, start = 60.dp, end = 128.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.88f)
                            )
                            .clickable { showModelSelector = true }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = when {
                                viewModel.isTTSMode -> "语音合成 TTS"
                                viewModel.isASRMode -> "语音识别 ASR"
                                else -> session?.title ?: "MiMo"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (hasMessages && !speechMode) {
                            Text(
                                text = modelLabel,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Capsule: new chat + overflow (same menus as before)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 12.dp)
                    ) {
                        var topMenuOpen by remember { mutableStateOf(false) }
                        val autoRead by viewModel.autoReadAloud.collectAsState()
                        val agentPhase by viewModel.agentPhase.collectAsState()
                        val showStudy = !speechMode
                        val showAgent = !speechMode && currentModel.contains("agent")
                        val showAutoRead = !viewModel.isASRMode

                        Box {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                                modifier = Modifier.alpha(if (topMenuOpen) 0.35f else 1f)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (speechMode) {
                                            val flashModel = modelIds.firstOrNull {
                                                it.contains("flash") &&
                                                    !it.contains("agent") &&
                                                    !it.contains("ultraspeed")
                                            } ?: "mimo-v2.6-flash"
                                            viewModel.setModel(flashModel)
                                            if (viewModel.currentSession.value?.messages?.isNotEmpty() == true) {
                                                viewModel.createNewSession()
                                            }
                                        } else {
                                            viewModel.createNewSession()
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = "新建对话",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    if (showStudy || showAgent || showAutoRead) {
                                        IconButton(onClick = { topMenuOpen = true }) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = "更多",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            var menuReady by remember { mutableStateOf(false) }
                            LaunchedEffect(topMenuOpen) {
                                if (topMenuOpen) {
                                    menuReady = false
                                    kotlinx.coroutines.delay(16)
                                    menuReady = true
                                } else {
                                    menuReady = false
                                }
                            }
                            val menuScale by animateFloatAsState(
                                targetValue = if (menuReady) 1f else 0.75f,
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                label = "topMenuScale"
                            )
                            val menuAlpha by animateFloatAsState(
                                targetValue = if (menuReady) 1f else 0f,
                                animationSpec = tween(160),
                                label = "topMenuAlpha"
                            )
                            DropdownMenu(
                                expanded = topMenuOpen,
                                onDismissRequest = { topMenuOpen = false },
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.graphicsLayer {
                                    scaleX = menuScale
                                    scaleY = menuScale
                                    alpha = menuAlpha
                                    transformOrigin = TransformOrigin(1f, 0f)
                                }
                            ) {
                                if (showStudy) {
                                    DropdownMenuItem(
                                        text = { Text("学习模式") },
                                        onClick = {
                                            studyMode = !studyMode
                                            topMenuOpen = false
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.School, contentDescription = null)
                                        },
                                        trailingIcon = {
                                            if (studyMode) Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    )
                                }
                                if (showAgent) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (agentPhase == com.zhibai.app.viewmodel.ChatViewModel.AgentPhase.PLAN)
                                                    "切换到 Build" else "切换到 Plan"
                                            )
                                        },
                                        onClick = {
                                            viewModel.setAgentPhase(
                                                if (agentPhase == com.zhibai.app.viewmodel.ChatViewModel.AgentPhase.PLAN)
                                                    com.zhibai.app.viewmodel.ChatViewModel.AgentPhase.BUILD
                                                else
                                                    com.zhibai.app.viewmodel.ChatViewModel.AgentPhase.PLAN
                                            )
                                            topMenuOpen = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (agentPhase == com.zhibai.app.viewmodel.ChatViewModel.AgentPhase.PLAN)
                                                    Icons.Default.Lightbulb else Icons.Default.Build,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("任务清单") },
                                        onClick = {
                                            showTaskPanel = !showTaskPanel
                                            topMenuOpen = false
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Checklist, contentDescription = null)
                                        },
                                        trailingIcon = {
                                            if (showTaskPanel) Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    )
                                }
                                if (showAutoRead) {
                                    DropdownMenuItem(
                                        text = { Text("自动朗读") },
                                        onClick = {
                                            viewModel.toggleAutoReadAloud()
                                            topMenuOpen = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (autoRead) Icons.Default.RecordVoiceOver else Icons.Default.Hearing,
                                                contentDescription = null
                                            )
                                        },
                                        trailingIcon = {
                                            if (autoRead) Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            } // end Box (floating chrome host)
        } // end Scaffold content

        // Gray overlay when drawer opens (clickable to close)
        if (dragProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = dragProgress * 0.12f }
                    .background(Color.Black)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { coroutineScope.launch { drawerState.close() } }
            )
        }
    } // end main content card

    // ---- PPT-style page overlays: slide in/out with cubic-bezier curve ----
    val pageEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    AnimatedVisibility(
        visible = showSettings,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(380, easing = pageEasing)
        ) + fadeIn(tween(380, easing = pageEasing)),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(320, easing = pageEasing)
        ) + fadeOut(tween(320, easing = pageEasing))
    ) {
        SettingsScreen(
            apiKey = savedApiKey,
            ttsVoice = ttsVoice,
            fontSize = fontSize,
            forceNewSessionOnModelSwitch = forceNewSession,
            agentEnabled = agentEnabled,
            agentPermissionMode = agentPermissionMode,
            agentApiKey = agentApiKey,
            agentBaseUrl = agentBaseUrl,
            workspacePath = viewModel.workspacePath.collectAsState().value,
            strictFaithful = viewModel.strictFaithful.collectAsState().value,
            promptAmbiguousClarify = viewModel.promptAmbiguousClarify.collectAsState().value,
            promptSensitiveRefusal = viewModel.promptSensitiveRefusal.collectAsState().value,
            unlimitedAttachments = viewModel.unlimitedAttachments.collectAsState().value,
            agentEngine = viewModel.agentEngine,
            onApiKeyChange = { viewModel.setApiKey(it) },
            localAiSection = { com.zhibai.app.ui.components.LocalAiSettingsSection(viewModel) },
            onTtsVoiceChange = { viewModel.setTtsVoice(it) },
            onFontSizeChange = { viewModel.setFontSize(it) },
            onForceNewSessionChange = { viewModel.setForceNewSessionOnModelSwitch(it) },
            onAgentEnabledChange = { viewModel.setAgentEnabled(it) },
            onAgentPermissionModeChange = { viewModel.setAgentPermissionMode(it) },
            onAgentApiKeyChange = { viewModel.setAgentApiKey(it) },
            onAgentBaseUrlChange = { viewModel.setAgentBaseUrl(it) },
            onWorkspacePathChange = { viewModel.setWorkspacePath(it) },
            onStrictFaithfulChange = { viewModel.setStrictFaithful(it) },
            onPromptAmbiguousClarifyChange = { viewModel.setPromptAmbiguousClarify(it) },
            onPromptSensitiveRefusalChange = { viewModel.setPromptSensitiveRefusal(it) },
            onUnlimitedAttachmentsChange = { viewModel.setUnlimitedAttachments(it) },
            inputStyle = viewModel.inputStyle.collectAsState().value,
            inputBlurColor = viewModel.inputBlurColor.collectAsState().value,
            inputShadowEnabled = viewModel.inputShadowEnabled.collectAsState().value,
            onInputStyleChange = { viewModel.setInputStyle(it) },
            onInputBlurColorChange = { viewModel.setInputBlurColor(it) },
            onInputShadowChange = { viewModel.setInputShadowEnabled(it) },
            customFontColor = viewModel.customFontColor.collectAsState().value,
            customBgColor = viewModel.customBgColor.collectAsState().value,
            customBubbleColor = viewModel.customBubbleColor.collectAsState().value,
            customTextColor = viewModel.customTextColor.collectAsState().value,
            customFontPath = viewModel.customFont.collectAsState().value,
            onCustomFontColorChange = { viewModel.setCustomFontColor(it) },
            onCustomBgColorChange = { viewModel.setCustomBgColor(it) },
            onCustomBubbleColorChange = { viewModel.setCustomBubbleColor(it) },
            onCustomTextColorChange = { viewModel.setCustomTextColor(it) },
            onCustomFontChange = { viewModel.setCustomFont(it) },
            prefDefaultModel = viewModel.prefDefaultModel.collectAsState().value,
            prefStartupMode = viewModel.prefStartupMode.collectAsState().value,
            prefStartupSession = viewModel.prefStartupSession.collectAsState().value,
            prefSessions = sessions.map { it.id to it.title },
            prefAgentThinkLevel = viewModel.prefAgentThinkLevel.collectAsState().value,
            prefAutoCompress = viewModel.prefAutoCompress.collectAsState().value,
            prefAutoCompressThreshold = viewModel.prefAutoCompressThreshold.collectAsState().value,
            prefLongTermMemory = viewModel.prefLongTermMemory.collectAsState().value,
            prefMemoryAutoWrite = viewModel.prefMemoryAutoWrite.collectAsState().value,
            prefModelHabit = viewModel.prefModelHabit.collectAsState().value,
            prefScenario = viewModel.prefScenario.collectAsState().value,
            onPrefDefaultModelChange = { viewModel.setPrefDefaultModel(it) },
            onPrefStartupModeChange = { viewModel.setPrefStartupMode(it) },
            onPrefStartupSessionChange = { viewModel.setPrefStartupSession(it) },
            onPrefAgentThinkLevelChange = { viewModel.setPrefAgentThinkLevel(it) },
            onPrefAutoCompressChange = { viewModel.setPrefAutoCompress(it) },
            onPrefAutoCompressThresholdChange = { viewModel.setPrefAutoCompressThreshold(it) },
            onPrefLongTermMemoryChange = { viewModel.setPrefLongTermMemory(it) },
            onPrefMemoryAutoWriteChange = { viewModel.setPrefMemoryAutoWrite(it) },
            onPrefModelHabitChange = { viewModel.setPrefModelHabit(it) },
            onPrefScenarioChange = { viewModel.setPrefScenario(it) },
            prefIslandExpandOnPhase = viewModel.prefIslandExpandOnPhase.collectAsState().value,
            onPrefIslandExpandOnPhaseChange = { viewModel.setPrefIslandExpandOnPhase(it) },
            prefIslandEnabled = viewModel.prefIslandEnabled.collectAsState().value,
            onPrefIslandEnabledChange = { viewModel.setPrefIslandEnabled(it) },
            prefIslandStreaming = viewModel.prefIslandStreaming.collectAsState().value,
            onPrefIslandStreamingChange = { viewModel.setPrefIslandStreaming(it) },
            onClearAllData = { viewModel.clearAllData() },
            onCustomPromptChange = { viewModel.setCustomPrompt(it) },
            onResetOnboarding = { viewModel.resetOnboarding() },
            onShowUpdatePreview = { viewModel.showUpdatePreview() },
            onDismiss = { viewModel.toggleSettings() }
        )
    }

    AnimatedVisibility(
        visible = showSkillsCenter,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(380, easing = pageEasing)
        ) + fadeIn(tween(380, easing = pageEasing)),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(320, easing = pageEasing)
        ) + fadeOut(tween(320, easing = pageEasing))
    ) {
        SkillsCenterScreen(
            skills = skillList,
            selectedSkill = skillContent,
            onBack = { viewModel.toggleSkillsCenter() },
            onOpenSkill = { viewModel.openSkill(it) },
            onCloseSkill = { viewModel.closeSkill() },
            onRefresh = { viewModel.refreshSkillList() }
        )
    }

    AnimatedVisibility(
        visible = showArchivePage,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(380, easing = pageEasing)
        ) + fadeIn(tween(380, easing = pageEasing)),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(320, easing = pageEasing)
        ) + fadeOut(tween(320, easing = pageEasing))
    ) {
        ArchiveScreen(
            viewModel = viewModel,
            onBack = { viewModel.toggleArchivePage() }
        )
    }
    } // end Box

    // ---- Thinking level Bottom Sheet (haptic on open) ----
    if (showThinkingSheet) {
        val thinkingLevel = viewModel.thinkingLevel.collectAsState().value
        val levels = listOf(
            "关闭" to "不使用深度思考",
            "低" to "1K tokens",
            "中" to "4K tokens",
            "高" to "8K tokens",
            "MAX" to "32K tokens（显著增加费用）"
        )
        ModalBottomSheet(
            onDismissRequest = { showThinkingSheet = false },
            sheetState = rememberModalBottomSheetState(),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "思考强度",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                levels.forEachIndexed { idx, (title, subtitle) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (idx == 4) {
                                    showThinkingSheet = false
                                    showMaxCostDialog = true
                                } else {
                                    viewModel.setThinkingLevel(idx)
                                    showThinkingSheet = false
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (thinkingLevel == idx) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                subtitle,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (thinkingLevel == idx) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- MAX thinking cost warning ----
    if (showMaxCostDialog) {
        AlertDialog(
            onDismissRequest = { showMaxCostDialog = false },
            title = { Text("开启 MAX 思考？") },
            text = {
                Text(
                    "MAX 会把思考预算强制拉到 32K tokens，并显著提高单次回复的长度与费用。\n\n" +
                        "确定要花这笔钱吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setThinkingLevel(4)
                    showMaxCostDialog = false
                }) {
                    Text("确认开启", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMaxCostDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ---- Context detail + compress ----
    if (showContextDetail) {
        val estTokens by viewModel.estimatedTokens.collectAsState()
        val isCompressing by viewModel.isCompressing.collectAsState()
        val compressProgress by viewModel.compressionProgress.collectAsState()
        val maxTokens = com.zhibai.app.viewmodel.ChatViewModel.MAX_CONTEXT_TOKENS
        val tokenFraction = (estTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
        val pct = (tokenFraction * 100).toInt()
        val maxM = maxTokens / 1_000_000.0
        AlertDialog(
            onDismissRequest = { showContextDetail = false },
            title = { Text("上下文用量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = String.format("%d/%.1fM Tokens (%d%%)", estTokens, maxM, pct),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    LinearProgressIndicator(
                        progress = { tokenFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50)),
                        color = when {
                            isCompressing -> Color(0xFF2196F3)
                            tokenFraction > 0.75f -> Color(0xFFFF9800)
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    if (isCompressing) {
                        Text(
                            "压缩中... ${(compressProgress * 100).toInt()}%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            "压缩会话会将历史消息总结成摘要，只保留摘要与最近几条消息。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.compressConversation() },
                    enabled = !isCompressing
                ) {
                    Text("压缩会话", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showContextDetail = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // Agent detected a missing permission — offer jump to system Settings
    if (pendingPermission != null) {
        val perm = pendingPermission!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissPermissionRequest() },
            title = { Text("需要系统权限") },
            text = {
                Text("Agent 需要「${viewModel.permissionLabel(perm.type)}」权限才能继续：\n\n${perm.reason}\n\n是否前往系统设置授权？授权后回来告诉 Agent 继续即可")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.openPermissionSettings(perm.type)
                    viewModel.dismissPermissionRequest()
                }) {
                    Text("去授权", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPermissionRequest() }) {
                    Text("暂不")
                }
            }
        )
    }

    // Media editor overlay (preview / zoom / crop / annotate)
    if (editingFileIndex in selectedFiles.indices) {
        val target = selectedFiles[editingFileIndex]
        com.zhibai.app.ui.editor.MediaEditorScreen(
            uri = target.uri,
            isVideo = target.isVideo,
            onDone = { newUri ->
                if (newUri != target.uri) {
                    selectedFiles = selectedFiles.toMutableList().apply {
                        this[editingFileIndex] = this[editingFileIndex].copy(uri = newUri)
                    }
                }
                editingFileIndex = -1
            },
            onDismiss = { editingFileIndex = -1 }
        )
    }

    // (session long-press menu is now a DropdownMenu anchored to each list item)

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = {
                Text(
                    if (batchSelectMode && batchSelectedIds.isNotEmpty())
                        "将删除选中 ${batchSelectedIds.size} 个对话，此操作不可撤销。"
                    else
                        "将删除该对话，此操作不可撤销。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (batchSelectMode && batchSelectedIds.isNotEmpty()) {
                            viewModel.deleteSessions(batchSelectedIds)
                            batchSelectMode = false
                            batchSelectedIds = emptySet()
                        } else if (pendingDeleteId != null) {
                            viewModel.deleteSession(pendingDeleteId!!)
                            pendingDeleteId = null
                        }
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // ---- Agent config broken warning dialog ----
    val agentConfigError by viewModel.agentConfigError.collectAsState()
    val agentConfigBroken by viewModel.agentConfigBroken.collectAsState()
    LaunchedEffect(agentConfigError) {
        if (agentConfigError != null && !agentConfigBroken) {
            // Auto-repair success message — show briefly then clear
            android.widget.Toast.makeText(context, agentConfigError, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearAgentConfigMessage()
        }
    }
    if (agentConfigBroken && agentConfigError != null) {
        AlertDialog(
            onDismissRequest = { /* force user to choose */ },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Agent 配置文件损坏")
                }
            },
            text = {
                Text("config.json 无法解析且自动修复失败。\n\n原因：$agentConfigError\n\nAgent 已暂时禁用。你可以回到正常聊天，或重置配置文件。")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.repairAgentConfig() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text("修复配置文件") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissAgentConfigError() }) { Text("回到正常聊天") }
            }
        )
    }
}

/**
 * Empty / idle state: mark + one line + optional chip row.
 * Includes a collapsed model switcher (tap to expand the full list).
 * Copy is English per design brief; selection is tonal, not accent-colored.
 */
@Composable
fun EmptyStateView(
    currentModel: String = "",
    modelIds: List<String> = emptyList(),
    agentEnabled: Boolean = false,
    localAiEnabled: Boolean = false,
    localAiModel: String = "",
    onSelectModel: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = com.zhibai.app.R.drawable.mimo_logo_foreground),
            contentDescription = null,
            modifier = Modifier.size(140.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "有什么可以帮你的？",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "— Xiaomi MiMo 驱动",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Model switcher — capsule segmented bar (Chat / Agent / ASR / TTS).
        // Sliding to Chat/Agent pops the model list; ASR/TTS switch directly.
        com.zhibai.app.ui.components.CapsuleModelBar(
            currentModel = currentModel,
            modelIds = modelIds,
            agentEnabled = agentEnabled,
            onSelectModel = onSelectModel
        )
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String {
    var name = "file"
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                name = it.getString(nameIndex) ?: name
            }
        }
    }
    return name
}

@Composable
private fun SpeedPanel(
    stats: com.zhibai.app.viewmodel.SpeedStats,
    isDarkTheme: Boolean,
    fontSize: Int,
    modifier: Modifier = Modifier
) {
    var collapsed by remember { mutableStateOf(false) }
    val bgColor = if (isDarkTheme) MiMoColors.AssistantBubbleDark else Color.White
    val textColor = if (isDarkTheme) MiMoColors.TextPrimaryDark else MiMoColors.TextPrimary
    val unitColor = Color(0xFFFF8C00)
    val answerColor = Color(0xFFFF8C00)
    val thinkColor = if (isDarkTheme) Color.White else Color.Black

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Header: title + first-token + collapse
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "UltraSpeed",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )
                if (stats.firstTokenMs >= 0) {
                    Text(
                        "首响 ${(stats.firstTokenMs / 1000f).format1()}s",
                        fontSize = 10.sp,
                        color = unitColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                IconButton(
                    onClick = { collapsed = !collapsed },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = textColor.copy(alpha = 0.5f)
                    )
                }
            }

            if (!collapsed) {
                // Instant | Avg | Peak — unit "/s" always orange
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SpeedStatItem("瞬时", stats.currentTotal, stats.finished, answerColor, unitColor, fontSize)
                    SpeedStatItem("均值", stats.avgTotal, true, answerColor, unitColor, fontSize)
                    SpeedStatItem("峰值", stats.peakTotal, true, answerColor, unitColor, fontSize)
                    SpeedStatItem("思考均值", stats.avgThink, true, thinkColor, unitColor, fontSize)
                    SpeedStatItem("用时", stats.elapsedSec, true, textColor, null, fontSize, isTime = true)
                }

                // Chart: think (black) then answer (orange) joined at thinkEndT
                if (stats.points.size >= 2) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val maxSpeed = remember(stats.points) {
                        stats.points.maxOf { maxOf(it.think, it.answer) }.coerceAtLeast(1f)
                    }
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        val w = size.width
                        val h = size.height
                        val tMin = stats.points.first().t
                        val tMax = stats.points.last().t.coerceAtLeast(tMin + 0.1f)
                        val tRange = tMax - tMin
                        val joinT = stats.thinkEndT

                        fun xOf(t: Float) = ((t - tMin) / tRange) * w
                        fun yOf(v: Float) = h - (v / maxSpeed) * h

                        // Think line: only points up to join (or all if no join yet)
                        val thinkPts = if (joinT >= 0f) stats.points.filter { it.t <= joinT + 0.001f } else stats.points
                        val thinkPath = Path()
                        thinkPts.forEachIndexed { i, pt ->
                            val x = xOf(pt.t); val y = yOf(pt.think)
                            if (i == 0) thinkPath.moveTo(x, y) else thinkPath.lineTo(x, y)
                        }
                        drawPath(thinkPath, thinkColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

                        // Answer line: points from join (or all if no think phase)
                        val ansPts = if (joinT >= 0f) stats.points.filter { it.t >= joinT - 0.001f } else stats.points
                        if (ansPts.size >= 2) {
                            val ansPath = Path()
                            ansPts.forEachIndexed { i, pt ->
                                val x = xOf(pt.t); val y = yOf(pt.answer)
                                if (i == 0) ansPath.moveTo(x, y) else ansPath.lineTo(x, y)
                            }
                            // Bridge: vertical join from last think y to first answer y
                            if (joinT >= 0f && thinkPts.isNotEmpty()) {
                                val lastThink = thinkPts.last()
                                val firstAns = ansPts.first()
                                val bridge = Path().apply {
                                    moveTo(xOf(lastThink.t), yOf(lastThink.think))
                                    lineTo(xOf(firstAns.t), yOf(firstAns.answer))
                                }
                                drawPath(bridge, answerColor.copy(alpha = 0.5f), style = Stroke(width = 1.5.dp.toPx()))
                            }
                            drawPath(ansPath, answerColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LegendDot(thinkColor, "思考")
                        LegendDot(answerColor, "回答")
                    }
                }
            }
        }
    }
}

private fun Float.format1(): String = "%.1f".format(this)

@Composable
private fun SpeedStatItem(
    label: String,
    value: Float,
    muted: Boolean,
    valueColor: Color,
    unitColor: Color?,
    fontSize: Int,
    isTime: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (isTime) "%.1f".format(value) else "%.0f".format(value),
                fontSize = (fontSize - 3).coerceAtLeast(11).sp,
                fontWeight = FontWeight.SemiBold,
                color = if (muted && value == 0f) MaterialTheme.colorScheme.onSurfaceVariant else valueColor
            )
            if (unitColor != null) {
                Text(
                    if (isTime) "s" else "/s",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = unitColor,
                    modifier = Modifier.padding(start = 1.dp, bottom = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Marker key for the free-text half of an "其它" answer. */
private const val OTHER_TEXT_SUFFIX = "::__other__"

/**
 * Interactive card when the Agent calls create_questionnaire.
 * One question at a time; Next after answering, Back to revise;
 * single/multi always offer 其它+自定义输入; 跳过 keeps a blank for that item;
 * 取消 cancels the whole run (returns USER_CANCELLED to the model).
 */
@Composable
private fun QuestionnaireCard(
    questions: List<com.zhibai.app.agent.QuestionnaireItem>,
    onSubmit: (Map<String, Any>) -> Unit,
    onCancel: () -> Unit
) {
    val answers = remember(questions) { mutableStateMapOf<String, Any>() }
    var step by remember(questions) { mutableStateOf(0) }
    var errorText by remember(questions) { mutableStateOf("") }
    // Auto-collapse tall question bodies; reset per question.
    var expanded by remember(questions, step) { mutableStateOf(false) }
    var contentHeightPx by remember(questions, step) { mutableStateOf(0) }
    val q = questions.getOrNull(step)
    val total = questions.size
    val isLast = step >= total - 1

    val density = LocalDensity.current
    val collapsedMaxHeight = 260.dp
    val collapsedMaxPx = with(density) { collapsedMaxHeight.toPx() }
    val contentTooTall = contentHeightPx > collapsedMaxPx
    val isDark = isSystemInDarkTheme()
    val fadeColor = if (isDark) Color.Black else Color.White
    val contentScrollState = rememberScrollState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MiMoColors.Primary.copy(alpha = 0.06f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .padding(14.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Assessment,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiMoColors.Primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "问卷",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiMoColors.Primary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "取消",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { onCancel() }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Step indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 ${step + 1} / $total 项",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                LinearProgressIndicator(
                    progress = { (step + 1f) / total.coerceAtLeast(1) },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp),
                    color = MiMoColors.Primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    drawStopIndicator = {}
                )
            }

            // Horizontally sliding question content — auto-collapses when too tall
            if (q != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!expanded) Modifier.heightIn(max = collapsedMaxHeight)
                            else Modifier
                        )
                        .clipToBounds()
                ) {
                    // Full content height is reported even while the viewport is clipped,
                    // because verticalScroll measures its child with unbounded height.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (!expanded) Modifier.verticalScroll(contentScrollState)
                                else Modifier
                            )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { contentHeightPx = it.height }
                        ) {
                            AnimatedContent(
                                targetState = step,
                                transitionSpec = {
                                    val dur = 320
                                    val easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
                                    if (targetState > initialState) {
                                        (slideInHorizontally(tween(dur, easing = easing)) { it } +
                                                fadeIn(tween(dur))) togetherWith
                                                (slideOutHorizontally(tween(dur, easing = easing)) { -it } +
                                                        fadeOut(tween(dur / 2)))
                                    } else {
                                        (slideInHorizontally(tween(dur, easing = easing)) { -it } +
                                                fadeIn(tween(dur))) togetherWith
                                                (slideOutHorizontally(tween(dur, easing = easing)) { it } +
                                                        fadeOut(tween(dur / 2)))
                                    }
                                },
                                label = "qSlide"
                            ) { targetStep ->
                                val targetQ = questions.getOrNull(targetStep)
                                if (targetQ != null) {
                                    QuestionContent(
                                        q = targetQ,
                                        answers = answers,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    // Opacity fade at the fold — white in light mode, black in dark mode.
                    // Only while collapsed and only when there is more content below.
                    if (!expanded && contentTooTall && contentScrollState.value < contentScrollState.maxValue) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            fadeColor.copy(alpha = 0.75f),
                                            fadeColor
                                        )
                                    )
                                )
                        )
                    }
                }

                if (contentTooTall) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (expanded) "收起" else "展开全部",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (errorText.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(errorText, fontSize = 12.sp, color = MiMoColors.Error)
            }

            // Navigation buttons
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step--; errorText = "" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("上一题")
                    }
                } else {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("不填了")
                    }
                }
                if (!isLast) {
                    OutlinedButton(
                        onClick = {
                            answers.remove(q?.id ?: return@OutlinedButton)
                            answers.remove((q.id) + OTHER_TEXT_SUFFIX)
                            errorText = ""
                            step++
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("跳过")
                    }
                }
                Button(
                    onClick = {
                        val cur = questions.getOrNull(step) ?: return@Button
                        val answered = when (cur.type) {
                            "single" -> {
                                val opt = answers[cur.id] as? String
                                if (opt == "其它") {
                                    (answers[cur.id + OTHER_TEXT_SUFFIX] as? String)?.isNotBlank() == true
                                } else opt?.isNotBlank() == true
                            }
                            "multi" -> {
                                val list = (answers[cur.id] as? List<*>)?.map { it.toString() } ?: emptyList()
                                if ("其它" in list) {
                                    list.isNotEmpty() && (answers[cur.id + OTHER_TEXT_SUFFIX] as? String)?.isNotBlank() == true
                                } else list.isNotEmpty()
                            }
                            "text" -> (answers[cur.id] as? String)?.isNotBlank() == true
                            else -> answers[cur.id] is Int
                        }
                        if (cur.required && !answered) {
                            errorText = "这是必答题，或点「跳过」"
                            return@Button
                        }
                        errorText = ""
                        if (isLast) {
                            val final = mutableMapOf<String, Any>()
                            for (item in questions) {
                                val raw = answers[item.id]
                                val otherText = answers[item.id + OTHER_TEXT_SUFFIX] as? String
                                when {
                                    raw == "其它" && otherText != null -> final[item.id] = otherText
                                    raw is List<*> -> {
                                        val cleaned = raw.map { it.toString() }.filter { it != "其它" }.toMutableList()
                                        if ("其它" in raw.map { it.toString() } && otherText != null) {
                                            cleaned.add(otherText)
                                        }
                                        final[item.id] = cleaned
                                    }
                                    raw != null -> final[item.id] = raw
                                }
                            }
                            onSubmit(final)
                        } else {
                            step++
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(if (isLast) "提交" else "下一题")
                }
            }
        }
    }
}

/** Renders the question body for a single step (extracted for AnimatedContent). */
@Composable
private fun QuestionContent(
    q: com.zhibai.app.agent.QuestionnaireItem,
    answers: MutableMap<String, Any>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row {
            Text(
                q.question,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (q.required) {
                Text(" *", fontSize = 15.sp, color = MiMoColors.Error)
            }
        }

        when (q.type) {
            "single" -> {
                val selectedOpt = answers[q.id] as? String
                val otherSelected = selectedOpt == "其它" ||
                    (answers[q.id + OTHER_TEXT_SUFFIX] as? String)?.isNotBlank() == true &&
                    selectedOpt == null
                q.options.forEach { opt ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { answers[q.id] = opt }
                    ) {
                        RadioButton(
                            selected = selectedOpt == opt,
                            onClick = { answers[q.id] = opt }
                        )
                        Text(opt, fontSize = 14.sp)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { answers[q.id] = "其它" }
                ) {
                    RadioButton(
                        selected = selectedOpt == "其它",
                        onClick = { answers[q.id] = "其它" }
                    )
                    Text("其它", fontSize = 14.sp)
                }
                if (selectedOpt == "其它" || otherSelected) {
                    OutlinedTextField(
                        value = answers[q.id + OTHER_TEXT_SUFFIX] as? String ?: "",
                        onValueChange = { answers[q.id + OTHER_TEXT_SUFFIX] = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp),
                        placeholder = { Text("输入自定义内容...", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
            "multi" -> {
                val selected = (answers[q.id] as? List<*>)?.map { it.toString() } ?: emptyList()
                q.options.forEach { opt ->
                    val checked = opt in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                answers[q.id] = if (checked) selected - opt else selected + opt
                            }
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                answers[q.id] = if (checked) selected - opt else selected + opt
                            }
                        )
                        Text(opt, fontSize = 14.sp)
                    }
                }
                val otherChecked = "其它" in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            answers[q.id] = if (otherChecked) selected - "其它" else selected + "其它"
                        }
                ) {
                    Checkbox(
                        checked = otherChecked,
                        onCheckedChange = {
                            answers[q.id] = if (otherChecked) selected - "其它" else selected + "其它"
                        }
                    )
                    Text("其它", fontSize = 14.sp)
                }
                if (otherChecked) {
                    OutlinedTextField(
                        value = answers[q.id + OTHER_TEXT_SUFFIX] as? String ?: "",
                        onValueChange = { answers[q.id + OTHER_TEXT_SUFFIX] = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp),
                        placeholder = { Text("输入自定义内容...", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
            "rating" -> {
                val current = answers[q.id] as? Int
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..q.maxRating.coerceIn(2, 10)).forEach { n ->
                        val active = current == n
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) MiMoColors.Primary else Color.Transparent,
                            border = BorderStroke(1.dp, if (active) MiMoColors.Primary else MaterialTheme.colorScheme.outline),
                            modifier = Modifier.clickable { answers[q.id] = n }
                        ) {
                            Text(
                                n.toString(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) MiMoColors.OnPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
            else -> {
                OutlinedTextField(
                    value = answers[q.id] as? String ?: "",
                    onValueChange = { answers[q.id] = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入回答...", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }
    }
}

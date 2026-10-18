package com.mimo.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mimo.app.ui.components.MessageBubble
import com.mimo.app.ui.theme.MiMoColors
import com.mimo.app.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val isImage: Boolean,
    val isAudio: Boolean = false,
    val isVideo: Boolean = false
)

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
    val showSpeedPanel = currentModel.contains("ultraspeed") && speedStats != null

    var inputText by remember { mutableStateOf("") }
    var selectedFiles by remember { mutableStateOf<List<SelectedFile>>(emptyList()) }
    var editingFileIndex by remember { mutableStateOf(-1) }
    var showModelSelector by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var editingSession by remember { mutableStateOf<com.mimo.app.data.model.ChatSession?>(null) }
    var editTitleText by remember { mutableStateOf("") }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<android.speech.SpeechRecognizer?>(null) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val showScrollToBottom = remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val keyboardController = LocalSoftwareKeyboardController.current

    // Initialize TTS service with API key
    val ttsService = remember { com.mimo.app.data.api.TTSService() }
    LaunchedEffect(savedApiKey) { ttsService.updateApiKey(savedApiKey) }

    fun startVoiceInput() {
        val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = sr
        isRecording = true
        isTranscribing = false

        sr.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() { isTranscribing = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isRecording = false }
            override fun onError(error: Int) {
                isRecording = false
                isTranscribing = false
                sr.destroy()
                speechRecognizer = null
            }
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    inputText = text
                }
                isRecording = false
                isTranscribing = false
                sr.destroy()
                speechRecognizer = null
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        sr.startListening(intent)
    }

    fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        isRecording = false
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceInput()
    }

    // Track scroll position + bottom pin (auto-follow only while user is at bottom)
    val isAtBottom = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                val totalItems = session?.messages?.size ?: 0
                showScrollToBottom.value = index < totalItems - 2
            }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) true
            else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }.distinctUntilChanged().collect { near -> isAtBottom.value = near }
    }

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

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

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

    // Auto-follow while pinned to bottom. Keyed on reasoningContent too so thinking streams scroll.
    // Instant scrollToItem (not animate) — animation restarts every delta and fights user scroll.
    LaunchedEffect(
        session?.messages?.size,
        session?.messages?.lastOrNull()?.content,
        session?.messages?.lastOrNull()?.reasoningContent
    ) {
        if (isAtBottom.value) {
            val size = session?.messages?.size ?: 0
            if (size > 0) listState.scrollToItem(size - 1, Int.MAX_VALUE)
        }
    }

    if (showSettings) {
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
            unlimitedAttachments = viewModel.unlimitedAttachments.collectAsState().value,
            agentEngine = viewModel.agentEngine,
            onApiKeyChange = { viewModel.setApiKey(it) },
            onTtsVoiceChange = { viewModel.setTtsVoice(it) },
            onFontSizeChange = { viewModel.setFontSize(it) },
            onForceNewSessionChange = { viewModel.setForceNewSessionOnModelSwitch(it) },
            onAgentEnabledChange = { viewModel.setAgentEnabled(it) },
            onAgentPermissionModeChange = { viewModel.setAgentPermissionMode(it) },
            onAgentApiKeyChange = { viewModel.setAgentApiKey(it) },
            onAgentBaseUrlChange = { viewModel.setAgentBaseUrl(it) },
            onWorkspacePathChange = { viewModel.setWorkspacePath(it) },
            onStrictFaithfulChange = { viewModel.setStrictFaithful(it) },
            onUnlimitedAttachmentsChange = { viewModel.setUnlimitedAttachments(it) },
            onClearAllData = { viewModel.clearAllData() },
            onCustomPromptChange = { viewModel.setCustomPrompt(it) },
            onDismiss = { viewModel.toggleSettings() }
        )
        return
    }

    // Back handler for drawer
    if (drawerState.isOpen) {
        BackHandler { coroutineScope.launch { drawerState.close() } }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                // Drawer header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "MiMo",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiMoColors.Primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Xiaomi AI Assistant",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                // New chat button
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    label = { Text("新对话") },
                    selected = false,
                    onClick = {
                        viewModel.createNewSession()
                        coroutineScope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("搜索对话...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(18.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )

                // Session list
                Text(
                    text = "History",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )

                val filteredSessions = remember(sessions, searchQuery) {
                    val validSessions = sessions.filter { it.messages.isNotEmpty() }
                    if (searchQuery.isBlank()) {
                        validSessions.sortedByDescending { it.createdAt }
                    } else {
                        val query = searchQuery.lowercase()
                        validSessions.filter { sess ->
                            sess.title.lowercase().contains(query) ||
                            sess.messages.any { msg ->
                                msg.content.lowercase().contains(query) ||
                                msg.reasoningContent.lowercase().contains(query)
                            }
                        }.sortedByDescending { it.createdAt }
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

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredSessionsWithSnippets) { (sess, matchSnippet) ->
                        val isSelected = sess.id == session?.id

                        NavigationDrawerItem(
                            icon = {
                                Icon(
                                    Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = {
                                Column {
                                    Text(
                                        text = sess.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 14.sp
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
                            },
                            selected = isSelected,
                            onClick = {
                                viewModel.selectSession(sess.id)
                                coroutineScope.launch { drawerState.close() }
                            },
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                                .combinedClickable(
                                    onClick = {
                                        viewModel.selectSession(sess.id)
                                        coroutineScope.launch { drawerState.close() }
                                    },
                                    onLongClick = {
                                        editingSession = sess
                                        editTitleText = sess.title
                                    }
                                ),
                            badge = {
                                IconButton(
                                    onClick = { viewModel.deleteSession(sess.id) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Delete",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        )
                    }
                }

                HorizontalDivider()

                // Settings
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                    selected = false,
                    onClick = {
                        viewModel.toggleSettings()
                        coroutineScope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = session?.title ?: "MiMo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            keyboardController?.hide()
                            coroutineScope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    },
                    actions = {
                        // Phone Agent button (hidden unless master switch is on)
                        if (com.mimo.app.agent.AgentEngine.PHONE_AGENT_ENABLED) {
                            IconButton(onClick = onNavigateToPhoneAgent) {
                                Icon(
                                    Icons.Default.PhoneAndroid,
                                    contentDescription = "Phone Agent",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        // Model selector chip
                        FilterChip(
                            onClick = {
                                showModelSelector = !showModelSelector
                                if (showModelSelector) viewModel.refreshModelList()
                            },
                            label = {
                                Text(
                                    text = when {
                                        currentModel == "tts-read" -> "TTS 原版"
                                        currentModel == "tts-design" -> "TTS 设计"
                                        currentModel == "tts-clone" -> "TTS 克隆"
                                        currentModel == "asr" -> "ASR"
                                        currentModel.contains("agent") && currentModel.contains("ultraspeed") -> "Agent Ultra"
                                        currentModel.contains("agent") -> "Agent"
                                        currentModel.contains("ultraspeed") -> "Ultra"
                                        currentModel.contains("pro") -> "Pro"
                                        currentModel.contains("flash") -> "Flash"
                                        // Unknown/new model id — show its last segment instead of mislabeling
                                        else -> currentModel.substringAfterLast("-").take(8).ifBlank { "Model" }
                                    },
                                    fontSize = 12.sp
                                )
                            },
                            selected = currentModel.contains("pro"),
                            leadingIcon = {
                                    Icon(
                                        when {
                                            currentModel.startsWith("tts") -> Icons.Default.RecordVoiceOver
                                            currentModel == "asr" -> Icons.Default.Mic
                                            currentModel.contains("agent") -> Icons.Default.SmartToy
                                            currentModel.contains("ultraspeed") -> Icons.Default.Speed
                                            currentModel.contains("pro") -> Icons.Default.Star
                                            else -> Icons.Default.FlashOn
                                        },
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            modifier = Modifier.height(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding()
            ) {
                // Model dropdown
                AnimatedVisibility(
                    visible = showModelSelector,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(8.dp)
                                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp / 3)
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Chat models + agent variants; TTS/ASR listed as first-class modes (no agent)
                            val models = buildList {
                                modelIds.filter { it.startsWith("mimo-v") }.forEach { id ->
                                    val desc = when {
                                        id.contains("ultraspeed") -> "UltraSpeed - 实时超快生成"
                                        id.contains("pro") -> "Pro - 复杂推理"
                                        id.contains("flash") -> "Flash - 日常快问快答"
                                        else -> id.substringAfter("mimo-", "对话")
                                    }
                                    add(id to desc)
                                    if (agentEnabled) {
                                        add("$id-agent" to "Agent - $desc + 文件工具")
                                    }
                                }
                                add("tts-read" to "TTS 原版 - 预置音色朗读")
                                add("tts-design" to "TTS 设计 - 自然语言描述音色")
                                add("tts-clone" to "TTS 克隆 - 上传样本复刻声线")
                                add("asr" to "ASR - 语音转文字")
                            }
                            models.forEach { (model, desc) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            viewModel.setModel(model)
                                            showModelSelector = false
                                        }
                                        .background(
                                            if (currentModel == model)
                                                MiMoColors.Primary.copy(alpha = 0.1f)
                                            else Color.Transparent
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = currentModel == model,
                                        onClick = {
                                            viewModel.setModel(model)
                                            showModelSelector = false
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = model,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = desc,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (session?.messages?.isEmpty() != false) {
                            item {
                                EmptyStateView()
                            }
                        }

                        items(session?.messages ?: emptyList()) { message ->
                            MessageBubble(
                                message = message,
                                isDarkTheme = isDarkTheme,
                                fontSize = fontSize,
                                onEdit = { text ->
                                    // Show edit dialog with original text, then editAndResend
                                    inputText = text
                                    editingMessageId = message.id
                                },
                                onCopy = { text ->
                                    com.mimo.app.ui.components.ClipboardHelper.copyWithToast(context, text, label = "消息")
                                },
                                onReadAloud = { text ->
                                    if (isSpeaking) {
                                        viewModel.stopSpeaking()
                                    } else {
                                        viewModel.readAloud(text)
                                    }
                                },
                                isSpeaking = isSpeaking
                            )
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

                    // Scroll to bottom button
                    if (showScrollToBottom.value) {
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    val size = session?.messages?.size ?: 0
                                    if (size > 0) {
                                        listState.animateScrollToItem(size - 1)
                                    }
                                }
                            },
                            containerColor = MiMoColors.Primary,
                            contentColor = MiMoColors.OnPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom"
                            )
                        }
                    }
                }

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
                                    colors = ButtonDefaults.buttonColors(containerColor = MiMoColors.Primary)
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
                }

                // Input area
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        // Toolbar row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Thinking toggle
                            FilterChip(
                                onClick = { viewModel.toggleThinking() },
                                label = { Text("深度思考", fontSize = 11.sp) },
                                selected = enableThinking,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Psychology,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                modifier = Modifier.height(28.dp)
                            )

                            // Search toggle
                            FilterChip(
                                onClick = { viewModel.toggleSearch() },
                                label = { Text("联网搜索", fontSize = 11.sp) },
                                selected = enableSearch,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                modifier = Modifier.height(28.dp)
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            // Image picker
                            IconButton(
                                onClick = { imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = "Attach image",
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // File picker
                            IconButton(
                                onClick = { filePicker.launch("*/*") },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.AttachFile,
                                    contentDescription = "Attach file",
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Audio picker
                            IconButton(
                                onClick = { filePicker.launch("audio/*") },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = "Attach audio",
                                    modifier = Modifier.size(18.dp)
                                )
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
                                    val clickable = file.isImage || file.isVideo
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MiMoColors.Primary.copy(alpha = 0.12f))
                                            .then(
                                                if (clickable) Modifier.clickable { editingFileIndex = index }
                                                else Modifier
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (file.isImage) {
                                            coil.compose.AsyncImage(
                                                model = file.uri,
                                                contentDescription = "Tap to edit",
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                when {
                                                    file.isAudio -> Icons.Default.MusicNote
                                                    file.isVideo -> Icons.Default.Videocam
                                                    else -> Icons.Default.AttachFile
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(22.dp),
                                                tint = MiMoColors.Primary
                                            )
                                        }
                                        if (clickable) {
                                            Surface(
                                                color = Color.Black.copy(alpha = 0.45f),
                                                shape = RoundedCornerShape(4.dp),
                                                modifier = Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .padding(bottom = 2.dp)
                                            ) {
                                                Text(
                                                    if (file.isVideo) "预览" else "编辑",
                                                    fontSize = 9.sp,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        // remove button
                                        IconButton(
                                            onClick = { selectedFiles = selectedFiles.toMutableList().apply { removeAt(index) } },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(18.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove",
                                                modifier = Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.onSurface
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
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MiMoColors.Primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "正在编辑消息（将替换原消息）",
                                    fontSize = 12.sp,
                                    color = MiMoColors.Primary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { editingMessageId = null; inputText = "" },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Cancel edit",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        // Input field
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text(
                                        if (editingMessageId != null) "Edit message..."
                                        else "Message MiMo...",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                maxLines = 6,
                                shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MiMoColors.Primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            // Voice input button
                            IconButton(
                                onClick = {
                                    if (isRecording) {
                                        stopVoiceInput()
                                    } else if (!isTranscribing) {
                                        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                            startVoiceInput()
                                        } else {
                                            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isRecording) MiMoColors.Error
                                        else if (isTranscribing) MaterialTheme.colorScheme.outline
                                        else Color.Transparent
                                    )
                            ) {
                                if (isTranscribing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                        color = MiMoColors.Primary
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Mic,
                                        contentDescription = if (isRecording) "Stop recording" else "Voice input",
                                        tint = if (isRecording) Color.White
                                        else if (inputText.isBlank() && selectedFiles.isEmpty()) MaterialTheme.colorScheme.outline
                                        else MiMoColors.Primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Send / Stop button
                            IconButton(
                                onClick = {
                                    if (isGenerating) {
                                        viewModel.stopGeneration()
                                    } else if (inputText.isNotBlank() || selectedFiles.isNotEmpty()) {
                                        if (editingMessageId != null) {
                                            // Edit and resend
                                            viewModel.editAndResend(editingMessageId!!, inputText)
                                            editingMessageId = null
                                        } else {
                                            // Normal send
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
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (inputText.isNotBlank() || selectedFiles.isNotEmpty() || isGenerating)
                                            MiMoColors.Primary
                                        else MaterialTheme.colorScheme.outline
                                    )
                            ) {
                                Icon(
                                    if (isGenerating) Icons.Default.Stop
                                    else Icons.AutoMirrored.Filled.Send,
                                    contentDescription = if (isGenerating) "Stop" else "Send",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
                } // end else (chat mode)
            }
        }
    }

    // Agent detected a missing permission → offer jump to system Settings
    if (pendingPermission != null) {
        val perm = pendingPermission!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissPermissionRequest() },
            title = { Text("需要系统权限") },
            text = {
                Text("Agent 需要「${viewModel.permissionLabel(perm.type)}」权限才能继续：\n\n${perm.reason}\n\n是否前往系统设置授权？授权后回来告诉 Agent 继续即可。")
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
        com.mimo.app.ui.editor.MediaEditorScreen(
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

    // Edit title dialog (outside drawer)
    if (editingSession != null) {
        AlertDialog(
            onDismissRequest = { editingSession = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = editTitleText,
                    onValueChange = { editTitleText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("输入新标题") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editTitleText.isNotBlank()) {
                            viewModel.renameSession(editingSession!!.id, editTitleText.trim())
                            editingSession = null
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingSession = null }) { Text("取消") }
            }
        )
    }
}

@Composable
fun EmptyStateView() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = com.mimo.app.R.drawable.mimo_logo_foreground),
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
            "由 Xiaomi MiMo 驱动",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    stats: com.mimo.app.viewmodel.SpeedStats,
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

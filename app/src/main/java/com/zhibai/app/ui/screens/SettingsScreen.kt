package com.zhibai.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.ui.theme.MiMoColors

private enum class SettingsPage {
    MAIN, API_KEY, VOICE, FONT_SIZE, AGENT, ABOUT, FORBIDDEN, TOOL_PERMISSIONS, INPUT_STYLE, PREFERENCES, WIRELESS_ADB, ISLAND, AUTOGLM
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    apiKey: String,
    ttsVoice: String,
    fontSize: Int,
    forceNewSessionOnModelSwitch: Boolean,
    agentEnabled: Boolean,
    agentPermissionMode: Int,
    agentApiKey: String,
    agentBaseUrl: String,
    workspacePath: String = "",
    strictFaithful: Boolean = true,
    promptAmbiguousClarify: Boolean = true,
    promptSensitiveRefusal: Boolean = true,
    unlimitedAttachments: Boolean = false,
    agentEngine: com.zhibai.app.agent.AgentEngine? = null,
    // Input bar appearance
    inputStyle: String = "glass",
    inputBlurColor: String = "#B0FFFFFF",
    inputShadowEnabled: Boolean = true,
    onInputStyleChange: (String) -> Unit = {},
    onInputBlurColorChange: (String) -> Unit = {},
    onInputShadowChange: (Boolean) -> Unit = {},
    // Custom theme colors
    customFontColor: String = "",
    customBgColor: String = "",
    customBubbleColor: String = "",
    customTextColor: String = "",
    customFontPath: String = "",
    onCustomFontColorChange: (String) -> Unit = {},
    onCustomBgColorChange: (String) -> Unit = {},
    onCustomBubbleColorChange: (String) -> Unit = {},
    onCustomTextColorChange: (String) -> Unit = {},
    onCustomFontChange: (String) -> Unit = {},
    // Preferences
    prefDefaultModel: String = "",
    prefStartupMode: String = "new",
    prefStartupSession: String = "",
    prefSessions: List<Pair<String, String>> = emptyList(),
    prefAgentThinkLevel: Int = 2,
    prefAutoCompress: Boolean = false,
    prefAutoCompressThreshold: Int = 800000,
    prefAutoSaveImages: String = "ask",
    workspaceDir: java.io.File = java.io.File(""),
    prefLongTermMemory: Boolean = false,
    prefMemoryAutoWrite: Boolean = false,
    prefModelHabit: String = "",
    prefScenario: String = "",
    onPrefDefaultModelChange: (String) -> Unit = {},
    onPrefStartupModeChange: (String) -> Unit = {},
    onPrefStartupSessionChange: (String) -> Unit = {},
    onPrefAgentThinkLevelChange: (Int) -> Unit = {},
    onPrefAutoCompressChange: (Boolean) -> Unit = {},
    onPrefAutoCompressThresholdChange: (Int) -> Unit = {},
    onPrefAutoSaveImagesChange: (String) -> Unit = {},
    onPrefLongTermMemoryChange: (Boolean) -> Unit = {},
    onPrefMemoryAutoWriteChange: (Boolean) -> Unit = {},
    onPrefModelHabitChange: (String) -> Unit = {},
    onPrefScenarioChange: (String) -> Unit = {},
    prefIslandExpandOnPhase: Boolean = false,
    onPrefIslandExpandOnPhaseChange: (Boolean) -> Unit = {},
    prefIslandEnabled: Boolean = true,
    onPrefIslandEnabledChange: (Boolean) -> Unit = {},
    prefIslandStreaming: Boolean = true,
    onPrefIslandStreamingChange: (Boolean) -> Unit = {},
    onApiKeyChange: (String) -> Unit,
    localAiSection: @Composable () -> Unit = {},
    onTtsVoiceChange: (String) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onForceNewSessionChange: (Boolean) -> Unit,
    onAgentEnabledChange: (Boolean) -> Unit,
    onAgentPermissionModeChange: (Int) -> Unit,
    onAgentApiKeyChange: (String) -> Unit,
    onAgentBaseUrlChange: (String) -> Unit,
    onWorkspacePathChange: (String) -> Unit = {},
    onStrictFaithfulChange: (Boolean) -> Unit = {},
    onPromptAmbiguousClarifyChange: (Boolean) -> Unit = {},
    onPromptSensitiveRefusalChange: (Boolean) -> Unit = {},
    onUnlimitedAttachmentsChange: (Boolean) -> Unit = {},
    onClearAllData: () -> Unit,
    onCustomPromptChange: (String) -> Unit,
    onResetOnboarding: () -> Unit = {},
    onShowUpdatePreview: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var currentPage by remember { mutableStateOf(SettingsPage.MAIN) }
    var pageTitle by remember { mutableStateOf("Settings") }

    BackHandler {
        if (currentPage == SettingsPage.MAIN) onDismiss()
        else { currentPage = SettingsPage.MAIN; pageTitle = "Settings" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPage == SettingsPage.MAIN) onDismiss()
                        else { currentPage = SettingsPage.MAIN; pageTitle = "Settings" }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        when (currentPage) {
            SettingsPage.MAIN -> SettingsMainMenu(
                onNavigate = { page, title -> currentPage = page; pageTitle = title },
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.ISLAND -> IslandSettingsPage(
                enabled = prefIslandEnabled,
                onEnabledChange = onPrefIslandEnabledChange,
                showStreaming = prefIslandStreaming,
                onShowStreamingChange = onPrefIslandStreamingChange,
                expandOnPhase = prefIslandExpandOnPhase,
                onExpandOnPhaseChange = onPrefIslandExpandOnPhaseChange,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.AUTOGLM -> AutoGLMSettingsPage(
                onNavigateToWirelessAdb = { currentPage = SettingsPage.WIRELESS_ADB; pageTitle = "无线调试 ADB" },
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.API_KEY -> ApiKeyPage(
                apiKey = apiKey,
                onSave = onApiKeyChange,
                localAiSection = localAiSection,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.VOICE -> VoicePage(
                currentVoice = ttsVoice,
                onSelect = onTtsVoiceChange,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.FONT_SIZE -> FontSizePage(
                currentSize = fontSize,
                onSelect = onFontSizeChange,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.AGENT -> AgentSettingsPage(
                agentEnabled = agentEnabled,
                agentPermissionMode = agentPermissionMode,
                agentApiKey = agentApiKey,
                agentBaseUrl = agentBaseUrl,
                workspacePath = workspacePath,
                onAgentEnabledChange = onAgentEnabledChange,
                onAgentPermissionModeChange = onAgentPermissionModeChange,
                onAgentApiKeyChange = onAgentApiKeyChange,
                onAgentBaseUrlChange = onAgentBaseUrlChange,
                onWorkspacePathChange = onWorkspacePathChange,
                onNavigateToToolPermissions = { currentPage = SettingsPage.TOOL_PERMISSIONS; pageTitle = "工具权限" },
                onNavigateToWirelessAdb = { currentPage = SettingsPage.WIRELESS_ADB; pageTitle = "无线调试 ADB" },
                localAiSection = localAiSection,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.ABOUT -> AboutPage(
                onNavigateToForbidden = { currentPage = SettingsPage.FORBIDDEN; pageTitle = "隐藏设置" },
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.FORBIDDEN -> ForbiddenPage(
                forceNewSession = forceNewSessionOnModelSwitch,
                strictFaithful = strictFaithful,
                promptAmbiguousClarify = promptAmbiguousClarify,
                promptSensitiveRefusal = promptSensitiveRefusal,
                unlimitedAttachments = unlimitedAttachments,
                onForceNewSessionChange = onForceNewSessionChange,
                onStrictFaithfulChange = onStrictFaithfulChange,
                onPromptAmbiguousClarifyChange = onPromptAmbiguousClarifyChange,
                onPromptSensitiveRefusalChange = onPromptSensitiveRefusalChange,
                onUnlimitedAttachmentsChange = onUnlimitedAttachmentsChange,
                onResetOnboarding = onResetOnboarding,
                onShowUpdatePreview = onShowUpdatePreview,
                onClearAllData = onClearAllData,
                onCustomPromptChange = onCustomPromptChange,
                customFontColor = customFontColor,
                customBgColor = customBgColor,
                customBubbleColor = customBubbleColor,
                customTextColor = customTextColor,
                customFontPath = customFontPath,
                onCustomFontColorChange = onCustomFontColorChange,
                onCustomBgColorChange = onCustomBgColorChange,
                onCustomBubbleColorChange = onCustomBubbleColorChange,
                onCustomTextColorChange = onCustomTextColorChange,
                onCustomFontChange = onCustomFontChange,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.TOOL_PERMISSIONS -> com.zhibai.app.ui.screens.ToolPermissionScreen(
                agentEngine = agentEngine,
                onBack = { currentPage = SettingsPage.AGENT; pageTitle = "AI Agent" }
            )
            SettingsPage.INPUT_STYLE -> InputStylePage(
                inputStyle = inputStyle,
                inputBlurColor = inputBlurColor,
                inputShadowEnabled = inputShadowEnabled,
                onInputStyleChange = onInputStyleChange,
                onInputBlurColorChange = onInputBlurColorChange,
                onInputShadowChange = onInputShadowChange,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.PREFERENCES -> PreferencesPage(
                prefDefaultModel = prefDefaultModel,
                prefStartupMode = prefStartupMode,
                prefStartupSession = prefStartupSession,
                sessions = prefSessions,
                onPrefDefaultModelChange = onPrefDefaultModelChange,
                onPrefStartupModeChange = onPrefStartupModeChange,
                onPrefStartupSessionChange = onPrefStartupSessionChange,
                prefAgentThinkLevel = prefAgentThinkLevel,
                onPrefAgentThinkLevelChange = onPrefAgentThinkLevelChange,
                prefAutoCompress = prefAutoCompress,
                prefAutoCompressThreshold = prefAutoCompressThreshold,
                onPrefAutoCompressChange = onPrefAutoCompressChange,
                onPrefAutoCompressThresholdChange = onPrefAutoCompressThresholdChange,
                prefAutoSaveImages = prefAutoSaveImages,
                onPrefAutoSaveImagesChange = onPrefAutoSaveImagesChange,
                prefLongTermMemory = prefLongTermMemory,
                prefMemoryAutoWrite = prefMemoryAutoWrite,
                onPrefLongTermMemoryChange = onPrefLongTermMemoryChange,
                onPrefMemoryAutoWriteChange = onPrefMemoryAutoWriteChange,
                prefModelHabit = prefModelHabit,
                onPrefModelHabitChange = onPrefModelHabitChange,
                prefScenario = prefScenario,
                onPrefScenarioChange = onPrefScenarioChange,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.WIRELESS_ADB -> WirelessAdbPage(
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
private fun SettingsMainMenu(
    onNavigate: (SettingsPage, String) -> Unit,
    prefIslandExpandOnPhase: Boolean = false,
    onPrefIslandExpandOnPhaseChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // API Key
        ListItem(
            headlineContent = { Text("API Key") },
            supportingContent = { Text("配置 MiMo API 访问") },
            leadingContent = { Icon(Icons.Default.Key, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.API_KEY, "API Key") }
        )
        HorizontalDivider()

        // Default TTS Voice
        ListItem(
            headlineContent = { Text("默认朗读语音") },
            supportingContent = { Text("聊天中长按朗读时使用的语音") },
            leadingContent = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.VOICE, "Read Aloud Voice") }
        )
        HorizontalDivider()

        // Font Size
        ListItem(
            headlineContent = { Text("字体大小") },
            supportingContent = { Text("调整聊天文字大小") },
            leadingContent = { Icon(Icons.Default.FormatSize, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.FONT_SIZE, "字体大小") }
        )
        HorizontalDivider()

        // Input bar appearance
        ListItem(
            headlineContent = { Text("输入框外观") },
            supportingContent = { Text("磨砂玻璃 / 经典样式、阴影、磨砂颜色") },
            leadingContent = { Icon(Icons.Default.Layers, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.INPUT_STYLE, "输入框外观") }
        )
        HorizontalDivider()

        // Preferences (偏好)
        ListItem(
            headlineContent = { Text("偏好") },
            supportingContent = { Text("启动行为、思考等级、自动压缩、长期记忆、模型习惯") },
            leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.PREFERENCES, "偏好") }
        )
        HorizontalDivider()

        // AI Agent
        ListItem(
            headlineContent = { Text("AI Agent") },
            supportingContent = { Text("权限模式、工作区与工具") },
            leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.AGENT, "AI Agent") }
        )
        HorizontalDivider()

        // Super Island hub
        ListItem(
            headlineContent = { Text("超级岛设置") },
            supportingContent = { Text("开关、流式输出、阶段展开与必要权限") },
            leadingContent = { Icon(Icons.Default.Crop, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.ISLAND, "超级岛设置") }
        )
        HorizontalDivider()

        // AutoGLM phone agent
        if (com.zhibai.app.agent.AgentEngine.PHONE_AGENT_ENABLED) {
            ListItem(
                headlineContent = { Text("AutoGLM 手机助手") },
                supportingContent = { Text("智谱 Key、步数、无线 ADB") },
                leadingContent = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { onNavigate(SettingsPage.AUTOGLM, "AutoGLM 手机助手") }
            )
            HorizontalDivider()
        }

        // About
        ListItem(
            headlineContent = { Text("关于") },
            supportingContent = { Text("应用信息与版本") },
            leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.ABOUT, "关于") }
        )
        HorizontalDivider()
    }
}

@Composable
private fun IslandSettingsPage(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    showStreaming: Boolean,
    onShowStreamingChange: (Boolean) -> Unit,
    expandOnPhase: Boolean,
    onExpandOnPhaseChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var perms by remember { mutableStateOf(com.zhibai.app.data.api.IslandNotificationService.collectPermissionStatus(context)) }
    val allGranted = perms.all { it.granted }

    // Re-check whenever the user comes back from system settings.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                perms = com.zhibai.app.data.api.IslandNotificationService.collectPermissionStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ListItem(
            headlineContent = { Text("开启超级岛") },
            supportingContent = {
                Text(
                    when {
                        !enabled -> "已关闭，Agent 状态不会上岛"
                        !allGranted -> "权限未齐，补齐下方清单后才会正式上岛"
                        else -> "已开启——满足权限时 Agent 状态会显示在超级岛"
                    }
                )
            },
            leadingContent = { Icon(Icons.Default.Crop, contentDescription = null) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = onEnabledChange) }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("显示流式输出过程") },
            supportingContent = { Text("生成时把最新输出片段滚到岛胶囊上（约 4 次/秒）") },
            leadingContent = { Icon(Icons.Default.Title, contentDescription = null) },
            trailingContent = { Switch(checked = showStreaming, onCheckedChange = onShowStreamingChange) }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("阶段切换展开岛") },
            supportingContent = {
                Text(if (expandOnPhase) "进入下一阶段时重新展开胶囊" else "阶段切换只原地更新，不重新展开")
            },
            leadingContent = { Icon(Icons.Default.UnfoldMore, contentDescription = null) },
            trailingContent = { Switch(checked = expandOnPhase, onCheckedChange = onExpandOnPhaseChange) }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = {
                Text(
                    if (allGranted) "必要权限 · 全部已开启" else "必要权限 · ${perms.count { it.granted }}/${perms.size}",
                    fontWeight = FontWeight.SemiBold
                )
            },
            supportingContent = { Text("四项全部开启后，才会正式开始上岛") },
            leadingContent = {
                Icon(
                    if (allGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (allGranted) Color(0xFF2EAB6C) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        perms.forEach { p ->
            ListItem(
                headlineContent = { Text(p.title) },
                supportingContent = { Text(p.detail) },
                leadingContent = {
                    Icon(
                        if (p.granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (p.granted) Color(0xFF2EAB6C) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.clickable {
                    when (p.id) {
                        "battery" -> com.zhibai.app.data.api.IslandNotificationService.openBatterySettings(context)
                        else -> com.zhibai.app.data.api.IslandNotificationService.openPromotionSettings(context)
                    }
                    perms = com.zhibai.app.data.api.IslandNotificationService.collectPermissionStatus(context)
                }
            )
        }

        HorizontalDivider()
        ListItem(
            headlineContent = { Text("从系统设置返回后会自动复查权限") },
            supportingContent = { Text("全部变绿即可开始上岛，无需重启 App") },
            leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) }
        )
    }
}

@Composable
private fun ApiKeyPage(
    apiKey: String,
    onSave: (String) -> Unit,
    localAiSection: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var apiKeyInput by rememberSaveable { mutableStateOf(apiKey) }

    LaunchedEffect(apiKey) { apiKeyInput = apiKey }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("MiMo API Key") },
            placeholder = { Text("sk-xxxxx") },
            singleLine = true,
            visualTransformation = if (showApiKey) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showApiKey = !showApiKey }) {
                    Icon(
                        if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle visibility"
                    )
                }
            },
            shape = RoundedCornerShape(12.dp)
        )

        Button(
            onClick = { onSave(apiKeyInput.trim()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = apiKeyInput.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("保存")
        }

        localAiSection()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("如何获取 API Key", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "1. 访问 platform.xiaomimimo.com\n2. 注册账号或登录\n3. 进入 API Keys 区域\n4. 创建新的 API Key\n5. 复制并粘贴到这里",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VoicePage(
    currentVoice: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val voices = listOf(
        "冰糖" to "Default female voice",
        "茉莉" to "Female voice",
        "苏打" to "Male voice",
        "白桦" to "Male voice",
        "Mia" to "English female",
        "Chloe" to "English female",
        "Milo" to "English male",
        "Dean" to "English male"
    )

    Column(modifier = modifier.fillMaxSize()) {
        ListItem(
            headlineContent = { Text("当前语音") },
            supportingContent = { Text(currentVoice) },
            leadingContent = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) }
        )
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        voices.forEach { (id, desc) ->
            ListItem(
                headlineContent = { Text(id) },
                supportingContent = { Text(desc, fontSize = 12.sp) },
                trailingContent = {
                    if (currentVoice == id) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MiMoColors.Primary)
                    }
                },
                modifier = Modifier.clickable { onSelect(id) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FontSizePage(
    currentSize: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderValue by remember { mutableFloatStateOf(currentSize.toFloat()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("预览", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "The quick brown fox jumps over the lazy dog. 敏捷的棕色狐狸跳过了懒狗。\nThe spring breeze brushes the willows as birds sing and call,\nUpon the rippling azure waves, a lone boat drifts along.\n春风拂柳鸟啼鸣，碧波荡漾孤舟行。",
                fontSize = sliderValue.toInt().sp,
                lineHeight = (sliderValue.toInt() + 7).sp,
                modifier = Modifier.padding(16.dp)
            )
        }

        Text("大小：${sliderValue.toInt()}sp", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

        // Slider with a dot above the midpoint (15 = default)
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "15",
                    fontSize = 10.sp,
                    color = if (sliderValue.toInt() == 15) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSelect(sliderValue.toInt()) },
                valueRange = 10f..20f,
                steps = 9,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(top = 36.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("小", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("标准 (15)", fontSize = 12.sp, color = if (sliderValue.toInt() == 15) MiMoColors.Primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (sliderValue.toInt() == 15) FontWeight.Bold else FontWeight.Normal)
            Text("大", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Reset to default
        if (sliderValue.toInt() != 15) {
            TextButton(
                onClick = { sliderValue = 15f; onSelect(15) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("恢复默认 (15)")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutPage(
    onNavigateToForbidden: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = com.zhibai.app.R.drawable.mimo_logo_foreground),
            contentDescription = null,
            modifier = Modifier.size(160.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("知白·雪", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        // Long-press version number → hidden settings
        Text(
            "debug2.6.999",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = { onNavigateToForbidden() }
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "由 Xiaomi MiMo API 提供支持",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            "MiMo AI 非官方客户端",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun AgentSettingsPage(
    agentEnabled: Boolean,
    agentPermissionMode: Int,
    agentApiKey: String,
    agentBaseUrl: String,
    workspacePath: String = "",
    onAgentEnabledChange: (Boolean) -> Unit,
    onAgentPermissionModeChange: (Int) -> Unit,
    onAgentApiKeyChange: (String) -> Unit,
    onAgentBaseUrlChange: (String) -> Unit,
    onWorkspacePathChange: (String) -> Unit = {},
    onNavigateToToolPermissions: () -> Unit = {},
    onNavigateToWirelessAdb: () -> Unit = {},
    localAiSection: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var agentKeyInput by remember(agentApiKey) { mutableStateOf(agentApiKey) }
    var agentUrlInput by remember(agentBaseUrl) { mutableStateOf(agentBaseUrl) }
    var workspaceInput by remember(workspacePath) { mutableStateOf(workspacePath) }
    var showAgentKey by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ListItem(
            headlineContent = { Text("AI Agent") },
            supportingContent = { Text(if (agentEnabled) "已开启 — 模型可使用本地工具" else "已关闭") },
            leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
            trailingContent = {
                Switch(checked = agentEnabled, onCheckedChange = onAgentEnabledChange)
            }
        )
        HorizontalDivider()

        if (agentEnabled) {
            ListItem(
                headlineContent = { Text("权限模式") },
                supportingContent = { Text("工具调用的批准方式") },
                leadingContent = { Icon(Icons.Default.Security, contentDescription = null) }
            )
            val permissionOptions = listOf(
                com.zhibai.app.agent.AgentEngine.MODE_ASK_ALL to
                    "每次询问 — 每个工具调用都需手动批准" to "最安全",
                com.zhibai.app.agent.AgentEngine.MODE_DEFAULT to
                    "默认 — 低风险直接运行，高风险询问" to "均衡",
                com.zhibai.app.agent.AgentEngine.MODE_AUTO to
                    "自动 — 全部直接运行" to "最快",
                com.zhibai.app.agent.AgentEngine.MODE_AI_APPROVE to
                    "AI 审批 — AI 决定，被拒时询问你" to "智能"
            )
            permissionOptions.forEach { (modePair, badge) ->
                val (mode, label) = modePair
                val selected = agentPermissionMode == mode
                ListItem(
                    headlineContent = {
                        Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    },
                    supportingContent = { Text(badge, fontSize = 11.sp) },
                    leadingContent = {
                        RadioButton(selected = selected, onClick = { onAgentPermissionModeChange(mode) })
                    },
                    modifier = Modifier.clickable { onAgentPermissionModeChange(mode) }
                )
            }
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("工具权限") },
                supportingContent = { Text("启用/禁用单个工具") },
                leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToToolPermissions() }
            )
            HorizontalDivider()

            // Wireless debugging ADB (配对后 run_adb 获得真实 shell 权限)
            ListItem(
                headlineContent = { Text("无线调试 ADB") },
                supportingContent = { Text("配对后 run_adb 获得真实 shell 权限") },
                leadingContent = { Icon(Icons.Default.Cable, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToWirelessAdb() }
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Agent API Key") },
                supportingContent = { Text(if (agentApiKey.isBlank()) "留空则使用主密钥" else "已配置独立密钥") },
                leadingContent = { Icon(Icons.Default.Key, contentDescription = null) }
            )
            OutlinedTextField(
                value = agentKeyInput,
                onValueChange = { agentKeyInput = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("tp-xxxxx（留空则使用主密钥）") },
                singleLine = true,
                visualTransformation = if (showAgentKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showAgentKey = !showAgentKey }) {
                        Icon(
                            if (showAgentKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            ListItem(
                headlineContent = { Text("Agent Base URL") },
                supportingContent = { Text(agentBaseUrl) },
                leadingContent = { Icon(Icons.Default.Language, contentDescription = null) }
            )
            OutlinedTextField(
                value = agentUrlInput,
                onValueChange = { agentUrlInput = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("https://token-plan-cn.xiaomimimo.com/v1") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "小米TokenPlanAPI" to "https://token-plan-cn.xiaomimimo.com/v1",
                    "MiMoAPI" to "https://api.xiaomimimo.com/v1"
                ).forEach { (label, preset) ->
                    val selected = agentUrlInput.trim() == preset
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { agentUrlInput = preset }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (selected) MaterialTheme.colorScheme.background
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            localAiSection()

            ListItem(
                headlineContent = { Text("工作区路径") },
                supportingContent = {
                    Text(if (workspacePath.isBlank()) "默认：/sdcard/知白/" else workspacePath)
                },
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) }
            )
            OutlinedTextField(
                value = workspaceInput,
                onValueChange = { workspaceInput = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("/sdcard/知白/ 或相对路径如 知白") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    onAgentApiKeyChange(agentKeyInput.trim())
                    onAgentBaseUrlChange(agentUrlInput.trim().ifBlank { "https://token-plan-cn.xiaomimimo.com/v1" })
                    onWorkspacePathChange(workspaceInput.trim())
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("保存 Agent 配置")
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ForbiddenPage(
    forceNewSession: Boolean,
    strictFaithful: Boolean = true,
    promptAmbiguousClarify: Boolean = true,
    promptSensitiveRefusal: Boolean = true,
    unlimitedAttachments: Boolean = false,
    onForceNewSessionChange: (Boolean) -> Unit,
    onStrictFaithfulChange: (Boolean) -> Unit = {},
    onPromptAmbiguousClarifyChange: (Boolean) -> Unit = {},
    onPromptSensitiveRefusalChange: (Boolean) -> Unit = {},
    onUnlimitedAttachmentsChange: (Boolean) -> Unit = {},
    onResetOnboarding: () -> Unit = {},
    onShowUpdatePreview: () -> Unit = {},
    onClearAllData: () -> Unit,
    onCustomPromptChange: (String) -> Unit,
    customFontColor: String = "",
    customBgColor: String = "",
    customBubbleColor: String = "",
    customTextColor: String = "",
    customFontPath: String = "",
    onCustomFontColorChange: (String) -> Unit = {},
    onCustomBgColorChange: (String) -> Unit = {},
    onCustomBubbleColorChange: (String) -> Unit = {},
    onCustomTextColorChange: (String) -> Unit = {},
    onCustomFontChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showLogExported by remember { mutableStateOf(false) }
    var logExportPath by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmLevel1 by remember { mutableStateOf(false) }
    var confirmLevel2 by remember { mutableStateOf(false) }
    var confirmLevel3 by remember { mutableStateOf(false) }
    var customPrompt by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ListItem(
            headlineContent = { Text("切换模型时强制新建会话") },
            supportingContent = { Text(if (forceNewSession) "开 — 切换模型时新建对话" else "关 — 保留当前对话") },
            leadingContent = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
            trailingContent = {
                Switch(checked = forceNewSession, onCheckedChange = onForceNewSessionChange)
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("严格忠实模式") },
            supportingContent = {
                Text(if (strictFaithful) "开 — 严格参考内容信息，不虚构" else "关 — 允许模型自由发挥")
            },
            leadingContent = { Icon(Icons.Default.Gavel, contentDescription = null) },
            trailingContent = {
                Switch(checked = strictFaithful, onCheckedChange = onStrictFaithfulChange)
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("模糊问题先答后问") },
            supportingContent = {
                Text(if (promptAmbiguousClarify) "开 — 先尽量回答，再最多追问一句" else "关 — 不注入该提示词")
            },
            leadingContent = { Icon(Icons.Default.HelpOutline, contentDescription = null) },
            trailingContent = {
                Switch(checked = promptAmbiguousClarify, onCheckedChange = onPromptAmbiguousClarifyChange)
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("敏感政治/历史限制") },
            supportingContent = {
                Text(if (promptSensitiveRefusal) "开 — 拒答外国敏感政治新闻等" else "关 — 不注入该提示词")
            },
            leadingContent = { Icon(Icons.Default.Public, contentDescription = null) },
            trailingContent = {
                Switch(checked = promptSensitiveRefusal, onCheckedChange = onPromptSensitiveRefusalChange)
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("无限制附件") },
            supportingContent = {
                Text(if (unlimitedAttachments) "开 — 解除附件数量/大小限制" else "关 — 视频1份20MB · 音频1份10MB · 图片6张（有音视频时2张）")
            },
            leadingContent = { Icon(Icons.Default.Attachment, contentDescription = null) },
            trailingContent = {
                Switch(checked = unlimitedAttachments, onCheckedChange = onUnlimitedAttachmentsChange)
            }
        )
        HorizontalDivider()

        // === Custom Theme Colors Section ===
        ListItem(
            headlineContent = { Text("主题颜色自定义", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text("自定义字体/背景/气泡/文字颜色，留空恢复默认") },
            leadingContent = { Icon(Icons.Default.Palette, contentDescription = null) }
        )

        // Font color
        ColorPickerRow(
            label = "字体颜色",
            currentHex = customFontColor,
            presets = listOf("", "#000000", "#FFFFFF", "#333333", "#666666", "#1A1A1A", "#E0E0E0", "#8B5CF6", "#3B82F6", "#10B981"),
            onColorChange = onCustomFontColorChange
        )
        // Background color
        ColorPickerRow(
            label = "背景颜色",
            currentHex = customBgColor,
            presets = listOf("", "#F5F5F5", "#FFFFFF", "#0A0A0A", "#121212", "#FAFAFA", "#141414", "#F0F0F0", "#0E0E0E"),
            onColorChange = onCustomBgColorChange
        )
        // Bubble color
        ColorPickerRow(
            label = "气泡颜色",
            currentHex = customBubbleColor,
            presets = listOf("", "#000000", "#FFFFFF", "#1E1E1E", "#2A2A2A", "#F0F0F0", "#1A1A1A", "#333333"),
            onColorChange = onCustomBubbleColorChange
        )
        // Text color
        ColorPickerRow(
            label = "文字颜色",
            currentHex = customTextColor,
            presets = listOf("", "#1A1A1A", "#E0E0E0", "#333333", "#CCCCCC", "#666666", "#999999", "#000000", "#FFFFFF"),
            onColorChange = onCustomTextColorChange
        )
        HorizontalDivider()

        // === Custom Font Section ===
        ListItem(
            headlineContent = { Text("自定义字体", fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                val fontName = com.zhibai.app.ui.theme.SystemFonts.listFonts()
                    .firstOrNull { it.path == customFontPath }?.name ?: "系统默认"
                Text(fontName)
            },
            leadingContent = { Icon(Icons.Default.FontDownload, contentDescription = null) }
        )
        FontPickerRow(
            currentPath = customFontPath,
            onFontChange = onCustomFontChange
        )
        HorizontalDivider()

        // Reset onboarding
        var confirmResetOnboarding by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text("重走初始引导") },
            supportingContent = { Text("清除引导页完成标记，下次启动重新走一遍") },
            leadingContent = { Icon(Icons.Default.Replay, contentDescription = null) },
            modifier = Modifier.clickable { confirmResetOnboarding = true }
        )
        HorizontalDivider()

        if (confirmResetOnboarding) {
            AlertDialog(
                onDismissRequest = { confirmResetOnboarding = false },
                title = { Text("重走引导页？") },
                text = { Text("将清除引导完成标记，下次打开 App 会重新显示引导页。其他设置不受影响。") },
                confirmButton = {
                    TextButton(onClick = {
                        onResetOnboarding()
                        confirmResetOnboarding = false
                    }) { Text("确认") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmResetOnboarding = false }) { Text("取消") }
                }
            )
        }

        // View update preview
        ListItem(
            headlineContent = { Text("查看更新内容") },
            supportingContent = { Text("重看 v2.6 新功能介绍，不改任何设置") },
            leadingContent = { Icon(Icons.Default.NewReleases, contentDescription = null) },
            modifier = Modifier.clickable { onShowUpdatePreview() }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("导出日志") },
            supportingContent = { Text("导出到 Download 目录，文件管理器可直接查看") },
            leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
            modifier = Modifier.clickable {
                try {
                    val mem = com.zhibai.app.util.AppLog.dump()
                    val logcat = try {
                        Runtime.getRuntime().exec("logcat -d -s MiMoApp")
                            .inputStream.bufferedReader().readText()
                    } catch (_: Exception) { "" }
                    val content = buildString {
                        appendLine("=== MiMo AppLog (${mem.lines().size} lines) ===")
                        appendLine(mem)
                        appendLine()
                        appendLine("=== logcat -s MiMoApp ===")
                        appendLine(logcat.ifBlank { "(empty)" })
                    }
                    val fileName = "mimo_log_${System.currentTimeMillis()}.txt"
                    // Save to public Download directory via MediaStore (Android 10+)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download")
                        }
                        val uri = context.contentResolver.insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        )
                        uri?.let {
                            context.contentResolver.openOutputStream(it)?.use { os ->
                                os.write(content.toByteArray())
                            }
                        }
                        logExportPath = "Download/$fileName"
                    } else {
                        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val file = java.io.File(dir, fileName)
                        file.writeText(content)
                        logExportPath = file.absolutePath
                    }
                } catch (e: Exception) {
                    logExportPath = "导出失败: ${e.message}"
                }
                showLogExported = true
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("清除日志缓冲") },
            supportingContent = { Text("清空内存里的 ${com.zhibai.app.util.AppLog.size()} 条诊断日志，不影响导出历史") },
            leadingContent = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            modifier = Modifier.clickable {
                com.zhibai.app.util.AppLog.clear()
                com.zhibai.app.util.AppLog.d("log buffer cleared by user")
                logExportPath = "日志缓冲已清空"
                showLogExported = true
            }
        )
        HorizontalDivider()

        // === Manual crash test (3-step confirmation) ===
        var crashTestStep by remember { mutableIntStateOf(0) } // 0=off, 1=confirm1, 2=confirm2, 3=mode select
        ListItem(
            headlineContent = { Text("手动崩溃测试", color = MiMoColors.Error) },
            supportingContent = { Text("触发崩溃验证日志弹窗，需 3 次确认") },
            leadingContent = { Icon(Icons.Default.Psychology, contentDescription = null, tint = MiMoColors.Error) },
            modifier = Modifier.clickable { if (crashTestStep == 0) crashTestStep = 1 }
        )
        HorizontalDivider()

        if (crashTestStep in 1..2) {
            AlertDialog(
                onDismissRequest = { crashTestStep = 0 },
                title = { Text("确认手动崩溃") },
                text = {
                    Text(
                        if (crashTestStep == 1)
                            "第 1/3 次确认：这会故意让 App 崩溃，用于测试崩溃日志弹窗。\n\n确定要继续吗？"
                        else
                            "第 2/3 次确认：崩溃后下次启动会弹出崩溃日志窗口。\n\n真的确定吗？"
                    )
                },
                confirmButton = {
                    TextButton(onClick = { crashTestStep++ }) { Text("继续") }
                },
                dismissButton = {
                    TextButton(onClick = { crashTestStep = 0 }) { Text("取消") }
                }
            )
        }

        if (crashTestStep == 3) {
            AlertDialog(
                onDismissRequest = { crashTestStep = 0 },
                title = { Text("选择崩溃模式") },
                text = {
                    Column {
                        Text("第 3/3 次确认：选择测试模式", fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("单次崩溃：验证崩溃日志弹窗能否正常弹出 + UI 设计检查", fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("连续崩溃两次：第一次崩溃后不关闭弹窗，再触发一次崩溃，验证日志是否被保护（不丢失）", fontSize = 13.sp)
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            crashTestStep = 0
                            // Single crash
                            throw RuntimeException("手动崩溃测试（单次）- UI 检查")
                        }) { Text("单次崩溃", color = MiMoColors.Error) }
                        TextButton(onClick = {
                            crashTestStep = 0
                            // Double crash: write a fake crash log first, then crash
                            try {
                                val file = java.io.File(context.filesDir, "last_crash.log")
                                file.writeText("=== CRASH REPORT ===\nTime: ${java.time.LocalDateTime.now()}\nThread: main\n手动崩溃测试（连续第1次）\n目的：验证日志保护\n")
                            } catch (_: Exception) {}
                            throw RuntimeException("手动崩溃测试（连续第1次）- 日志保护验证")
                        }) { Text("连续两次", color = MiMoColors.Error) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { crashTestStep = 0 }) { Text("取消") }
                }
            )
        }

        ListItem(
            headlineContent = { Text("清除所有数据") },
            supportingContent = { Text("删除所有会话和设置") },
            leadingContent = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MiMoColors.Error) },
            modifier = Modifier.clickable { confirmClear = true }
        )
        HorizontalDivider()

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("清除所有数据？") },
                text = { Text("这将删除所有会话、设置和 API Key，且无法撤销。") },
                confirmButton = {
                    TextButton(onClick = {
                        onClearAllData()
                        confirmClear = false
                    }) {
                        Text("全部删除", color = MiMoColors.Error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("取消") }
                }
            )
        }

        if (showLogExported) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "日志已导出到: $logExportPath",
                fontSize = 13.sp,
                color = if (logExportPath.startsWith("导出失败")) MiMoColors.Error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        ListItem(
            headlineContent = { Text("隐藏功能", color = MiMoColors.Error) },
            supportingContent = { Text("追加自定义系统提示词（需 3 次确认）") },
            leadingContent = { Icon(Icons.Default.Warning, contentDescription = null, tint = MiMoColors.Error) },
            modifier = Modifier.clickable { confirmLevel1 = true }
        )
        HorizontalDivider()

        if (confirmLevel1) {
            AlertDialog(
                onDismissRequest = { confirmLevel1 = false },
                title = { Text("警告级别 1/3") },
                text = { Text("你即将访问一个危险功能。添加自定义系统提示词可能导致异常行为。继续吗？") },
                confirmButton = { TextButton(onClick = { confirmLevel1 = false; confirmLevel2 = true }) { Text("我已了解") } },
                dismissButton = { TextButton(onClick = { confirmLevel1 = false }) { Text("取消") } }
            )
        }
        if (confirmLevel2) {
            AlertDialog(
                onDismissRequest = { confirmLevel2 = false },
                title = { Text("警告级别 2/3") },
                text = { Text("自定义提示词会追加到现有系统提示词之后。你无法查看或修改原始提示词。继续吗？") },
                confirmButton = { TextButton(onClick = { confirmLevel2 = false; confirmLevel3 = true }) { Text("我接受") } },
                dismissButton = { TextButton(onClick = { confirmLevel2 = false }) { Text("取消") } }
            )
        }
        if (confirmLevel3) {
            AlertDialog(
                onDismissRequest = { confirmLevel3 = false },
                title = { Text("最终确认") },
                text = {
                    Column {
                        Text("输入附加系统提示词内容：")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customPrompt,
                            onValueChange = { customPrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("例如：始终使用中文回复……") },
                            minLines = 3,
                            maxLines = 5
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (customPrompt.isNotBlank()) {
                            onCustomPromptChange(customPrompt)
                            confirmLevel3 = false
                        }
                    }) { Text("应用") }
                },
                dismissButton = { TextButton(onClick = { confirmLevel3 = false }) { Text("取消") } }
            )
        }
    }
}

// ── Input bar appearance page ───────────────────────────────────────

@Composable
private fun InputStylePage(
    inputStyle: String,
    inputBlurColor: String,
    inputShadowEnabled: Boolean,
    onInputStyleChange: (String) -> Unit,
    onInputBlurColorChange: (String) -> Unit,
    onInputShadowChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Style toggle
        ListItem(
            headlineContent = { Text("磨砂玻璃输入框") },
            supportingContent = { Text("悬浮半透明，输入时变不透明，可透视下方消息") },
            trailingContent = {
                Switch(checked = inputStyle == "glass", onCheckedChange = { onInputStyleChange(if (it) "glass" else "classic") })
            }
        )
        HorizontalDivider()

        if (inputStyle == "glass") {
            // Shadow
            ListItem(
                headlineContent = { Text("悬浮阴影") },
                supportingContent = { Text("输入框下方投射柔和阴影") },
                trailingContent = {
                    Switch(checked = inputShadowEnabled, onCheckedChange = onInputShadowChange)
                }
            )
            HorizontalDivider()

            // Blur color presets
            ListItem(headlineContent = { Text("磨砂颜色") })
            val presets = listOf(
                "auto" to "自动深浅",
                "#B0FFFFFF" to "经典白",
                "#80FFFFFF" to "轻透白",
                "#B01E1E1E" to "深色",
                "#801E1E1E" to "轻透黑",
                "#90F5F0E8" to "暖纸",
                "#90E8EDF5" to "冷雾"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (hex, name) ->
                    val selected = inputBlurColor.equals(hex, ignoreCase = true)
                    Surface(
                        onClick = { onInputBlurColorChange(hex) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hex == "auto") {
                                // Split light/dark swatch
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFB0FFFFFF))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .fillMaxWidth(0.5f)
                                            .fillMaxHeight(0.5f)
                                            .background(Color(0xFFB01E1E1E))
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(com.zhibai.app.ui.components.parseHexColor(hex) ?: Color.Gray)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(name, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Custom hex input
            var customHex by remember(inputBlurColor) { mutableStateOf(inputBlurColor) }
            OutlinedTextField(
                value = customHex,
                onValueChange = { customHex = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("自定义颜色 (AARRGGBB)") },
                placeholder = { Text("#B0FFFFFF") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            TextButton(
                onClick = { onInputBlurColorChange(customHex.trim()) },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text("应用自定义颜色") }
        }
    }
}

@Composable
private fun ColorPickerRow(
    label: String,
    currentHex: String,
    presets: List<String>,
    onColorChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var customInput by remember(currentHex) { mutableStateOf(currentHex) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            // Current color preview dot
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (currentHex.isBlank()) Color.Unspecified
                        else try { Color(android.graphics.Color.parseColor(currentHex)) } catch (_: Exception) { Color.Unspecified }
                    )
                    .then(
                        if (currentHex.isBlank()) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        else Modifier
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "选择", fontSize = 12.sp)
            }
        }

        if (expanded) {
            // Preset color swatches
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { hex ->
                    val c = if (hex.isBlank()) Color.Unspecified
                    else try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.Unspecified }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(c)
                            .then(
                                if (hex.isBlank()) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                else if (hex == currentHex) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                else Modifier.border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            )
                            .clickable { onColorChange(hex) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (hex.isBlank()) {
                            Text("默", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // Custom hex input
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("自定义 #RRGGBB", fontSize = 11.sp) },
                    placeholder = { Text("#1A2B3C", fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                TextButton(onClick = {
                    val hex = customInput.trim().let { if (it.startsWith("#")) it else "#$it" }
                    if (hex.matches(Regex("^#[0-9A-Fa-f]{6}$"))) onColorChange(hex)
                }) { Text("应用", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun FontPickerRow(
    currentPath: String,
    onFontChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val fonts = remember { com.zhibai.app.ui.theme.SystemFonts.listFonts() }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起字体列表" else "选择预设字体 (${fonts.size} 个)", fontSize = 12.sp)
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                fonts.forEach { font ->
                    FontItem(
                        name = font.name,
                        isSelected = currentPath == font.path || (font.path == "default" && currentPath.isBlank()),
                        onClick = { onFontChange(if (font.path == "default") "" else font.path) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FontItem(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Spacer(modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(name, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

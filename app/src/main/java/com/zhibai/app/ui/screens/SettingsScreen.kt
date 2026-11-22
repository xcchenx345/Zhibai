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
import com.zhibai.app.i18n.LangManager

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
    promptXiaomiGuard: Boolean = true,
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
    onApiKeyChange: (String) -> Boolean,
    mmWrapped: Boolean = false,
    onApiKeyCopy: () -> Unit = {},
    onApiKeyClear: () -> Unit = {},
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
    onPromptXiaomiGuardChange: (Boolean) -> Unit = {},
    onUnlimitedAttachmentsChange: (Boolean) -> Unit = {},
    onClearAllData: () -> Unit,
    onCustomPromptChange: (String) -> Unit,
    onResetOnboarding: () -> Unit = {},
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
                onNavigateToWirelessAdb = { currentPage = SettingsPage.WIRELESS_ADB; pageTitle = LangManager.t("settings.adb.title", "无线调试 ADB") },
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.API_KEY -> ApiKeyPage(
                apiKey = apiKey,
                mmWrapped = mmWrapped,
                onSave = onApiKeyChange,
                onCopy = onApiKeyCopy,
                onClear = onApiKeyClear,
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
                onNavigateToToolPermissions = { currentPage = SettingsPage.TOOL_PERMISSIONS; pageTitle = LangManager.t("settings.toolPerms.title", "工具权限") },
                onNavigateToWirelessAdb = { currentPage = SettingsPage.WIRELESS_ADB; pageTitle = LangManager.t("settings.adb.title", "无线调试 ADB") },
                localAiSection = localAiSection,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.ABOUT -> AboutPage(
                onNavigateToForbidden = { currentPage = SettingsPage.FORBIDDEN; pageTitle = LangManager.t("settings.forbidden.navTitle", "隐藏设置") },
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.FORBIDDEN -> ForbiddenPage(
                forceNewSession = forceNewSessionOnModelSwitch,
                strictFaithful = strictFaithful,
                promptAmbiguousClarify = promptAmbiguousClarify,
                promptSensitiveRefusal = promptSensitiveRefusal,
                promptXiaomiGuard = promptXiaomiGuard,
                unlimitedAttachments = unlimitedAttachments,
                onForceNewSessionChange = onForceNewSessionChange,
                onStrictFaithfulChange = onStrictFaithfulChange,
                onPromptAmbiguousClarifyChange = onPromptAmbiguousClarifyChange,
                onPromptSensitiveRefusalChange = onPromptSensitiveRefusalChange,
                onPromptXiaomiGuardChange = onPromptXiaomiGuardChange,
                onUnlimitedAttachmentsChange = onUnlimitedAttachmentsChange,
                onResetOnboarding = onResetOnboarding,
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
    val context = LocalContext.current
    var showLangDialog by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        // API Key
        ListItem(
            headlineContent = { Text("API Key") },
            supportingContent = { Text(LangManager.t("settings.apikey.desc", "配置 MiMo API 访问")) },
            leadingContent = { Icon(Icons.Default.Key, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.API_KEY, "API Key") }
        )
        HorizontalDivider()

        // Language
        ListItem(
            headlineContent = { Text(LangManager.t("settings.lang.title", "语言")) },
            supportingContent = { Text(com.zhibai.app.i18n.LangManager.languageName(com.zhibai.app.i18n.LangManager.currentCode)) },
            leadingContent = { Icon(Icons.Default.Language, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { showLangDialog = true }
        )
        HorizontalDivider()

        // Default TTS Voice
        ListItem(
            headlineContent = { Text(LangManager.t("settings.voice.title", "默认朗读语音")) },
            supportingContent = { Text(LangManager.t("settings.voice.desc", "聊天中长按朗读时使用的语音")) },
            leadingContent = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.VOICE, "Read Aloud Voice") }
        )
        HorizontalDivider()

        // Font Size
        ListItem(
            headlineContent = { Text(LangManager.t("settings.fontsize.title", "字体大小")) },
            supportingContent = { Text(LangManager.t("settings.fontsize.desc", "调整聊天文字大小")) },
            leadingContent = { Icon(Icons.Default.FormatSize, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.FONT_SIZE, LangManager.t("settings.fontsize.title", "字体大小")) }
        )
        HorizontalDivider()

        // Input bar appearance
        ListItem(
            headlineContent = { Text(LangManager.t("settings.inputStyle.title", "输入框外观")) },
            supportingContent = { Text(LangManager.t("settings.inputStyle.desc", "磨砂玻璃 / 经典样式、阴影、磨砂颜色")) },
            leadingContent = { Icon(Icons.Default.Layers, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.INPUT_STYLE, LangManager.t("settings.inputStyle.title", "输入框外观")) }
        )
        HorizontalDivider()

        // Preferences (偏好)
        ListItem(
            headlineContent = { Text(LangManager.t("settings.prefs.title", "偏好")) },
            supportingContent = { Text(LangManager.t("settings.prefs.desc", "启动行为、思考等级、自动压缩、长期记忆、模型习惯")) },
            leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.PREFERENCES, LangManager.t("settings.prefs.title", "偏好")) }
        )
        HorizontalDivider()

        // AI Agent
        ListItem(
            headlineContent = { Text("AI Agent") },
            supportingContent = { Text(LangManager.t("settings.agent.desc", "权限模式、工作区与工具")) },
            leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.AGENT, "AI Agent") }
        )
        HorizontalDivider()

        // Super Island hub
        ListItem(
            headlineContent = { Text(LangManager.t("settings.island.title", "超级岛设置")) },
            supportingContent = { Text(LangManager.t("settings.island.desc", "开关、流式输出、阶段展开与必要权限")) },
            leadingContent = { Icon(Icons.Default.Crop, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.ISLAND, LangManager.t("settings.island.title", "超级岛设置")) }
        )
        HorizontalDivider()

        // AutoGLM phone agent
        if (com.zhibai.app.agent.AgentEngine.PHONE_AGENT_ENABLED) {
            ListItem(
                headlineContent = { Text(LangManager.t("settings.autoglm.title", "AutoGLM 手机助手")) },
                supportingContent = { Text(LangManager.t("settings.autoglm.desc", "智谱 Key、步数、无线 ADB")) },
                leadingContent = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { onNavigate(SettingsPage.AUTOGLM, LangManager.t("settings.autoglm.title", "AutoGLM 手机助手")) }
            )
            HorizontalDivider()
        }

        // About
        ListItem(
            headlineContent = { Text(LangManager.t("settings.about.title", "关于")) },
            supportingContent = { Text(LangManager.t("settings.about.desc", "应用信息与版本")) },
            leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.ABOUT, LangManager.t("settings.about.title", "关于")) }
        )
        HorizontalDivider()
    }

    if (showLangDialog) {
        AlertDialog(
            onDismissRequest = { showLangDialog = false },
            title = { Text(LangManager.t("settings.lang.title", "语言")) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    com.zhibai.app.i18n.LangManager.availableLocales().sorted().forEach { code ->
                        val name = com.zhibai.app.i18n.LangManager.languageName(code)
                        val selected = code == com.zhibai.app.i18n.LangManager.currentCode
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    com.zhibai.app.i18n.LangManager.switchTo(context, code)
                                    showLangDialog = false
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                name,
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
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
            },
            confirmButton = {
                TextButton(onClick = { showLangDialog = false }) { Text(LangManager.t("common.cancel", "取消")) }
            }
        )
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
            headlineContent = { Text(LangManager.t("settings.island.enable.title", "开启超级岛")) },
            supportingContent = {
                Text(
                    when {
                        !enabled -> LangManager.t("settings.island.enable.off", "已关闭，Agent 状态不会上岛")
                        !allGranted -> LangManager.t("settings.island.enable.noPerms", "权限未齐，补齐下方清单后才会正式上岛")
                        else -> LangManager.t("settings.island.enable.on", "已开启——满足权限时 Agent 状态会显示在超级岛")
                    }
                )
            },
            leadingContent = { Icon(Icons.Default.Crop, contentDescription = null) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = onEnabledChange) }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text(LangManager.t("settings.island.streaming.title", "显示流式输出过程")) },
            supportingContent = { Text(LangManager.t("settings.island.streaming.desc", "生成时把最新输出片段滚到岛胶囊上（约 4 次/秒）")) },
            leadingContent = { Icon(Icons.Default.Title, contentDescription = null) },
            trailingContent = { Switch(checked = showStreaming, onCheckedChange = onShowStreamingChange) }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text(LangManager.t("settings.island.expand.title", "阶段切换展开岛")) },
            supportingContent = {
                Text(if (expandOnPhase) LangManager.t("settings.island.expand.on", "进入下一阶段时重新展开胶囊") else LangManager.t("settings.island.expand.off", "阶段切换只原地更新，不重新展开"))
            },
            leadingContent = { Icon(Icons.Default.UnfoldMore, contentDescription = null) },
            trailingContent = { Switch(checked = expandOnPhase, onCheckedChange = onExpandOnPhaseChange) }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = {
                Text(
                    if (allGranted) LangManager.t("settings.island.perms.allOn", "必要权限 · 全部已开启") else LangManager.t("settings.island.perms.count", "必要权限 · {n}/{m}").replace("{n}", perms.count { it.granted }.toString()).replace("{m}", perms.size.toString()),
                    fontWeight = FontWeight.SemiBold
                )
            },
            supportingContent = { Text(LangManager.t("settings.island.perms.desc", "四项全部开启后，才会正式开始上岛")) },
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
            headlineContent = { Text(LangManager.t("settings.island.perms.recheck", "从系统设置返回后会自动复查权限")) },
            supportingContent = { Text(LangManager.t("settings.island.perms.recheckDesc", "全部变绿即可开始上岛，无需重启 App")) },
            leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) }
        )
    }
}

@Composable
private fun ApiKeyPage(
    apiKey: String,
    mmWrapped: Boolean = false,
    onSave: (String) -> Boolean = { true },
    onCopy: () -> Unit = {},
    onClear: () -> Unit = {},
    localAiSection: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    // Never prefill the saved key - after saving it cannot be viewed again.
    var apiKeyInput by rememberSaveable { mutableStateOf("") }
    var mmError by remember { mutableStateOf(false) }

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
            placeholder = { Text("sk-xxxxx / mm-xxxxx") },
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

        if (mmError) {
            Text(
                LangManager.t("settings.apikey.mmInvalid", "mm- 校验失败，未保存"),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        }

        Button(
            onClick = {
                val ok = onSave(apiKeyInput.trim())
                mmError = !ok
                if (ok) apiKeyInput = ""
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = apiKeyInput.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(LangManager.t("common.save", "保存"))
        }

        if (apiKey.isNotBlank()) {
            Text(
                if (mmWrapped) LangManager.t("settings.apikey.savedMm", "已保存 · mm- 外层")
                else LangManager.t("settings.apikey.saved", "已保存"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCopy, shape = RoundedCornerShape(12.dp)) {
                    Text(LangManager.t("settings.apikey.copyMm", "复制 (mm-)"))
                }
                OutlinedButton(
                    onClick = { onClear(); apiKeyInput = "" },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(LangManager.t("settings.apikey.clear", "清空"))
                }
            }
            Text(
                LangManager.t(
                    "settings.apikey.privacyNote",
                    "保存后此处不再显示密钥；复制走的永远是 mm- 形式，可随时清空或填入新值修改"
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        localAiSection()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(LangManager.t("settings.apikey.howto", "如何获取 API Key"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    LangManager.t("settings.apikey.howtoSteps", "1. 访问 platform.xiaomimimo.com\n2. 注册账号或登录\n3. 进入 API Keys 区域\n4. 创建新的 API Key\n5. 复制并粘贴到这里"),
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
            headlineContent = { Text(LangManager.t("settings.voice.current", "当前语音")) },
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
        Text(LangManager.t("settings.fontsize.preview", "预览"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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

        Text(LangManager.t("settings.fontsize.size", "大小：{n}sp").replace("{n}", sliderValue.toInt().toString()), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

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
            Text(LangManager.t("settings.fontsize.small", "小"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(LangManager.t("settings.fontsize.standard", "标准 (15)"), fontSize = 12.sp, color = if (sliderValue.toInt() == 15) MiMoColors.Primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (sliderValue.toInt() == 15) FontWeight.Bold else FontWeight.Normal)
            Text(LangManager.t("settings.fontsize.large", "大"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Reset to default
        if (sliderValue.toInt() != 15) {
            TextButton(
                onClick = { sliderValue = 15f; onSelect(15) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(LangManager.t("settings.fontsize.reset", "恢复默认 (15)"))
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
            "V2.6.0",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = { onNavigateToForbidden() }
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            LangManager.t("settings.about.powered", "由 Xiaomi MiMo API 提供支持"),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            LangManager.t("settings.about.unofficial", "MiMo AI 非官方客户端"),
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
            supportingContent = { Text(if (agentEnabled) LangManager.t("settings.agent.enabled.on", "已开启 — 模型可使用本地工具") else LangManager.t("settings.agent.enabled.off", "已关闭")) },
            leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
            trailingContent = {
                Switch(checked = agentEnabled, onCheckedChange = onAgentEnabledChange)
            }
        )
        HorizontalDivider()

        if (agentEnabled) {
            ListItem(
                headlineContent = { Text(LangManager.t("settings.agent.permMode.title", "权限模式")) },
                supportingContent = { Text(LangManager.t("settings.agent.permMode.desc", "工具调用的批准方式")) },
                leadingContent = { Icon(Icons.Default.Security, contentDescription = null) }
            )
            val permissionOptions = listOf(
                com.zhibai.app.agent.AgentEngine.MODE_ASK_ALL to
                    LangManager.t("settings.agent.permMode.askAll", "每次询问 — 每个工具调用都需手动批准") to LangManager.t("settings.agent.permMode.askAllBadge", "最安全"),
                com.zhibai.app.agent.AgentEngine.MODE_DEFAULT to
                    LangManager.t("settings.agent.permMode.default", "默认 — 低风险直接运行，高风险询问") to LangManager.t("settings.agent.permMode.defaultBadge", "均衡"),
                com.zhibai.app.agent.AgentEngine.MODE_AUTO to
                    LangManager.t("settings.agent.permMode.auto", "自动 — 全部直接运行") to LangManager.t("settings.agent.permMode.autoBadge", "最快"),
                com.zhibai.app.agent.AgentEngine.MODE_AI_APPROVE to
                    LangManager.t("settings.agent.permMode.aiApprove", "AI 审批 — AI 决定，被拒时询问你") to LangManager.t("settings.agent.permMode.aiApproveBadge", "智能")
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
                headlineContent = { Text(LangManager.t("settings.toolPerms.title", "工具权限")) },
                supportingContent = { Text(LangManager.t("settings.agent.toolPerms.desc", "启用/禁用单个工具")) },
                leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToToolPermissions() }
            )
            HorizontalDivider()

            // Wireless debugging ADB (配对后 run_adb 获得真实 shell 权限)
            ListItem(
                headlineContent = { Text(LangManager.t("settings.adb.title", "无线调试 ADB")) },
                supportingContent = { Text(LangManager.t("settings.adb.desc", "配对后 run_adb 获得真实 shell 权限")) },
                leadingContent = { Icon(Icons.Default.Cable, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToWirelessAdb() }
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Agent API Key") },
                supportingContent = { Text(if (agentApiKey.isBlank()) LangManager.t("settings.agent.key.empty", "留空则使用主密钥") else LangManager.t("settings.agent.key.configured", "已配置独立密钥")) },
                leadingContent = { Icon(Icons.Default.Key, contentDescription = null) }
            )
            OutlinedTextField(
                value = agentKeyInput,
                onValueChange = { agentKeyInput = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(LangManager.t("settings.agent.key.placeholder", "tp-xxxxx（留空则使用主密钥）")) },
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
                headlineContent = { Text(LangManager.t("settings.agent.workspace.title", "工作区路径")) },
                supportingContent = {
                    Text(if (workspacePath.isBlank()) LangManager.t("settings.agent.workspace.default", "默认：/sdcard/知白/") else workspacePath)
                },
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) }
            )
            OutlinedTextField(
                value = workspaceInput,
                onValueChange = { workspaceInput = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(LangManager.t("settings.agent.workspace.placeholder", "/sdcard/知白/ 或相对路径如 知白")) },
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
                Text(LangManager.t("settings.agent.save", "保存 Agent 配置"))
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
    promptXiaomiGuard: Boolean = true,
    unlimitedAttachments: Boolean = false,
    onForceNewSessionChange: (Boolean) -> Unit,
    onStrictFaithfulChange: (Boolean) -> Unit = {},
    onPromptAmbiguousClarifyChange: (Boolean) -> Unit = {},
    onPromptSensitiveRefusalChange: (Boolean) -> Unit = {},
    onPromptXiaomiGuardChange: (Boolean) -> Unit = {},
    onUnlimitedAttachmentsChange: (Boolean) -> Unit = {},
    onResetOnboarding: () -> Unit = {},
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
            headlineContent = { Text(LangManager.t("settings.forbidden.forceNew.title", "切换模型时强制新建会话")) },
            supportingContent = { Text(if (forceNewSession) LangManager.t("settings.forbidden.forceNew.on", "开 — 切换模型时新建对话") else LangManager.t("settings.forbidden.forceNew.off", "关 — 保留当前对话")) },
            leadingContent = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
            trailingContent = {
                Switch(checked = forceNewSession, onCheckedChange = onForceNewSessionChange)
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.strict.title", "严格忠实模式")) },
            supportingContent = {
                Text(if (strictFaithful) LangManager.t("settings.forbidden.strict.on", "开 — 严格参考内容信息，不虚构") else LangManager.t("settings.forbidden.strict.off", "关 — 允许模型自由发挥"))
            },
            leadingContent = { Icon(Icons.Default.Gavel, contentDescription = null) },
            trailingContent = {
                Switch(checked = strictFaithful, onCheckedChange = onStrictFaithfulChange)
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.ambiguous.title", "模糊问题先答后问")) },
            supportingContent = {
                Text(if (promptAmbiguousClarify) LangManager.t("settings.forbidden.ambiguous.on", "开 — 先尽量回答，再最多追问一句") else LangManager.t("settings.forbidden.promptOff", "关 — 不注入该提示词"))
            },
            leadingContent = { Icon(Icons.Default.HelpOutline, contentDescription = null) },
            trailingContent = {
                Switch(checked = promptAmbiguousClarify, onCheckedChange = onPromptAmbiguousClarifyChange)
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.sensitive.title", "敏感政治/历史限制")) },
            supportingContent = {
                Text(if (promptSensitiveRefusal) LangManager.t("settings.forbidden.sensitive.on", "开 — 拒答外国敏感政治新闻等") else LangManager.t("settings.forbidden.promptOff", "关 — 不注入该提示词"))
            },
            leadingContent = { Icon(Icons.Default.Public, contentDescription = null) },
            trailingContent = {
                Switch(checked = promptSensitiveRefusal, onCheckedChange = onPromptSensitiveRefusalChange)
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.xiaomi.title", "小米负面前提防护")) },
            supportingContent = {
                Text(if (promptXiaomiGuard) LangManager.t("settings.forbidden.xiaomi.on", "开 — 不承认、不复述、不背书针对小米及管理层的负面前提") else LangManager.t("settings.forbidden.promptOff", "关 — 不注入该提示词"))
            },
            leadingContent = { Icon(Icons.Default.Shield, contentDescription = null) },
            trailingContent = {
                Switch(checked = promptXiaomiGuard, onCheckedChange = onPromptXiaomiGuardChange)
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.attach.title", "无限制附件")) },
            supportingContent = {
                Text(if (unlimitedAttachments) LangManager.t("settings.forbidden.attach.on", "开 — 解除附件数量/大小限制") else LangManager.t("settings.forbidden.attach.off", "关 — 视频1份20MB · 音频1份10MB · 图片6张（有音视频时2张）"))
            },
            leadingContent = { Icon(Icons.Default.Attachment, contentDescription = null) },
            trailingContent = {
                Switch(checked = unlimitedAttachments, onCheckedChange = onUnlimitedAttachmentsChange)
            }
        )
        HorizontalDivider()

        // === Custom Theme Colors Section ===
        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.theme.title", "主题颜色自定义"), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(LangManager.t("settings.forbidden.theme.desc", "自定义字体/背景/气泡/文字颜色，留空恢复默认")) },
            leadingContent = { Icon(Icons.Default.Palette, contentDescription = null) }
        )

        // Font color
        ColorPickerRow(
            label = LangManager.t("settings.color.font", "字体颜色"),
            currentHex = customFontColor,
            presets = listOf("", "#000000", "#FFFFFF", "#333333", "#666666", "#1A1A1A", "#E0E0E0", "#8B5CF6", "#3B82F6", "#10B981"),
            onColorChange = onCustomFontColorChange
        )
        // Background color
        ColorPickerRow(
            label = LangManager.t("settings.color.bg", "背景颜色"),
            currentHex = customBgColor,
            presets = listOf("", "#F5F5F5", "#FFFFFF", "#0A0A0A", "#121212", "#FAFAFA", "#141414", "#F0F0F0", "#0E0E0E"),
            onColorChange = onCustomBgColorChange
        )
        // Bubble color
        ColorPickerRow(
            label = LangManager.t("settings.color.bubble", "气泡颜色"),
            currentHex = customBubbleColor,
            presets = listOf("", "#000000", "#FFFFFF", "#1E1E1E", "#2A2A2A", "#F0F0F0", "#1A1A1A", "#333333"),
            onColorChange = onCustomBubbleColorChange
        )
        // Text color
        ColorPickerRow(
            label = LangManager.t("settings.color.text", "文字颜色"),
            currentHex = customTextColor,
            presets = listOf("", "#1A1A1A", "#E0E0E0", "#333333", "#CCCCCC", "#666666", "#999999", "#000000", "#FFFFFF"),
            onColorChange = onCustomTextColorChange
        )
        HorizontalDivider()

        // === Custom Font Section ===
        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.font.title", "自定义字体"), fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                val fontName = com.zhibai.app.ui.theme.SystemFonts.listFonts()
                    .firstOrNull { it.path == customFontPath }?.name ?: LangManager.t("settings.forbidden.font.systemDefault", "系统默认")
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
            headlineContent = { Text(LangManager.t("settings.forbidden.resetOnboarding.title", "重走初始引导")) },
            supportingContent = { Text(LangManager.t("settings.forbidden.resetOnboarding.desc", "清除引导页完成标记，下次启动重新走一遍")) },
            leadingContent = { Icon(Icons.Default.Replay, contentDescription = null) },
            modifier = Modifier.clickable { confirmResetOnboarding = true }
        )
        HorizontalDivider()

        if (confirmResetOnboarding) {
            AlertDialog(
                onDismissRequest = { confirmResetOnboarding = false },
                title = { Text(LangManager.t("settings.forbidden.resetOnboarding.confirmTitle", "重走引导页？")) },
                text = { Text(LangManager.t("settings.forbidden.resetOnboarding.confirmText", "将清除引导完成标记，下次打开 App 会重新显示引导页。其他设置不受影响。")) },
                confirmButton = {
                    TextButton(onClick = {
                        onResetOnboarding()
                        confirmResetOnboarding = false
                    }) { Text(LangManager.t("common.confirm", "确认")) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmResetOnboarding = false }) { Text(LangManager.t("common.cancel", "取消")) }
                }
            )
        }


        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.exportLog.title", "导出日志")) },
            supportingContent = { Text(LangManager.t("settings.forbidden.exportLog.desc", "导出到 Download 目录，文件管理器可直接查看")) },
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
                    logExportPath = LangManager.t("settings.forbidden.exportLog.failed", "导出失败") + ": ${e.message}"
                }
                showLogExported = true
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.clearLog.title", "清除日志缓冲")) },
            supportingContent = { Text(LangManager.t("settings.forbidden.clearLog.desc", "清空内存里的 {n} 条诊断日志，不影响导出历史").replace("{n}", com.zhibai.app.util.AppLog.size().toString())) },
            leadingContent = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            modifier = Modifier.clickable {
                com.zhibai.app.util.AppLog.clear()
                com.zhibai.app.util.AppLog.d("log buffer cleared by user")
                logExportPath = LangManager.t("settings.forbidden.clearLog.done", "日志缓冲已清空")
                showLogExported = true
            }
        )
        HorizontalDivider()

        // === Manual crash test (3-step confirmation) ===
        var crashTestStep by remember { mutableIntStateOf(0) } // 0=off, 1=confirm1, 2=confirm2, 3=mode select
        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.crash.title", "手动崩溃测试"), color = MiMoColors.Error) },
            supportingContent = { Text(LangManager.t("settings.forbidden.crash.desc", "触发崩溃验证日志弹窗，需 3 次确认")) },
            leadingContent = { Icon(Icons.Default.Psychology, contentDescription = null, tint = MiMoColors.Error) },
            modifier = Modifier.clickable { if (crashTestStep == 0) crashTestStep = 1 }
        )
        HorizontalDivider()

        if (crashTestStep in 1..2) {
            AlertDialog(
                onDismissRequest = { crashTestStep = 0 },
                title = { Text(LangManager.t("settings.forbidden.crash.confirmTitle", "确认手动崩溃")) },
                text = {
                    Text(
                        if (crashTestStep == 1)
                            LangManager.t("settings.forbidden.crash.step1", "第 1/3 次确认：这会故意让 App 崩溃，用于测试崩溃日志弹窗。\n\n确定要继续吗？")
                        else
                            LangManager.t("settings.forbidden.crash.step2", "第 2/3 次确认：崩溃后下次启动会弹出崩溃日志窗口。\n\n真的确定吗？")
                    )
                },
                confirmButton = {
                    TextButton(onClick = { crashTestStep++ }) { Text(LangManager.t("common.continue", "继续")) }
                },
                dismissButton = {
                    TextButton(onClick = { crashTestStep = 0 }) { Text(LangManager.t("common.cancel", "取消")) }
                }
            )
        }

        if (crashTestStep == 3) {
            AlertDialog(
                onDismissRequest = { crashTestStep = 0 },
                title = { Text(LangManager.t("settings.forbidden.crash.modeTitle", "选择崩溃模式")) },
                text = {
                    Column {
                        Text(LangManager.t("settings.forbidden.crash.step3", "第 3/3 次确认：选择测试模式"), fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(LangManager.t("settings.forbidden.crash.singleDesc", "单次崩溃：验证崩溃日志弹窗能否正常弹出 + UI 设计检查"), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(LangManager.t("settings.forbidden.crash.doubleDesc", "连续崩溃两次：第一次崩溃后不关闭弹窗，再触发一次崩溃，验证日志是否被保护（不丢失）"), fontSize = 13.sp)
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            crashTestStep = 0
                            // Single crash
                            throw RuntimeException("手动崩溃测试（单次）- UI 检查")
                        }) { Text(LangManager.t("settings.forbidden.crash.single", "单次崩溃"), color = MiMoColors.Error) }
                        TextButton(onClick = {
                            crashTestStep = 0
                            // Double crash: write a fake crash log first, then crash
                            try {
                                val file = java.io.File(context.filesDir, "last_crash.log")
                                file.writeText("=== CRASH REPORT ===\nTime: ${java.time.LocalDateTime.now()}\nThread: main\n手动崩溃测试（连续第1次）\n目的：验证日志保护\n")
                            } catch (_: Exception) {}
                            throw RuntimeException("手动崩溃测试（连续第1次）- 日志保护验证")
                        }) { Text(LangManager.t("settings.forbidden.crash.double", "连续两次"), color = MiMoColors.Error) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { crashTestStep = 0 }) { Text(LangManager.t("common.cancel", "取消")) }
                }
            )
        }

        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.clearData.title", "清除所有数据")) },
            supportingContent = { Text(LangManager.t("settings.forbidden.clearData.desc", "删除所有会话和设置")) },
            leadingContent = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MiMoColors.Error) },
            modifier = Modifier.clickable { confirmClear = true }
        )
        HorizontalDivider()

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(LangManager.t("settings.forbidden.clearData.confirmTitle", "清除所有数据？")) },
                text = { Text(LangManager.t("settings.forbidden.clearData.confirmText", "这将删除所有会话、设置和 API Key，且无法撤销。")) },
                confirmButton = {
                    TextButton(onClick = {
                        onClearAllData()
                        confirmClear = false
                    }) {
                        Text(LangManager.t("settings.forbidden.clearData.confirmAction", "全部删除"), color = MiMoColors.Error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text(LangManager.t("common.cancel", "取消")) }
                }
            )
        }

        if (showLogExported) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                LangManager.t("settings.forbidden.exportLog.result", "日志已导出到: {path}").replace("{path}", logExportPath),
                fontSize = 13.sp,
                color = if (logExportPath.startsWith(LangManager.t("settings.forbidden.exportLog.failed", "导出失败"))) MiMoColors.Error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        ListItem(
            headlineContent = { Text(LangManager.t("settings.forbidden.hidden.title", "隐藏功能"), color = MiMoColors.Error) },
            supportingContent = { Text(LangManager.t("settings.forbidden.hidden.desc", "追加自定义系统提示词（需 3 次确认）")) },
            leadingContent = { Icon(Icons.Default.Warning, contentDescription = null, tint = MiMoColors.Error) },
            modifier = Modifier.clickable { confirmLevel1 = true }
        )
        HorizontalDivider()

        if (confirmLevel1) {
            AlertDialog(
                onDismissRequest = { confirmLevel1 = false },
                title = { Text(LangManager.t("settings.forbidden.hidden.warn1Title", "警告级别 1/3")) },
                text = { Text(LangManager.t("settings.forbidden.hidden.warn1Text", "你即将访问一个危险功能。添加自定义系统提示词可能导致异常行为。继续吗？")) },
                confirmButton = { TextButton(onClick = { confirmLevel1 = false; confirmLevel2 = true }) { Text(LangManager.t("settings.forbidden.hidden.warn1Action", "我已了解")) } },
                dismissButton = { TextButton(onClick = { confirmLevel1 = false }) { Text(LangManager.t("common.cancel", "取消")) } }
            )
        }
        if (confirmLevel2) {
            AlertDialog(
                onDismissRequest = { confirmLevel2 = false },
                title = { Text(LangManager.t("settings.forbidden.hidden.warn2Title", "警告级别 2/3")) },
                text = { Text(LangManager.t("settings.forbidden.hidden.warn2Text", "自定义提示词会追加到现有系统提示词之后。你无法查看或修改原始提示词。继续吗？")) },
                confirmButton = { TextButton(onClick = { confirmLevel2 = false; confirmLevel3 = true }) { Text(LangManager.t("settings.forbidden.hidden.warn2Action", "我接受")) } },
                dismissButton = { TextButton(onClick = { confirmLevel2 = false }) { Text(LangManager.t("common.cancel", "取消")) } }
            )
        }
        if (confirmLevel3) {
            AlertDialog(
                onDismissRequest = { confirmLevel3 = false },
                title = { Text(LangManager.t("settings.forbidden.hidden.finalTitle", "最终确认")) },
                text = {
                    Column {
                        Text(LangManager.t("settings.forbidden.hidden.finalLabel", "输入附加系统提示词内容："))
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customPrompt,
                            onValueChange = { customPrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(LangManager.t("settings.forbidden.hidden.finalPlaceholder", "例如：始终使用中文回复……")) },
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
                    }) { Text(LangManager.t("common.apply", "应用")) }
                },
                dismissButton = { TextButton(onClick = { confirmLevel3 = false }) { Text(LangManager.t("common.cancel", "取消")) } }
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
            headlineContent = { Text(LangManager.t("settings.inputStyle.glass.title", "磨砂玻璃输入框")) },
            supportingContent = { Text(LangManager.t("settings.inputStyle.glass.desc", "悬浮半透明，输入时变不透明，可透视下方消息")) },
            trailingContent = {
                Switch(checked = inputStyle == "glass", onCheckedChange = { onInputStyleChange(if (it) "glass" else "classic") })
            }
        )
        HorizontalDivider()

        if (inputStyle == "glass") {
            // Shadow
            ListItem(
                headlineContent = { Text(LangManager.t("settings.inputStyle.shadow.title", "悬浮阴影")) },
                supportingContent = { Text(LangManager.t("settings.inputStyle.shadow.desc", "输入框下方投射柔和阴影")) },
                trailingContent = {
                    Switch(checked = inputShadowEnabled, onCheckedChange = onInputShadowChange)
                }
            )
            HorizontalDivider()

            // Blur color presets
            ListItem(headlineContent = { Text(LangManager.t("settings.inputStyle.blur.title", "磨砂颜色")) })
            val presets = listOf(
                "auto" to LangManager.t("settings.inputStyle.preset.auto", "自动深浅"),
                "#B0FFFFFF" to LangManager.t("settings.inputStyle.preset.classicWhite", "经典白"),
                "#80FFFFFF" to LangManager.t("settings.inputStyle.preset.lightWhite", "轻透白"),
                "#B01E1E1E" to LangManager.t("settings.inputStyle.preset.dark", "深色"),
                "#801E1E1E" to LangManager.t("settings.inputStyle.preset.lightBlack", "轻透黑"),
                "#90F5F0E8" to LangManager.t("settings.inputStyle.preset.warmPaper", "暖纸"),
                "#90E8EDF5" to LangManager.t("settings.inputStyle.preset.coldMist", "冷雾")
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
                label = { Text(LangManager.t("settings.inputStyle.customLabel", "自定义颜色 (AARRGGBB)")) },
                placeholder = { Text("#B0FFFFFF") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            TextButton(
                onClick = { onInputBlurColorChange(customHex.trim()) },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text(LangManager.t("settings.inputStyle.customApply", "应用自定义颜色")) }
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
                Text(if (expanded) LangManager.t("chat.collapse", "收起") else LangManager.t("common.select", "选择"), fontSize = 12.sp)
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
                            Text(LangManager.t("settings.color.defaultAbbr", "默"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    label = { Text(LangManager.t("settings.color.customLabel", "自定义 #RRGGBB"), fontSize = 11.sp) },
                    placeholder = { Text("#1A2B3C", fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                TextButton(onClick = {
                    val hex = customInput.trim().let { if (it.startsWith("#")) it else "#$it" }
                    if (hex.matches(Regex("^#[0-9A-Fa-f]{6}$"))) onColorChange(hex)
                }) { Text(LangManager.t("common.apply", "应用"), fontSize = 12.sp) }
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
            Text(if (expanded) LangManager.t("settings.fontPicker.collapse", "收起字体列表") else LangManager.t("settings.fontPicker.select", "选择预设字体 ({n} 个)").replace("{n}", fonts.size.toString()), fontSize = 12.sp)
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

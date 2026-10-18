package com.mimo.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mimo.app.ui.theme.MiMoColors

private enum class SettingsPage {
    MAIN, API_KEY, VOICE, FONT_SIZE, AGENT, ABOUT, FORBIDDEN, TOOL_PERMISSIONS
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
    unlimitedAttachments: Boolean = false,
    agentEngine: com.mimo.app.agent.AgentEngine? = null,
    onApiKeyChange: (String) -> Unit,
    onTtsVoiceChange: (String) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onForceNewSessionChange: (Boolean) -> Unit,
    onAgentEnabledChange: (Boolean) -> Unit,
    onAgentPermissionModeChange: (Int) -> Unit,
    onAgentApiKeyChange: (String) -> Unit,
    onAgentBaseUrlChange: (String) -> Unit,
    onWorkspacePathChange: (String) -> Unit = {},
    onStrictFaithfulChange: (Boolean) -> Unit = {},
    onUnlimitedAttachmentsChange: (Boolean) -> Unit = {},
    onClearAllData: () -> Unit,
    onCustomPromptChange: (String) -> Unit,
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
            SettingsPage.API_KEY -> ApiKeyPage(
                apiKey = apiKey,
                onSave = onApiKeyChange,
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
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.ABOUT -> AboutPage(
                onNavigateToForbidden = { currentPage = SettingsPage.FORBIDDEN; pageTitle = "隐藏设置" },
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.FORBIDDEN -> ForbiddenPage(
                forceNewSession = forceNewSessionOnModelSwitch,
                strictFaithful = strictFaithful,
                unlimitedAttachments = unlimitedAttachments,
                onForceNewSessionChange = onForceNewSessionChange,
                onStrictFaithfulChange = onStrictFaithfulChange,
                onUnlimitedAttachmentsChange = onUnlimitedAttachmentsChange,
                onClearAllData = onClearAllData,
                onCustomPromptChange = onCustomPromptChange,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsPage.TOOL_PERMISSIONS -> com.mimo.app.ui.screens.ToolPermissionScreen(
                agentEngine = agentEngine,
                onBack = { currentPage = SettingsPage.AGENT; pageTitle = "AI Agent" }
            )
        }
    }
}

@Composable
private fun SettingsMainMenu(
    onNavigate: (SettingsPage, String) -> Unit,
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

        // AI Agent
        ListItem(
            headlineContent = { Text("AI Agent") },
            supportingContent = { Text("权限模式、工作区与工具") },
            leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(SettingsPage.AGENT, "AI Agent") }
        )
        HorizontalDivider()

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
private fun ApiKeyPage(
    apiKey: String,
    onSave: (String) -> Unit,
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
            onClick = { onSave(apiKeyInput) },
            modifier = Modifier.fillMaxWidth(),
            enabled = apiKeyInput.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = MiMoColors.Primary)
        ) {
            Text("保存")
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MiMoColors.Primary.copy(alpha = 0.08f)
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
        Text("试听", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "The quick brown fox jumps over the lazy dog. 敏捷的棕色狐狸跳过了懒狗。",
                fontSize = sliderValue.toInt().sp,
                lineHeight = (sliderValue.toInt() + 7).sp,
                modifier = Modifier.padding(16.dp)
            )
        }

        Text("大小：${sliderValue.toInt()}sp", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onSelect(sliderValue.toInt()) },
            valueRange = 12f..24f,
            steps = 11,
            modifier = Modifier.fillMaxWidth()
        )

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
            painter = painterResource(id = com.mimo.app.R.drawable.mimo_logo_foreground),
            contentDescription = null,
            modifier = Modifier.size(160.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("知白", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        // Long-press version number → hidden settings
        Text(
            "v2.5.0",
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
                com.mimo.app.agent.AgentEngine.MODE_ASK_ALL to
                    "每次询问 — 每个工具调用都需手动批准" to "最安全",
                com.mimo.app.agent.AgentEngine.MODE_DEFAULT to
                    "默认 — 低风险直接运行，高风险询问" to "均衡",
                com.mimo.app.agent.AgentEngine.MODE_AUTO to
                    "自动 — 全部直接运行" to "最快",
                com.mimo.app.agent.AgentEngine.MODE_AI_APPROVE to
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
            Spacer(modifier = Modifier.height(8.dp))

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
                colors = ButtonDefaults.buttonColors(containerColor = MiMoColors.Primary)
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
    unlimitedAttachments: Boolean = false,
    onForceNewSessionChange: (Boolean) -> Unit,
    onStrictFaithfulChange: (Boolean) -> Unit = {},
    onUnlimitedAttachmentsChange: (Boolean) -> Unit = {},
    onClearAllData: () -> Unit,
    onCustomPromptChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showLogExported by remember { mutableStateOf(false) }
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

        ListItem(
            headlineContent = { Text("导出日志") },
            supportingContent = { Text("将应用日志导出到文件") },
            leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
            modifier = Modifier.clickable {
                try {
                    val log = Runtime.getRuntime().exec("logcat -d -s MiMoApp").inputStream.bufferedReader().readText()
                    val file = java.io.File(context.getExternalFilesDir(null), "mimo_log_${System.currentTimeMillis()}.txt")
                    file.writeText(log.ifBlank { "No logs found" })
                } catch (_: Exception) {}
                showLogExported = true
            }
        )
        HorizontalDivider()

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
                "日志已导出到应用外部文件目录",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

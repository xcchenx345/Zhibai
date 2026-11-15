package com.zhibai.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PreferencesPage(
    prefDefaultModel: String,
    prefStartupMode: String,
    prefStartupSession: String,
    sessions: List<Pair<String, String>>,
    onPrefDefaultModelChange: (String) -> Unit,
    onPrefStartupModeChange: (String) -> Unit,
    onPrefStartupSessionChange: (String) -> Unit,
    prefAgentThinkLevel: Int,
    onPrefAgentThinkLevelChange: (Int) -> Unit,
    prefAutoCompress: Boolean,
    prefAutoCompressThreshold: Int,
    onPrefAutoCompressChange: (Boolean) -> Unit,
    onPrefAutoCompressThresholdChange: (Int) -> Unit,
    prefAutoSaveImages: String,
    onPrefAutoSaveImagesChange: (String) -> Unit,
    prefLongTermMemory: Boolean,
    prefMemoryAutoWrite: Boolean,
    onPrefLongTermMemoryChange: (Boolean) -> Unit,
    onPrefMemoryAutoWriteChange: (Boolean) -> Unit,
    prefModelHabit: String,
    onPrefModelHabitChange: (String) -> Unit,
    prefScenario: String,
    onPrefScenarioChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showModelMenu by remember { mutableStateOf(false) }
    var showStartupMenu by remember { mutableStateOf(false) }
    var showThinkMenu by remember { mutableStateOf(false) }
    var showScenarioMenu by remember { mutableStateOf(false) }
    var showSessionPicker by remember { mutableStateOf(false) }

    val thinkLabels = listOf("关", "低", "中", "高", "MAX")
    val scenarioLabels = mapOf("" to "默认", "code" to "编程", "work" to "工作", "study" to "学习", "daily" to "日常")
    val startupLabels = mapOf("new" to "新对话", "last" to "上次对话", "session" to "指定对话")

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        // === 启动行为 ===
        ListItem(
            headlineContent = { Text("启动行为", fontWeight = FontWeight.SemiBold) },
            leadingContent = { Icon(Icons.Default.RocketLaunch, contentDescription = null) }
        )

        // 默认模型
        Box {
            ListItem(
                headlineContent = { Text("启动时默认模型") },
                supportingContent = { Text(if (prefDefaultModel.isBlank()) "使用上次的模型" else prefDefaultModel) },
                modifier = Modifier.clickable { showModelMenu = true }
            )
            DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                DropdownMenuItem(text = { Text("使用上次的模型") }, onClick = { onPrefDefaultModelChange(""); showModelMenu = false })
                listOf("mimo-v2.6-pro", "mimo-v2.6-flash", "mimo-v2.6-ultraspeed").forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = { onPrefDefaultModelChange(m); showModelMenu = false })
                }
            }
        }

        // 启动对话模式
        Box {
            ListItem(
                headlineContent = { Text("启动时打开") },
                supportingContent = { Text(startupLabels[prefStartupMode] ?: "新对话") },
                modifier = Modifier.clickable { showStartupMenu = true }
            )
            DropdownMenu(expanded = showStartupMenu, onDismissRequest = { showStartupMenu = false }) {
                startupLabels.forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        onPrefStartupModeChange(key); showStartupMenu = false
                    })
                }
            }
        }

        // 指定对话
        if (prefStartupMode == "session") {
            ListItem(
                headlineContent = { Text("选择启动对话") },
                supportingContent = {
                    Text(sessions.firstOrNull { it.first == prefStartupSession }?.second ?: "点击选择")
                },
                modifier = Modifier.clickable { showSessionPicker = !showSessionPicker }
            )
            if (showSessionPicker) {
                sessions.forEach { (id, title) ->
                    ListItem(
                        headlineContent = { Text(title, fontSize = 14.sp) },
                        modifier = Modifier.padding(start = 32.dp).clickable {
                            onPrefStartupSessionChange(id); showSessionPicker = false
                        },
                        trailingContent = {
                            if (id == prefStartupSession) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
        }
        HorizontalDivider()

        // === Agent 思考等级 ===
        Box {
            ListItem(
                headlineContent = { Text("Agent 默认思考等级") },
                supportingContent = { Text(thinkLabels[prefAgentThinkLevel] ?: "中") },
                leadingContent = { Icon(Icons.Default.Psychology, contentDescription = null) },
                modifier = Modifier.clickable { showThinkMenu = true }
            )
            DropdownMenu(expanded = showThinkMenu, onDismissRequest = { showThinkMenu = false }) {
                thinkLabels.forEachIndexed { idx, label ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onPrefAgentThinkLevelChange(idx); showThinkMenu = false })
                }
            }
        }
        HorizontalDivider()

        // === 自动压缩 ===
        ListItem(
            headlineContent = { Text("到达 Token 阈值自动压缩") },
            supportingContent = { Text("超过阈值时自动整理对话，保留最近 10 条") },
            leadingContent = { Icon(Icons.Default.Compress, contentDescription = null) },
            trailingContent = { Switch(checked = prefAutoCompress, onCheckedChange = onPrefAutoCompressChange) }
        )
        if (prefAutoCompress) {
            var thresholdText by remember(prefAutoCompressThreshold) { mutableStateOf(prefAutoCompressThreshold.toString()) }
            OutlinedTextField(
                value = thresholdText,
                onValueChange = { thresholdText = it.filter { c -> c.isDigit() } },
                label = { Text("Token 阈值（100000-900000）") },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            TextButton(
                onClick = { thresholdText.toIntOrNull()?.let { onPrefAutoCompressThresholdChange(it) } },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text("保存阈值") }
        }
        HorizontalDivider()

        // === Agent 上传图片存本地 ===
        val saveImgLabels = mapOf("ask" to "每次询问", "always" to "总是保存", "never" to "从不保存")
        ListItem(
            headlineContent = { Text("Agent 上传图片存本地") },
            supportingContent = { Text("上传图片时复制一份到工作目录 uploads（当前：${saveImgLabels[prefAutoSaveImages] ?: "每次询问"}）") },
            leadingContent = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
            modifier = Modifier.clickable {
                val next = when (prefAutoSaveImages) {
                    "ask" -> "always"; "always" -> "never"; else -> "ask"
                }
                onPrefAutoSaveImagesChange(next)
            }
        )
        HorizontalDivider()

        // === 长期记忆 ===
        ListItem(
            headlineContent = { Text("长期记忆") },
            supportingContent = { Text("跨对话记住用户偏好和重要信息，存为 MEMORY.md") },
            leadingContent = { Icon(Icons.Default.Memory, contentDescription = null) },
            trailingContent = { Switch(checked = prefLongTermMemory, onCheckedChange = onPrefLongTermMemoryChange) }
        )
        if (prefLongTermMemory) {
            ListItem(
                headlineContent = { Text("AI 自动写入记忆") },
                supportingContent = { Text("每次对话后自动整理值得记住的内容") },
                trailingContent = { Switch(checked = prefMemoryAutoWrite, onCheckedChange = onPrefMemoryAutoWriteChange) }
            )
        }
        HorizontalDivider()

        // === 模型习惯 ===
        ListItem(
            headlineContent = { Text("模型习惯") },
            supportingContent = { Text("在每次回复末尾附加自定义文字，最多 10 字") },
            leadingContent = { Icon(Icons.Default.Pets, contentDescription = null) }
        )
        OutlinedTextField(
            value = prefModelHabit,
            onValueChange = { onPrefModelHabitChange(it.take(10)) },
            label = { Text("末尾习惯（如：喵）") },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            supportingText = { Text("${prefModelHabit.length}/10") }
        )
        HorizontalDivider()

        // === 场景偏好 ===
        Box {
            ListItem(
                headlineContent = { Text("偏向场景") },
                supportingContent = { Text(scenarioLabels[prefScenario] ?: "默认") },
                leadingContent = { Icon(Icons.Default.Category, contentDescription = null) },
                modifier = Modifier.clickable { showScenarioMenu = true }
            )
            DropdownMenu(expanded = showScenarioMenu, onDismissRequest = { showScenarioMenu = false }) {
                scenarioLabels.forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onPrefScenarioChange(key); showScenarioMenu = false })
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

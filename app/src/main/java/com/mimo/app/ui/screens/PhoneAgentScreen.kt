package com.mimo.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mimo.app.agent.PhoneAgentEngine
import com.mimo.app.agent.PhoneAgentStep
import com.mimo.app.agent.PhoneAgentState
import com.mimo.app.ui.theme.MiMoColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneAgentScreen(
    onBack: () -> Unit,
    onRequestScreenCapture: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf<PhoneAgentEngine?>(null) }
    var state by remember { mutableStateOf(PhoneAgentState()) }
    var instruction by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var currentScreenshot by remember { mutableStateOf<Bitmap?>(null) }
    var showApiKeyInput by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var taskResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        engine = PhoneAgentEngine(context)
        engine?.onStateChange = { state = it }
        engine?.onScreenshot = { currentScreenshot = it }
        engine?.onTaskComplete = { taskResult = it }
        engine?.init(apiKey)
        // Load saved zhipu key
        val prefs = context.getSharedPreferences("mimo_settings", android.content.Context.MODE_PRIVATE)
        apiKey = prefs.getString("zhipu_api_key", "") ?: ""
    }

    DisposableEffect(Unit) {
        onDispose {
            engine?.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手机助手") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showApiKeyInput = !showApiKeyInput }) {
                        Icon(Icons.Default.Key, "API Key")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            if (showApiKeyInput) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("AutoGLM API Key") },
                    placeholder = { Text("输入智谱 API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        engine?.init(apiKey)
                        val prefs = context.getSharedPreferences("mimo_settings", android.content.Context.MODE_PRIVATE)
                        prefs.edit().putString("zhipu_api_key", apiKey).apply()
                        showApiKeyInput = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存") }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (!Settings.canDrawOverlays(context)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MiMoColors.Warning.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "需要悬浮窗权限",
                            fontWeight = FontWeight.Bold,
                            color = MiMoColors.Warning
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("执行任务时需要悬浮窗来控制暂停/继续/终止", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Layers, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("授予悬浮窗权限")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (!com.mimo.app.agent.MiMoAccessibilityService.isRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MiMoColors.Warning.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "需要无障碍服务权限",
                            fontWeight = FontWeight.Bold,
                            color = MiMoColors.Warning
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Agent 需要无障碍服务来自动执行手机操作", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Accessibility, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("开启无障碍服务")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (!com.mimo.app.agent.ScreenCaptureService.hasPermission()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MiMoColors.Warning.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "需要截屏权限",
                            fontWeight = FontWeight.Bold,
                            color = MiMoColors.Warning
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Agent 需要截屏权限来分析当前屏幕内容", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onRequestScreenCapture() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ScreenShare, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("授权截屏权限")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            connectionStatus?.let { status ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MiMoColors.Success.copy(alpha = 0.1f))
                ) {
                    Text(
                        status,
                        modifier = Modifier.padding(12.dp),
                        color = MiMoColors.Success
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            currentScreenshot?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Screenshot",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MiMoColors.Divider, RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("指令") },
                placeholder = { Text("例如: 打开微信") },
                enabled = !state.isRunning,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            engine?.executeInstruction(instruction)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = instruction.isNotBlank() && !state.isRunning && apiKey.isNotBlank()
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("执行")
                }

                Button(
                    onClick = { engine?.stop() },
                    modifier = Modifier.weight(1f),
                    enabled = state.isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = MiMoColors.Error)
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("停止")
                }
            }

            if (state.isWaitingForUser) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        engine?.continueExecution()
                        scope.launch {
                            engine?.executeInstruction(instruction)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("继续（用户操作已完成）")
                }
            }

            if (state.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MiMoColors.Error.copy(alpha = 0.1f))
                ) {
                    Text(
                        state.error!!,
                        modifier = Modifier.padding(12.dp),
                        color = MiMoColors.Error
                    )
                }
            }

            taskResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MiMoColors.Success.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("任务结果", fontWeight = FontWeight.Bold, color = MiMoColors.Success)
                        Text(result, fontSize = 14.sp)
                    }
                }
            }

            if (state.steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "步骤 (${state.currentStep}/${state.totalSteps})",
                    fontWeight = FontWeight.Bold
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.steps) { step ->
                        StepItem(step)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(step: PhoneAgentStep) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (step.action?.type) {
                    "tap" -> Icons.Default.TouchApp
                    "swipe" -> Icons.Default.SwapVert
                    "type" -> Icons.Default.Keyboard
                    "back" -> Icons.Default.ArrowBack
                    "home" -> Icons.Default.Home
                    "launch" -> Icons.Default.Launch
                    "done" -> Icons.Default.CheckCircle
                    "take_over" -> Icons.Default.Warning
                    else -> Icons.Default.Android
                },
                contentDescription = null,
                tint = when (step.action?.type) {
                    "done" -> MiMoColors.Success
                    "take_over" -> Color(0xFFFF9800)
                    else -> MiMoColors.Primary
                },
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(step.description, fontSize = 14.sp)
        }
    }
}

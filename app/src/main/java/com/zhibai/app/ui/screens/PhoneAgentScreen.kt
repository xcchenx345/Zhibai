package com.zhibai.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.zhibai.app.adb.AdbManager
import com.zhibai.app.agent.PhoneAgentEngine
import com.zhibai.app.agent.PhoneAgentState
import com.zhibai.app.agent.PhoneAgentStep
import com.zhibai.app.ui.theme.MiMoColors
import kotlinx.coroutines.launch

/**
 * Manual AutoGLM runner. Config (key / URL / steps / ADB pair) lives in Settings → AutoGLM.
 * Live control while running is on the Super Island (终止 / 暂时接管).
 */
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
    var currentScreenshot by remember { mutableStateOf<Bitmap?>(null) }
    var adbOk by remember { mutableStateOf<Boolean?>(null) }
    var taskResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        engine = PhoneAgentEngine(context)
        engine?.onStateChange = { state = it }
        engine?.onScreenshot = { currentScreenshot = it }
        engine?.onTaskComplete = { taskResult = it }
        engine?.loadConfig()
        adbOk = AdbManager.testConnection()
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
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ADB status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (adbOk) {
                        true -> MiMoColors.Success.copy(alpha = 0.1f)
                        false -> MiMoColors.Error.copy(alpha = 0.1f)
                        null -> MiMoColors.SurfaceElevated
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (adbOk) {
                            true -> Icons.Default.CheckCircle
                            false -> Icons.Default.Error
                            null -> Icons.Default.Cable
                        },
                        null,
                        tint = when (adbOk) {
                            true -> MiMoColors.Success
                            false -> MiMoColors.Error
                            null -> MiMoColors.TextSecondary
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (adbOk) {
                            true -> "无线 ADB 已连接"
                            false -> "无线 ADB 未连接 — 去设置 → AutoGLM 配对"
                            null -> "检测 ADB 中..."
                        },
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            currentScreenshot?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Screenshot",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MiMoColors.Divider, RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("指令") },
                placeholder = { Text("例如: 打开美团搜索附近的火锅店") },
                enabled = !state.isRunning,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        taskResult = null
                        scope.launch { engine?.executeInstruction(instruction) }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = instruction.isNotBlank() && !state.isRunning && adbOk == true
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text("执行")
                }

                Button(
                    onClick = { engine?.stop() },
                    modifier = Modifier.weight(1f),
                    enabled = state.isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = MiMoColors.Error)
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(4.dp))
                    Text("终止")
                }
            }

            if (state.isRunning) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "执行中 — 可在超级岛上下拉选择「终止」或「暂时接管」",
                    fontSize = 12.sp,
                    color = MiMoColors.TextSecondary
                )
            }

            if (state.isPaused || state.isWaitingForUser) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (state.isPaused && state.isRunning) {
                            engine?.resume()
                        } else {
                            scope.launch { engine?.executeInstruction(instruction) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.isPaused) "继续执行" else "继续（接管操作已完成）")
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MiMoColors.Error.copy(alpha = 0.1f))
                ) {
                    Text(state.error!!, modifier = Modifier.padding(12.dp), color = MiMoColors.Error)
                }
            }

            taskResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MiMoColors.Success.copy(alpha = 0.1f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("任务结果", fontWeight = FontWeight.Bold, color = MiMoColors.Success)
                        Text(result, fontSize = 14.sp)
                    }
                }
            }

            if (state.steps.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("步骤 (${state.currentStep}/${state.totalSteps})", fontWeight = FontWeight.Bold)
                state.steps.forEach { step -> StepItem(step) }
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
            Spacer(Modifier.width(12.dp))
            Text(step.description, fontSize = 14.sp)
        }
    }
}

package com.zhibai.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.localai.LocalAiSupport
import com.zhibai.app.localai.LocalModel
import com.zhibai.app.localai.ModelDownloader
import com.zhibai.app.localai.ModelRegistry
import com.zhibai.app.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LocalAiFlow { NONE, WARNING, UNSUPPORTED, BATTERY, INSTALL_CONFIRM, ERROR }

/**
 * "使用本地AI" switch + the full enable flow:
 * warning dialog → device-tier check → battery-unrestricted gate →
 * registry fetch → install (island progress) → enable.
 * Shown in both the API Key page and the Agent API config page.
 */
@Composable
fun LocalAiSettingsSection(viewModel: ChatViewModel) {
    val enabled by viewModel.localAiEnabled.collectAsState()
    val model by viewModel.localAiModel.collectAsState()
    val installing by viewModel.localAiInstalling.collectAsState()
    val dlProgress by viewModel.localAiProgress.collectAsState()

    val scope = rememberCoroutineScope()
    var flow by remember { mutableStateOf(LocalAiFlow.NONE) }
    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var support by remember { mutableStateOf<LocalAiSupport.Support?>(null) }
    var pendingModel by remember { mutableStateOf<LocalModel?>(null) }

    fun showError(msg: String) {
        statusMsg = msg
        flow = LocalAiFlow.ERROR
    }

    /** Battery ok → fetch registry → installed? enable : confirm install. */
    fun prepareInstall() {
        busy = true
        statusMsg = "正在获取模型列表…"
        scope.launch {
            try {
                val s = support ?: viewModel.localAiSupport().also { support = it }
                if (!s.supported || s.tier == null) {
                    flow = LocalAiFlow.UNSUPPORTED
                    return@launch
                }
                val models = withContext(Dispatchers.IO) { ModelRegistry.fetch() }
                val def = ModelRegistry.defaultModel(models, s.tier)
                    ?: run {
                        showError("云端模型列表里没有本档位可用的 MiMo 模型")
                        return@launch
                    }
                pendingModel = def
                if (viewModel.isLocalAiInstalled(def)) {
                    flow = LocalAiFlow.NONE
                    viewModel.setLocalAiEnabled(true, def.displayName)
                } else {
                    flow = LocalAiFlow.INSTALL_CONFIRM
                }
            } catch (e: Exception) {
                showError("获取模型列表失败: ${e.message ?: "网络错误"}")
            } finally {
                busy = false
                statusMsg = ""
            }
        }
    }

    fun afterBatteryGate() {
        if (viewModel.localAiBatteryOk()) {
            prepareInstall()
        } else {
            statusMsg = "省电策略仍未设为「无限制」，请在系统设置中修改后重试"
            flow = LocalAiFlow.BATTERY
        }
    }

fun humanBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024.0 / 1024.0)
    b >= 1024L * 1024 -> "%.0f MB".format(b / 1024.0 / 1024.0)
    else -> "%.0f KB".format(b / 1024.0)
}

// ------------------------------------------------------------------ switch
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("使用本地AI", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(2.dp))
            val subtitle = when {
                installing -> dlProgress?.let { p ->
                    buildString {
                        append(if (p.percent >= 0) "下载中 ${p.percent}%" else "下载中")
                        if (p.bytesTotal != null && p.bytesTotal > 0) {
                            append(" · ").append(humanBytes(p.bytesDone))
                            append('/').append(humanBytes(p.bytesTotal))
                        }
                        if (p.speedBps > 0) append(" · ").append(humanBytes(p.speedBps)).append("/s")
                    }
                } ?: "正在安装本地模型…"
                busy -> statusMsg.ifBlank { "处理中…" }
                enabled && model.isNotBlank() -> "已启用 · $model（内置 MNN，不依赖云端）"
                else -> "关闭 — 使用云端 MiMo API"
            }
            Text(
                subtitle,
                fontSize = 12.sp,
                color = if (installing || busy) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (statusMsg.isNotBlank() && !busy && !installing) {
                Text(statusMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        if (busy || installing) {
            CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Switch(
            checked = enabled,
            enabled = !busy && !installing,
            onCheckedChange = { want ->
                if (!want) {
                    viewModel.setLocalAiEnabled(false)
                } else {
                    statusMsg = ""
                    flow = LocalAiFlow.WARNING
                }
            }
        )
    }

    // ----------------------------------------------------------------- dialogs
    when (flow) {
        LocalAiFlow.NONE -> {}

        LocalAiFlow.WARNING -> AlertDialog(
            onDismissRequest = { flow = LocalAiFlow.NONE },
            title = { Text("启用本地AI？") },
            text = {
                Text(
                    buildAnnotatedString {
                        append("本地AI可能更慢，甚至导致发热卡顿\n")
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append("严重的会导致死机")
                        pop()
                        append("\n是否继续？")
                    },
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = viewModel.localAiSupport()
                    support = s
                    if (!s.supported) {
                        flow = LocalAiFlow.UNSUPPORTED
                    } else if (!viewModel.localAiBatteryOk()) {
                        statusMsg = ""
                        flow = LocalAiFlow.BATTERY
                    } else {
                        prepareInstall()
                    }
                }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { flow = LocalAiFlow.NONE }) { Text("取消") }
            }
        )

        LocalAiFlow.UNSUPPORTED -> AlertDialog(
            onDismissRequest = { flow = LocalAiFlow.NONE },
            title = { Text("机型不在支持名单") },
            text = {
                Text(
                    (support?.reason ?: "该设备不满足本地AI要求") +
                        "\n\n支持名单按内存分档：低档 4-6GB、中档 6-8GB、高档 ≥8GB（需 arm64）。"
                )
            },
            confirmButton = {
                TextButton(onClick = { flow = LocalAiFlow.NONE }) { Text("关闭") }
            }
        )

        LocalAiFlow.BATTERY -> AlertDialog(
            onDismissRequest = { flow = LocalAiFlow.NONE },
            title = { Text("先设置省电策略") },
            text = {
                Text(
                    "准备安装本地模型前，需要把本应用的省电策略设为「无限制」，" +
                        "否则下载和推理过程可能被系统杀掉。\n\n" +
                        statusMsg
                )
            },
            confirmButton = {
                TextButton(onClick = { afterBatteryGate() }) { Text("我已设置") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.openLocalAiBatterySettings()
                }) { Text("去设置") }
            }
        )

        LocalAiFlow.INSTALL_CONFIRM -> AlertDialog(
            onDismissRequest = { flow = LocalAiFlow.NONE },
            title = { Text("准备安装") },
            text = {
                val m = pendingModel
                Text(
                    buildString {
                        append("将下载并安装本地模型：\n")
                        append("· ").append(m?.displayName ?: "—").append('\n')
                        if (m != null && m.paramsB > 0) {
                            append("· 约 ").append("%.1f".format(m.paramsB * 0.74)).append(" GB（4bit）\n")
                        }
                        append("\n下载期间会通过灵动岛显示进度，完成后提醒你。")
                    },
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = pendingModel ?: return@TextButton
                    flow = LocalAiFlow.NONE
                    viewModel.installLocalModel(m) { ok, msg ->
                        if (ok) {
                            viewModel.setLocalAiEnabled(true, m.displayName)
                        } else {
                            scope.launch { showError("安装失败: $msg") }
                        }
                    }
                }) { Text("开始安装") }
            },
            dismissButton = {
                TextButton(onClick = { flow = LocalAiFlow.NONE }) { Text("取消") }
            }
        )

        LocalAiFlow.ERROR -> AlertDialog(
            onDismissRequest = { flow = LocalAiFlow.NONE },
            title = { Text("无法启用本地AI") },
            text = { Text(statusMsg) },
            confirmButton = {
                TextButton(onClick = { flow = LocalAiFlow.NONE }) { Text("关闭") }
            }
        )
    }
}

package com.zhibai.app.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.adb.AdbManager
import com.zhibai.app.ui.theme.MiMoColors
import kotlinx.coroutines.launch

/**
 * AutoGLM / 手机助手 configuration: zhipu key, base URL, max steps, wireless ADB status.
 * Prefs: SharedPreferences "mimo_settings" — same keys PhoneAgentEngine reads.
 */
@Composable
fun AutoGLMSettingsPage(
    onNavigateToWirelessAdb: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences("mimo_settings", Context.MODE_PRIVATE)
    }

    var apiKey by remember {
        mutableStateOf(prefs.getString("zhipu_api_key", "") ?: "")
    }
    var baseUrl by remember {
        mutableStateOf(
            prefs.getString("zhipu_base_url", "https://open.bigmodel.cn/api/paas/v4")
                ?: "https://open.bigmodel.cn/api/paas/v4"
        )
    }
    var maxSteps by remember {
        mutableStateOf(prefs.getInt("phone_agent_max_steps", 20).toString())
    }
    var showKey by remember { mutableStateOf(false) }
    var adbStatus by remember { mutableStateOf<String?>(null) }
    var adbBusy by remember { mutableStateOf(false) }
    var adbOk by remember { mutableStateOf<Boolean?>(null) }

    fun persist() {
        prefs.edit()
            .putString("zhipu_api_key", apiKey.trim())
            .putString("zhipu_base_url", baseUrl.trim().ifBlank { "https://open.bigmodel.cn/api/paas/v4" })
            .putInt("phone_agent_max_steps", maxSteps.toIntOrNull()?.coerceIn(1, 50) ?: 20)
            .apply()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "AutoGLM 手机助手",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MiMoColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        Text(
            "截屏、点击、滑动、输入全部走无线调试 ADB，不需要屏幕录制权限。执行时超级岛上可「终止」或「暂时接管」。",
            fontSize = 13.sp,
            color = MiMoColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))

        ListItem(
            headlineContent = { Text("智谱 API Key") },
            supportingContent = { Text(if (apiKey.isBlank()) "必填 — autoglm-phone 模型" else "已配置") },
            leadingContent = { Icon(Icons.Default.Key, null) }
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; persist() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("智谱开放平台 API Key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "隐藏" else "显示", fontSize = 12.sp)
                }
            },
            shape = RoundedCornerShape(12.dp)
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("API Base URL") },
            supportingContent = { Text("一般不用改") },
            leadingContent = { Icon(Icons.Default.Link, null) }
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; persist() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(12.dp)
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("最大步数") },
            supportingContent = { Text("单次任务最多循环次数（1–50）") },
            leadingContent = { Icon(Icons.Default.Numbers, null) }
        )
        OutlinedTextField(
            value = maxSteps,
            onValueChange = { maxSteps = it.filter { c -> c.isDigit() }.take(2); persist() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(12.dp)
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("无线调试 ADB") },
            supportingContent = {
                Text(
                    when (adbOk) {
                        true -> "已连接 — 手机操作通过 ADB shell 执行"
                        false -> "未连接 — 需配对后才能使用手机助手"
                        null -> "点「测试」检查当前连接"
                    }
                )
            },
            leadingContent = {
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
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !adbBusy,
                        onClick = {
                            scope.launch {
                                adbBusy = true
                                adbStatus = "测试中..."
                                adbOk = AdbManager.testConnection()
                                adbStatus = if (adbOk == true) "连接正常" else "连接失败"
                                adbBusy = false
                            }
                        }
                    ) { Text("测试") }
                }
            }
        )
        adbStatus?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = MiMoColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("去配对 / 重新配对") },
            supportingContent = { Text("开发者选项 → 无线调试 → 使用配对码配对设备") },
            leadingContent = { Icon(Icons.Default.Cable, null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            modifier = Modifier.clickable { onNavigateToWirelessAdb() }
        )
    }
}

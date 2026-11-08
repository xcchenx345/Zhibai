package com.zhibai.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.adb.AdbManager
import com.zhibai.app.ui.theme.MiMoColors
import kotlinx.coroutines.launch

@Composable
fun WirelessAdbPage(
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    var pairingPort by remember { mutableStateOf<Int?>(null) }
    var manualPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lastConnectPort by remember { mutableStateOf(AdbManager.lastConnectPort()) }

    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "无线调试 ADB",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MiMoColors.TextPrimary
        )

        if (!supported) {
            Text("需要 Android 11 及以上版本。", color = MiMoColors.Error, fontSize = 13.sp)
            return@Column
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MiMoColors.SurfaceElevated)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("使用步骤", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("1. 开发者选项 → 无线调试 → 打开开关", fontSize = 13.sp)
                Text("2. 点「使用配对码配对设备」，把弹窗里的配对码输到下面", fontSize = 13.sp)
                Text("3. 配对一次即可，之后 AI 的 run_adb 工具就拥有真实 shell 权限", fontSize = 13.sp)
                Text("提示：HyperOS 需把知白的省电策略改为「无限制」，否则后台可能被杀", fontSize = 12.sp, color = MiMoColors.TextSecondary)
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MiMoColors.SurfaceElevated)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("连接端口", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        if (lastConnectPort > 0) lastConnectPort.toString() else "未发现",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        busy = true
                        status = "测试连接中..."
                        val ok = AdbManager.testConnection()
                        status = if (ok) "连接正常，ADB shell 可用" else "连接失败，请确认无线调试已开启"
                        lastConnectPort = AdbManager.lastConnectPort()
                        busy = false
                    }
                }) {
                    Text("测试连接")
                }
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MiMoColors.SurfaceElevated)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("配对", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "正在通过 mDNS 发现配对服务..."
                                val port = AdbManager.discoverPairingPort()
                                pairingPort = port
                                status = if (port != null) "发现配对端口：$port" else "未发现配对服务，请确认无线调试已打开且弹出了配对码窗口"
                                busy = false
                            }
                        }
                    ) {
                        Text("自动发现端口")
                    }
                    if (pairingPort != null) {
                        Text("端口 $pairingPort", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }

                OutlinedTextField(
                    value = manualPort,
                    onValueChange = { manualPort = it.filter { c -> c.isDigit() } },
                    label = { Text("端口（可手动填弹窗里的）") },
                    placeholder = { Text("如 37125") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = pairCode,
                    onValueChange = { pairCode = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("6 位配对码") },
                    placeholder = { Text("123456") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Button(
                    enabled = !busy && pairCode.length == 6 && (manualPort.isNotBlank() || pairingPort != null),
                    onClick = {
                        val port = manualPort.toIntOrNull() ?: pairingPort ?: return@Button
                        scope.launch {
                            busy = true
                            status = "配对中..."
                            val result = AdbManager.pair("127.0.0.1", port, pairCode)
                            status = result.fold(
                                onSuccess = {
                                    lastConnectPort = AdbManager.discoverConnectPort() ?: -1
                                    "配对成功"
                                },
                                onFailure = { e -> "配对失败：${e.message ?: e.javaClass.simpleName}" }
                            )
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(if (busy) "处理中..." else "开始配对")
                }
            }
        }

        if (status.isNotBlank()) {
            Text(status, fontSize = 13.sp, color = MiMoColors.TextSecondary)
        }
    }
}

package com.zhibai.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhibai.app.ui.theme.MiMoColors

private const val GITHUB_ISSUE_URL = "https://github.com/xcchenx345/Zhibai/issues/new"

data class CrashAnalysis(
    val severity: Int,
    val responsibility: String,
    val summary: String,
    val suggestions: String,
    val raw: String
)

@Composable
fun CrashReportDialog(
    crashLog: String,
    onAnalyze: (suspend (String) -> CrashAnalysis?)? = null,
    onBackupChats: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    var showAnalysisDialog by remember { mutableStateOf(false) }

    val displayLog = remember(crashLog) {
        if (crashLog.length > 5000) crashLog.take(5000) + "\n\n... (截断，复制/保存为完整)"
        else crashLog
    }

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, null, tint = MiMoColors.Error, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("应用崩溃了", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("上次运行时发生异常。关闭后不会再次显示。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))

                // Crash log
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                        Text(displayLog, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", crashLog))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(4.dp)) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(13.dp))
                        Text("复制", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { saved = saveToDownloads(context, crashLog) }, enabled = !saved,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(4.dp)) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(13.dp))
                        Text(if (saved) "已存" else "保存", fontSize = 11.sp)
                    }
                    if (onBackupChats != null) {
                        OutlinedButton(onClick = { onBackupChats() }, modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(4.dp)) {
                            Icon(Icons.Default.Storage, null, modifier = Modifier.size(13.dp))
                            Text("备份", fontSize = 11.sp)
                        }
                    }
                    OutlinedButton(onClick = {
                        try {
                            val title = java.net.URLEncoder.encode("[Bug] App crash", "UTF-8")
                            val body = java.net.URLEncoder.encode("### 崩溃日志\n```\n$crashLog\n```\n", "UTF-8")
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("$GITHUB_ISSUE_URL?title=$title&body=$body")))
                        } catch (_: Exception) {
                            Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MiMoColors.Error),
                        contentPadding = PaddingValues(4.dp)) {
                        Icon(Icons.Default.Feedback, null, modifier = Modifier.size(13.dp))
                        Text("反馈", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // AI analysis button
                if (onAnalyze != null) {
                    OutlinedButton(
                        onClick = { showAnalysisDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("AI 分析崩溃原因", fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Button(onClick = { onDismiss() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    Text("关闭")
                }
            }
        }
    }

    // === AI Analysis dialog ===
    if (showAnalysisDialog && onAnalyze != null) {
        CrashAnalysisDialog(
            crashLog = crashLog,
            onAnalyze = onAnalyze,
            onDismiss = { showAnalysisDialog = false }
        )
    }
}

@Composable
private fun CrashAnalysisDialog(
    crashLog: String,
    onAnalyze: suspend (String) -> CrashAnalysis?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var analysis by remember { mutableStateOf<CrashAnalysis?>(null) }
    var analyzing by remember { mutableStateOf(true) }

    LaunchedEffect(crashLog) {
        analyzing = true
        analysis = onAnalyze(crashLog)
        analyzing = false
    }

    Dialog(
        onDismissRequest = { if (!analyzing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 500.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AI 崩溃分析", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1f))
                    if (!analyzing) {
                        IconButton(onClick = { onDismiss() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                if (analyzing) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("AI 正在分析崩溃原因...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    analysis?.let { a ->
                        // Severity + Responsibility badge
                        val sevColor = when {
                            a.severity >= 4 -> MiMoColors.Error
                            a.severity >= 3 -> MiMoColors.Warning
                            else -> MiMoColors.Success
                        }
                        Surface(shape = RoundedCornerShape(8.dp), color = sevColor.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("严重等级: ${a.severity}/5", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = sevColor)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("责任归属: ${a.responsibility}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = sevColor)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("C=自身问题  B=应用+自己  A=应用问题", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // Summary
                        Text("原因分析", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(a.summary, fontSize = 13.sp, lineHeight = 19.sp)

                        Spacer(modifier = Modifier.height(10.dp))

                        // Suggestions
                        if (a.suggestions.isNotBlank()) {
                            Text("建议操作", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(a.suggestions, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    } ?: run {
                        Text("AI 分析失败，请检查网络和 API Key 后重试。", fontSize = 13.sp, color = MiMoColors.Error)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Copy AI analysis
                        analysis?.let { a ->
                            OutlinedButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("AI Analysis", a.raw))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("复制分析", fontSize = 12.sp)
                            }
                        }
                        // Retry if failed
                        if (analysis == null) {
                            OutlinedButton(onClick = {
                                analyzing = true
                                // re-trigger via LaunchedEffect won't work, use coroutine
                            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Text("重试", fontSize = 12.sp)
                            }
                        }
                        Button(onClick = { onDismiss() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                            Text("关闭", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun saveToDownloads(context: Context, content: String): Boolean {
    val fileName = "mimo_crash_${System.currentTimeMillis()}.txt"
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download")
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { context.contentResolver.openOutputStream(it)?.use { it.write(content.toByteArray()) } }
            Toast.makeText(context, "已保存到 Download/$fileName", Toast.LENGTH_SHORT).show()
        } else {
            val file = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), fileName)
            file.writeText(content)
            Toast.makeText(context, "已保存到 ${file.absolutePath}", Toast.LENGTH_SHORT).show()
        }
        true
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        false
    }
}

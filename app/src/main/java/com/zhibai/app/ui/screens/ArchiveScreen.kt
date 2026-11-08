package com.zhibai.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.data.model.ChatSession
import com.zhibai.app.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * 档案页 — full-screen archive management.
 * Long-press to enter multi-select; left icon becomes checkbox in select mode.
 * Export (plain / encrypted) & import archived conversations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val context = LocalContext.current
    val archivedSessions = remember(sessions) {
        sessions.filter { it.archived }
            .sortedByDescending { it.lastActiveAt.coerceAtLeast(it.createdAt) }
    }

    // Multi-select state
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportEncrypt by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var needImportPassword by remember { mutableStateOf(false) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    // File pickers
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val json = viewModel.exportArchivedSessions(exportEncrypt, exportPassword)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                toastMsg = "已导出 ${archivedSessions.size} 个对话"
            } catch (e: Exception) {
                toastMsg = "导出失败: ${e.message}"
            }
            showExportDialog = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                if (json.isBlank()) { toastMsg = "文件为空"; return@rememberLauncherForActivityResult }
                // Detect encryption: try parse as plain JSON first
                val result = viewModel.importSessions(json, "")
                if (result == "NEED_PASSWORD") {
                    pendingImportJson = json
                    needImportPassword = true
                    showImportDialog = true
                } else {
                    toastMsg = result
                    showImportDialog = false
                }
            } catch (e: Exception) {
                toastMsg = "导入失败: ${e.message}"
            }
        }
    }

    // Back handler — prevent system back from exiting app
    androidx.activity.compose.BackHandler(enabled = true) {
        if (selectMode) {
            selectMode = false
            selectedIds = emptySet()
        } else {
            onBack()
        }
    }

    // Toast
    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectMode) "已选 ${selectedIds.size} 项"
                        else "档案 (${archivedSessions.size})",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectMode) {
                            selectMode = false
                            selectedIds = emptySet()
                        } else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!selectMode) {
                        // Import
                        IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Import")
                        }
                        // Export
                        IconButton(onClick = { showExportDialog = true }) {
                            Icon(Icons.Default.FileUpload, contentDescription = "Export")
                        }
                        // Select mode
                        IconButton(onClick = { selectMode = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = "Select")
                        }
                    } else {
                        // Restore selected
                        IconButton(
                            onClick = {
                                viewModel.restoreSessions(selectedIds)
                                toastMsg = "已恢复 ${selectedIds.size} 个对话"
                                selectMode = false
                                selectedIds = emptySet()
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Unarchive, contentDescription = "Restore")
                        }
                        // Delete selected
                        IconButton(
                            onClick = {
                                viewModel.deleteSessions(selectedIds)
                                toastMsg = "已删除 ${selectedIds.size} 个对话"
                                selectMode = false
                                selectedIds = emptySet()
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (archivedSessions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("暂无档案", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Text("在对话列表长按对话可归档到这里", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(archivedSessions, key = { it.id }) { sess ->
                    val isSelected = sess.id in selectedIds
                    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                            .clickable {
                                if (selectMode) {
                                    selectedIds = if (isSelected) selectedIds - sess.id else selectedIds + sess.id
                                } else {
                                    viewModel.selectSession(sess.id)
                                    onBack()
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left icon: checkbox in select mode, archive icon otherwise
                            if (selectMode) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    }
                                }
                            } else {
                                Icon(
                                    Icons.Default.Archive,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sess.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "${df.format(Date(sess.lastActiveAt.coerceAtLeast(sess.createdAt)))} · ${sess.messages.size} 条消息",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!selectMode) {
                                IconButton(onClick = { viewModel.toggleArchiveSession(sess.id) }) {
                                    Icon(Icons.Default.Unarchive, contentDescription = "Restore", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Export dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出档案") },
            text = {
                Column {
                    Text("将导出 ${archivedSessions.size} 个归档对话为 JSON 文件", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exportEncrypt, onCheckedChange = { exportEncrypt = it })
                        Text("加密导出", fontSize = 14.sp)
                    }
                    if (exportEncrypt) {
                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("密码") },
                            placeholder = { Text("输入加密密码") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (exportEncrypt && exportPassword.length < 4) {
                            toastMsg = "密码至少4位"
                        } else {
                            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                            exportLauncher.launch("zhibai_archive_$ts.json")
                        }
                    }
                ) { Text("选择保存位置") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            }
        )
    }

    // Import dialog (password prompt when needed)
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(if (needImportPassword) "输入密码" else "导入对话") },
            text = {
                if (needImportPassword) {
                    Column {
                        Text("该文件已加密，请输入密码", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importPassword,
                            onValueChange = { importPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("密码") }
                        )
                    }
                } else {
                    Text("导入后将合并到当前对话列表", fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (needImportPassword) {
                            val json = pendingImportJson ?: return@TextButton
                            val result = viewModel.importSessions(json, importPassword)
                            if (result == "NEED_PASSWORD") {
                                toastMsg = "密码错误"
                            } else {
                                toastMsg = result
                                showImportDialog = false
                                needImportPassword = false
                                pendingImportJson = null
                                importPassword = ""
                            }
                        } else {
                            showImportDialog = false
                        }
                    }
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false; needImportPassword = false }) { Text("取消") }
            }
        )
    }
}

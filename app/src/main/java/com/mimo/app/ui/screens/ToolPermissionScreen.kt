package com.mimo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.mimo.app.agent.AgentEngine
import com.mimo.app.ui.theme.MiMoColors
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPermissionScreen(
    agentEngine: AgentEngine?,
    onBack: () -> Unit
) {
    if (agentEngine == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Agent 未初始化")
        }
        return
    }

    val categories = remember { agentEngine.getToolsByCategory() }
    var permissions by remember { mutableStateOf(agentEngine.getToolPermissions()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具权限") },
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
                .verticalScroll(rememberScrollState())
        ) {
            val categoryNames = mapOf(
                "file" to "File Operations",
                "app" to "App Control",
                "system" to "System",
                "network" to "Network",
                "skill" to "Skills",
                "other" to "Other"
            )
            val categoryIcons = mapOf(
                "file" to Icons.Default.Folder,
                "app" to Icons.Default.Apps,
                "system" to Icons.Default.Code,
                "network" to Icons.Default.Language,
                "skill" to Icons.Default.Psychology,
                "other" to Icons.Default.Extension
            )

            categories.forEach { (category, tools) ->
                ListItem(
                    headlineContent = {
                        Text(
                            categoryNames[category] ?: category,
                            fontWeight = FontWeight.SemiBold,
                            color = MiMoColors.Primary
                        )
                    },
                    leadingContent = {
                        Icon(
                            categoryIcons[category] ?: Icons.Default.Extension,
                            contentDescription = null,
                            tint = MiMoColors.Primary
                        )
                    }
                )
                HorizontalDivider()

                tools.forEach { tool ->
                    val perm = permissions.find { it.toolName == tool.name }
                    var enabled by remember { mutableStateOf(perm?.enabled ?: true) }
                    var requireConfirm by remember { mutableStateOf(perm?.requireConfirmation ?: false) }

                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        ListItem(
                            headlineContent = { Text(tool.name, fontSize = 14.sp) },
                            supportingContent = {
                                Column {
                                    Text(tool.description, fontSize = 12.sp, lineHeight = 16.sp)
                                    val riskColor = when {
                                        tool.riskLevel <= 2 -> MiMoColors.Primary
                                        tool.riskLevel <= 3 -> Color(0xFFE65100)
                                        else -> MiMoColors.Error
                                    }
                                    Text(
                                        "风险等级：${tool.riskLevel}/5",
                                        fontSize = 11.sp,
                                        color = riskColor,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            },
                            trailingContent = {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { newValue ->
                                        enabled = newValue
                                        agentEngine.updateToolPermission(tool.name, enabled = newValue, requireConfirmation = null)
                                        permissions = agentEngine.getToolPermissions()
                                    }
                                )
                            }
                        )
                        if (enabled) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("需要确认", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Switch(
                                    checked = requireConfirm,
                                    onCheckedChange = { newValue ->
                                        requireConfirm = newValue
                                        agentEngine.updateToolPermission(tool.name, enabled = null, requireConfirmation = newValue)
                                        permissions = agentEngine.getToolPermissions()
                                    },
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    categories.values.flatten().forEach { tool ->
                        agentEngine.updateToolPermission(tool.name, enabled = true, requireConfirmation = false)
                    }
                    permissions = agentEngine.getToolPermissions()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("全部恢复默认")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

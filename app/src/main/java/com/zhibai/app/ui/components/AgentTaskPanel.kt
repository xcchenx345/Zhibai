package com.zhibai.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.agent.AgentTaskManager
import com.zhibai.app.agent.AgentSessionResult
import com.zhibai.app.agent.CheckTaskStatus

/**
 * Floating task checklist panel shown during Agent execution.
 * Displays tasks with status icons, progress count, and expand/collapse.
 */
@Composable
fun AgentTaskPanel(
    taskManager: AgentTaskManager,
    result: AgentSessionResult,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val tasks by taskManager.tasks.collectAsState()
    var expanded by remember { mutableStateOf(true) }

    // Auto-collapse when session ends successfully
    LaunchedEffect(result) {
        if (result == AgentSessionResult.SUCCESS || result == AgentSessionResult.FAILED) {
            expanded = false
        }
    }

    if (tasks.isEmpty()) return

    val doneCount = tasks.count { it.status == CheckTaskStatus.DONE }
    val totalCount = tasks.size
    val allDone = doneCount == totalCount

    val borderColor = when {
        result == AgentSessionResult.FAILED -> Color(0xFFD32F2F).copy(alpha = 0.4f)
        allDone && result == AgentSessionResult.SUCCESS -> Color(0xFF2196F3).copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .then(
                Modifier.padding(1.dp)
            )
    ) {
        // Header row — clickable to expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "任务清单",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "$doneCount/$totalCount",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (allDone) Color(0xFF2196F3) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            // Progress bar
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = if (totalCount > 0) doneCount.toFloat() / totalCount else 0f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (allDone) Color(0xFF2196F3)
                            else if (result == AgentSessionResult.FAILED) Color(0xFFD32F2F)
                            else MaterialTheme.colorScheme.primary
                        )
                )
            }
        }

        // Task list
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                tasks.forEach { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Status icon
                        Icon(
                            imageVector = when (task.status) {
                                CheckTaskStatus.DONE -> Icons.Default.CheckCircle
                                CheckTaskStatus.IN_PROGRESS -> Icons.Default.HourglassTop
                                CheckTaskStatus.CANCELLED -> Icons.Default.RemoveCircleOutline
                                CheckTaskStatus.PENDING -> Icons.Default.RemoveCircleOutline
                            },
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = when (task.status) {
                                CheckTaskStatus.DONE -> Color(0xFF4CAF50)
                                CheckTaskStatus.IN_PROGRESS -> Color(0xFFFF9800)
                                CheckTaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                CheckTaskStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Task ID
                        Text(
                            task.label,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp)
                        )
                        // Task title
                        Text(
                            task.title,
                            fontSize = 12.sp,
                            color = when (task.status) {
                                CheckTaskStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                CheckTaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            textDecoration = if (task.status == CheckTaskStatus.DONE)
                                androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

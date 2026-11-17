package com.zhibai.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.agent.AgentSessionResult
import com.zhibai.app.agent.AgentTaskManager
import com.zhibai.app.agent.CheckTaskStatus
import com.zhibai.app.i18n.LangManager

/**
 * Floating task checklist panel shown during Agent execution.
 *
 * Monochrome by design (知白 aesthetic): no hardcoded rainbow tints — state is
 * carried by drawn icon shape + theme colors only. Hairline progress under the
 * header, expand/collapse with chevron, scrollable list capped in height.
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

    // Completed tasks stay in the list with their checkmarks — no auto-collapse.
    if (tasks.isEmpty()) return

    val doneCount = tasks.count { it.status == CheckTaskStatus.DONE }
    val totalCount = tasks.size
    val allDone = doneCount == totalCount && totalCount > 0

    val scheme = MaterialTheme.colorScheme
    val borderColor = when {
        result == AgentSessionResult.FAILED -> scheme.error.copy(alpha = 0.45f)
        allDone && result == AgentSessionResult.SUCCESS -> scheme.primary.copy(alpha = 0.45f)
        else -> scheme.outline.copy(alpha = 0.25f)
    }

    // Progress hairline
    val targetFraction = if (totalCount > 0) doneCount.toFloat() / totalCount else 0f
    val fraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(400),
        label = "taskProgress"
    )
    val progressColor = when {
        result == AgentSessionResult.FAILED -> scheme.error
        else -> scheme.primary
    }

    // Status badge (pill) — text + theme color, no icon glyphs
    val badge: Pair<String, Color>? = when {
        isActive -> LangManager.t("agent.taskRunning", "进行中") to scheme.primary
        result == AgentSessionResult.FAILED -> LangManager.t("agent.taskFailed", "失败") to scheme.error
        allDone -> LangManager.t("agent.taskDone", "已完成") to scheme.primary
        else -> null
    }

    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevron"
    )

    Column(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface.copy(alpha = 0.96f))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
    ) {
        // Header — tap to expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                LangManager.t("agent.workPanel", "工作清单"),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "$doneCount/$totalCount",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            badge?.let { (label, tint) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                ) {
                    Text(
                        label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = tint
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) LangManager.t("chat.collapse", "收起") else LangManager.t("common.expand", "展开"),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevronAngle),
                tint = scheme.onSurfaceVariant
            )
        }

        // Hairline progress — full width, sits between header and list
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(scheme.outline.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = fraction.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(progressColor)
            )
        }

        // Task list — scrollable, height-capped so long checklists can't take over
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                tasks.forEach { task ->
                    val inProgress = task.status == CheckTaskStatus.IN_PROGRESS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (inProgress) Modifier.background(scheme.primary.copy(alpha = 0.06f))
                                else Modifier
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TaskStatusIcon(
                            status = task.status,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            task.label,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.width(30.dp)
                        )
                        Text(
                            task.title,
                            fontSize = 12.5.sp,
                            color = when (task.status) {
                                CheckTaskStatus.DONE ->
                                    scheme.onSurfaceVariant.copy(alpha = 0.55f)
                                CheckTaskStatus.CANCELLED ->
                                    scheme.onSurfaceVariant.copy(alpha = 0.4f)
                                else -> scheme.onSurface
                            },
                            textDecoration = if (task.status == CheckTaskStatus.DONE)
                                TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hand-drawn status glyph — shape carries the state, theme color carries the
 * emphasis (no filled rainbow material icons):
 *  - pending:    hollow circle
 *  - in progress: rotating arc (primary)
 *  - done:       hollow circle + check (primary)
 *  - cancelled:  hollow circle + slash (muted)
 */
@Composable
private fun TaskStatusIcon(status: CheckTaskStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val outline = scheme.outline
    val surface = scheme.surface

    when (status) {
        CheckTaskStatus.IN_PROGRESS -> {
            val transition = rememberInfiniteTransition(label = "taskSpin")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "taskSpinAngle"
            )
            Canvas(modifier = modifier) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2f + 1.dp.toPx()
                drawArc(
                    color = primary,
                    startAngle = angle,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }

        CheckTaskStatus.DONE -> Canvas(modifier = modifier) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f + 1.dp.toPx()
            val box = size.width - inset * 2
            drawArc(
                color = outline.copy(alpha = 0.55f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(box, box),
                style = Stroke(width = stroke)
            )
            val checkStroke = 2.dp.toPx()
            drawLine(
                color = primary,
                start = Offset(size.width * 0.30f, size.height * 0.52f),
                end = Offset(size.width * 0.45f, size.height * 0.67f),
                strokeWidth = checkStroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = primary,
                start = Offset(size.width * 0.45f, size.height * 0.67f),
                end = Offset(size.width * 0.72f, size.height * 0.33f),
                strokeWidth = checkStroke,
                cap = StrokeCap.Round
            )
        }

        CheckTaskStatus.CANCELLED -> Canvas(modifier = modifier) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f + 1.dp.toPx()
            val box = size.width - inset * 2
            drawArc(
                color = outline.copy(alpha = 0.4f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(box, box),
                style = Stroke(width = stroke)
            )
            drawLine(
                color = outline.copy(alpha = 0.55f),
                start = Offset(size.width * 0.32f, size.height * 0.32f),
                end = Offset(size.width * 0.68f, size.height * 0.68f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }

        CheckTaskStatus.PENDING -> Canvas(modifier = modifier) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f + 1.dp.toPx()
            drawCircle(
                color = outline.copy(alpha = 0.5f),
                radius = (size.width - inset * 2) / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = stroke)
            )
        }
    }
}

package com.zhibai.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Capsule segmented model switcher (spec: CAPSULE TAB BAR).
 *
 * Four segments — Chat / Agent / ASR / TTS — icon over label, with a darker
 * indicator pill under the active label. The pill is tap-to-jump and
 * press-to-drag (strict 1:1 while dragging, velocity-driven squash, spring
 * snap on release). Landing on Chat or Agent pops the model list for that
 * category; ASR and TTS switch directly.
 */
@Composable
fun CapsuleModelBar(
    currentModel: String,
    modelIds: List<String>,
    agentEnabled: Boolean,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val segments = listOf("Chat", "Agent", "ASR", "TTS")
    val selectedIndex = when {
        currentModel.contains("agent") -> 1
        currentModel == "asr" -> 2
        currentModel.startsWith("tts") -> 3
        else -> 0
    }

    val chatModels = modelIds.filter { it.startsWith("mimo-v") || it.startsWith("local:") }
    val agentModels = if (agentEnabled) chatModels.map { "$it-agent" } else emptyList()

    // ---- geometry (px, filled at layout time) ----
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val segCount = segments.size
    fun segCenterPx(i: Int): Float =
        if (trackWidthPx <= 0f) 0f else trackWidthPx * (i + 0.5f) / segCount

    // ---- pill position (px) + squash ----
    val pillX = remember { Animatable(0f) }
    var pillInited by remember { mutableStateOf(false) }
    var hoverIndex by remember { mutableIntStateOf(selectedIndex) }
    var pressedIndex by remember { mutableIntStateOf(-1) }
    var menuFor by remember { mutableIntStateOf(-1) } // 0=Chat 1=Agent popup open

    // Velocity-driven squash: the faster the pill moves the flatter it gets
    // (k from the spec; velocities are dp/s-ish in px, clamped to a visible band).
    val speed = abs(pillX.velocity)
    val pillScaleY = (1f - speed * 0.00022f).coerceIn(0.72f, 1f)
    val pillScaleX = 1f / pillScaleY

    fun land(index: Int) {
        when (index) {
            0, 1 -> menuFor = index
            2 -> onSelectModel("asr")
            3 -> onSelectModel("tts")
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth(0.94f)
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
    ) {
        // ---- shell ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                    RoundedCornerShape(28.dp)
                )
        )

        // ---- segments (icon + label) ----
        Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            segments.forEachIndexed { i, label ->
                val active = i == hoverIndex
                val pressed = i == pressedIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .scale(if (pressed) 0.92f else 1f)
                ) {
                    Icon(
                        imageVector = when (i) {
                            0 -> Icons.Default.Chat
                            1 -> Icons.Default.SmartToy
                            2 -> Icons.Default.Mic
                            else -> Icons.Default.VolumeUp
                        },
                        contentDescription = label,
                        modifier = Modifier.size(20.dp),
                        tint = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // ---- indicator pill ----
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 40.dp)
                .graphicsLayer {
                    translationX = pillX.value - 10.dp.toPx()
                    scaleX = pillScaleX
                    scaleY = pillScaleY
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                }
                .size(width = 20.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f))
        )

        // ---- hit layer: whole track is tappable + draggable ----
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(trackWidthPx) {
                    if (trackWidthPx <= 0f) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressedIndex = ((down.position.x / trackWidthPx) * segCount)
                            .toInt().coerceIn(0, segCount - 1)
                        var lastX = down.position.x
                        var lastT = System.currentTimeMillis()
                        var moved = false
                        var v = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val x = change.position.x
                            val now = System.currentTimeMillis()
                            val dt = ((now - lastT) / 1000f).coerceAtLeast(0.008f)
                            v = (x - lastX) / dt
                            if (abs(x - down.position.x) > 6f) moved = true
                            if (moved) {
                                // 1:1 tracking while dragging — no easing, glued to finger.
                                // Allow a small overshoot past the track edges.
                                val lo = -8.dp.toPx()
                                val hi = trackWidthPx + 8.dp.toPx()
                                val clamped = x.coerceIn(lo, hi)
                                scope.launch { pillX.snapTo(clamped) }
                                hoverIndex = ((x / trackWidthPx) * segCount).toInt().coerceIn(0, segCount - 1)
                            }
                            lastX = x
                            lastT = now
                        }
                        pressedIndex = -1
                        val target = if (moved) {
                            hoverIndex
                        } else {
                            ((down.position.x / trackWidthPx) * segCount).toInt().coerceIn(0, segCount - 1)
                        }
                        hoverIndex = target
                        scope.launch {
                            pillX.animateTo(
                                segCenterPx(target),
                                animationSpec = spring(
                                    dampingRatio = 0.72f,
                                    stiffness = if (moved) Spring.StiffnessMediumLow else Spring.StiffnessMedium
                                )
                            )
                        }
                        land(target)
                    }
                }
        )

        // ---- model list popup for Chat / Agent ----
        if (menuFor in 0..1) {
            val models = if (menuFor == 0) chatModels else agentModels
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menuFor = -1 },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(if (menuFor == 1) "未启用 Agent" else "无可用模型", fontSize = 13.sp) },
                        onClick = { menuFor = -1 }
                    )
                }
                models.forEach { id ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = modelLabel(id),
                                fontSize = 13.sp,
                                fontWeight = if (id == currentModel) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSelectModel(id)
                            menuFor = -1
                        }
                    )
                }
            }
        }
    }
}

private fun modelLabel(id: String): String = when {
    id.startsWith("local:") && id.endsWith("-agent") -> "本地 Agent · ${id.removePrefix("local:").removeSuffix("-agent")}"
    id.startsWith("local:") -> "本地 · ${id.removePrefix("local:")}"
    id.endsWith("-agent") -> "Agent · ${id.removeSuffix("-agent").removePrefix("mimo-")}"
    id.contains("ultraspeed") -> "UltraSpeed"
    id.contains("pro") -> "Pro"
    id.contains("flash") -> "Flash"
    else -> id.removePrefix("mimo-")
}

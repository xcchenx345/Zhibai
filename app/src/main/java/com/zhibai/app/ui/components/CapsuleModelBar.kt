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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Capsule segmented model switcher — the Android-clock style: one big white
 * capsule shell, four bold icon-over-label segments, and the selection shown as
 * a gray rounded slab behind the whole icon+label block (not a dot under it).
 *
 * Interactions: tap-to-jump with spring, drag the slab 1:1 with velocity squash
 * and snap-to-nearest on release. Landing on Chat / Agent pops the model list;
 * ASR / TTS switch directly.
 */
@Composable
fun CapsuleModelBar(
    currentModel: String,
    modelIds: List<String>,
    agentEnabled: Boolean,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
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

    // ---- geometry (px, filled at layout) ----
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val segCount = segments.size
    val density = LocalDensity.current
    val insetPx = with(density) { 6.dp.toPx() }
    fun segCenterPx(i: Int): Float =
        if (trackWidthPx <= 0f) 0f else trackWidthPx * (i + 0.5f) / segCount
    fun slabWidthPx(): Float =
        if (trackWidthPx <= 0f) 0f else trackWidthPx / segCount - insetPx * 2

    // ---- indicator slab position + squash ----
    val slabX = remember { Animatable(0f) }
    var slabInited by remember { mutableStateOf(false) }
    var hoverIndex by remember { mutableIntStateOf(selectedIndex) }
    var pressedIndex by remember { mutableIntStateOf(-1) }
    var menuFor by remember { mutableIntStateOf(-1) }

    val speed = abs(slabX.velocity)
    val slabScaleY = (1f - speed * 0.00018f).coerceIn(0.78f, 1f)
    val slabScaleX = 1f / slabScaleY

    fun land(index: Int) {
        when (index) {
            0, 1 -> menuFor = index
            2 -> onSelectModel("asr")
            3 -> onSelectModel("tts")
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth(0.96f)
            .onSizeChanged {
                trackWidthPx = it.width.toFloat()
                if (!slabInited && trackWidthPx > 0f) {
                    slabInited = true
                    scope.launch { slabX.snapTo(segCenterPx(selectedIndex)) }
                }
            }
    ) {
        // ---- shell: big rounded capsule, hairline border ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                    RoundedCornerShape(32.dp)
                )
        )

        // ---- selection slab (gray rounded block behind icon+label) ----
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = slabX.value - slabWidthPx() / 2f
                    scaleX = slabScaleX
                    scaleY = slabScaleY
                }
                .width(with(density) { slabWidthPx().toDp() })
                .fillMaxHeight(0.82f)
                .padding(vertical = 0.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
        )

        // ---- segments: big filled icon over bold label ----
        Row(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            segments.forEachIndexed { i, label ->
                val active = i == hoverIndex
                val pressed = i == pressedIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .scale(if (pressed) 0.93f else 1f)
                ) {
                    Icon(
                        imageVector = when (i) {
                            0 -> Icons.AutoMirrored.Filled.Chat
                            1 -> Icons.Default.SmartToy
                            2 -> Icons.Default.Mic
                            else -> Icons.Default.VolumeUp
                        },
                        contentDescription = label,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // ---- hit layer: whole track tappable + draggable ----
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
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val x = change.position.x
                            if (abs(x - down.position.x) > 6f) moved = true
                            if (moved) {
                                // 1:1 tracking — glued to the finger
                                val lo = -8.dp.toPx()
                                val hi = trackWidthPx + 8.dp.toPx()
                                val clamped = x.coerceIn(lo, hi)
                                scope.launch { slabX.snapTo(clamped) }
                                hoverIndex = ((x / trackWidthPx) * segCount)
                                    .toInt().coerceIn(0, segCount - 1)
                            }
                            lastX = x
                            lastT = System.currentTimeMillis()
                        }
                        pressedIndex = -1
                        val target = if (moved) hoverIndex
                        else ((down.position.x / trackWidthPx) * segCount)
                            .toInt().coerceIn(0, segCount - 1)
                        hoverIndex = target
                        scope.launch {
                            slabX.animateTo(
                                segCenterPx(target),
                                animationSpec = spring(
                                    dampingRatio = 0.75f,
                                    stiffness = if (moved) Spring.StiffnessMediumLow
                                    else Spring.StiffnessMedium
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

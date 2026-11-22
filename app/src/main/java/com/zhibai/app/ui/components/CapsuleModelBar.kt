package com.zhibai.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.zhibai.app.i18n.LangManager
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Capsule segmented model switcher — Apple's capsule language (UISegmentedControl /
 * iPadOS floating tab bar): everything is a true capsule (radius = half the height,
 * concentric rings), a translucent gray track holds an elevated white capsule thumb,
 * and the thumb springs between segments with a squash while dragged.
 *
 * Landing on Chat / Agent pops the model list; ASR / TTS switch directly.
 */
@Composable
fun CapsuleModelBar(
    currentModel: String,
    modelIds: List<String>,
    agentEnabled: Boolean,
    onSelectModel: (String) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    val segments = listOf(
        LangManager.t("model.chat", "Chat"),
        LangManager.t("model.agent", "Agent"),
        LangManager.t("model.asr", "ASR"),
        LangManager.t("model.tts", "TTS")
    )
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
    val insetPx = with(density) { 5.dp.toPx() }
    fun segCenterPx(i: Int): Float =
        if (trackWidthPx <= 0f) 0f else trackWidthPx * (i + 0.5f) / segCount
    fun slabWidthPx(): Float =
        if (trackWidthPx <= 0f) 0f else trackWidthPx / segCount - insetPx * 2

    // Apple segmented-control palette: gray track, elevated light thumb.
    val trackColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    val thumbColor = if (isDark) Color(0xFF3A3A3C) else Color.White

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
        // ---- shell: liquid-glass capsule track (Kyant0/AndroidLiquidGlass) ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(percent = 50))
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(percent = 50) },
                    effects = {
                        blur(2f.dp.toPx())
                        lens(12f.dp.toPx(), 24f.dp.toPx())
                    },
                    onDrawSurface = { drawRect(trackColor) }
                )
        )

        // ---- selection thumb: elevated white capsule (concentric with the track) ----
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = slabX.value - slabWidthPx() / 2f
                    scaleX = slabScaleX
                    scaleY = slabScaleY
                    shadowElevation = 6f
                    shape = RoundedCornerShape(percent = 50)
                    clip = true
                }
                .width(with(density) { slabWidthPx().toDp() })
                .fillMaxHeight(0.84f)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(percent = 50) },
                    effects = {
                        blur(3f.dp.toPx())
                        lens(16f.dp.toPx(), 28f.dp.toPx())
                    },
                    onDrawSurface = { drawRect(thumbColor.copy(alpha = 0.92f)) }
                )
        ) {
            // Liquid-Glass style specular: a faint bright line on the thumb's top edge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(Color.White.copy(alpha = if (isDark) 0.18f else 0.55f))
            )
        }

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
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
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

        // ---- model list popup for Chat / Agent (AndroidLiquidGlass card) ----
        if (menuFor in 0..1) {
            val models = if (menuFor == 0) chatModels else agentModels
            Popup(
                alignment = Alignment.TopCenter,
                onDismissRequest = { menuFor = -1 },
                properties = PopupProperties(focusable = true)
            ) {
                Column(
                    modifier = Modifier
                        .padding(top = 72.dp)
                        .width(240.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(22.dp) },
                            effects = {
                                blur(6f.dp.toPx())
                                lens(18f.dp.toPx(), 26f.dp.toPx())
                            },
                            onDrawSurface = {
                                drawRect(
                                    if (isDark) Color(0xFF1C1C1E).copy(alpha = 0.62f)
                                    else Color.White.copy(alpha = 0.62f)
                                )
                            }
                        )
                        .padding(vertical = 6.dp)
                ) {
                    if (models.isEmpty()) {
                        Text(
                            if (menuFor == 1) LangManager.t("model.agentDisabled", "未启用 Agent")
                            else LangManager.t("model.none", "无可用模型"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    models.forEach { id ->
                        val selected = id == currentModel
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectModel(id); menuFor = -1 }
                                .padding(horizontal = 16.dp, vertical = 11.dp)
                        ) {
                            Text(
                                text = modelLabel(id),
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun modelLabel(id: String): String = when {
    id.startsWith("local:") && id.endsWith("-agent") ->
        LangManager.t("model.localAgent", "本地 Agent · {name}")
            .replace("{name}", id.removePrefix("local:").removeSuffix("-agent"))
    id.startsWith("local:") ->
        LangManager.t("model.local", "本地 · {name}").replace("{name}", id.removePrefix("local:"))
    id.endsWith("-agent") ->
        LangManager.t("model.agentNamed", "Agent · {name}")
            .replace("{name}", id.removeSuffix("-agent").removePrefix("mimo-"))
    id.contains("ultraspeed") -> "UltraSpeed"
    id.contains("pro") -> "Pro"
    id.contains("flash") -> "Flash"
    else -> id.removePrefix("mimo-")
}

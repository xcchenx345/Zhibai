package com.zhibai.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen continuous snowfall with input-bar pile.
 * - Snow covers the entire screen (top to bottom)
 * - Color adapts: white in dark mode, dark in light mode
 * - Pile on input bar grows over time; clears on [pileRequest]
 */
@Composable
fun SnowCelebration(
    active: Boolean,
    pileRequest: Int,
    modifier: Modifier = Modifier
) {
    if (!active) return

    val isDark = isSystemInDarkTheme()
    val flakeColor = if (isDark) Color.White else Color(0xFF333333)
    val pileColor = if (isDark) Color.White else Color(0xFF555555)

    val infiniteTransition = rememberInfiniteTransition(label = "snowFall")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fallProgress"
    )

    // Accumulated pile height (grows continuously)
    var pilePx by remember { mutableFloatStateOf(0f) }
    var lastTick by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }

    // Auto-stop snow after 15 seconds
    LaunchedEffect(active) {
        lastTick = System.currentTimeMillis()
        elapsed = 0
        while (active && elapsed < 15000) {
            kotlinx.coroutines.delay(1000)
            elapsed += 1000
            val now = System.currentTimeMillis()
            val dt = (now - lastTick) / 1000f
            lastTick = now
            pilePx += dt * 10f
            pilePx = pilePx.coerceAtMost(100f)
        }
    }

    LaunchedEffect(pileRequest) {
        if (pileRequest > 0) {
            val start = pilePx
            val startTime = System.currentTimeMillis()
            while (pilePx > 0f) {
                val t = (System.currentTimeMillis() - startTime) / 400f
                pilePx = (start * (1f - t)).coerceAtLeast(0f)
                kotlinx.coroutines.delay(16)
            }
        }
    }

    val snowflakes = remember {
        List(120) {
            SnowflakeData(
                x = Random.nextFloat(),
                startY = Random.nextFloat(), // spread across full screen height
                speed = 0.2f + Random.nextFloat() * 0.8f,
                size = 1.5f + Random.nextFloat() * 4f,
                drift = (Random.nextFloat() - 0.5f) * 0.3f,
                phase = Random.nextFloat() * 6.28f,
                alpha = 0.3f + Random.nextFloat() * 0.7f
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val inputBarTop = h * 0.88f
        val pileH = pilePx

        // Snow pile on input bar
        if (pileH > 0.5f) {
            drawSnowPile(w, inputBarTop, pileH, pileColor)
        }

        // Falling snowflakes — full screen
        for (flake in snowflakes) {
            // Each flake starts at a random vertical position and wraps around
            val rawY = flake.startY + progress * flake.speed
            val yPos = (rawY % 1f) * h
            val xOffset = sin((progress * 3f + flake.phase).toDouble()).toFloat() * flake.drift * w * 0.1f
            val xPos = flake.x * w + xOffset
            val pileSurface = inputBarTop - pileH
            val alpha = flake.alpha * (0.4f + 0.6f * (1f - yPos / h))

            // Don't draw flakes that have landed on the pile
            if (yPos < pileSurface) {
                drawCircle(
                    color = flakeColor.copy(alpha = alpha),
                    radius = flake.size,
                    center = Offset(xPos, yPos)
                )
            }
        }
    }
}

private data class SnowflakeData(
    val x: Float,
    val startY: Float,
    val speed: Float,
    val size: Float,
    val drift: Float,
    val phase: Float,
    val alpha: Float
)

private fun DrawScope.drawSnowPile(width: Float, topY: Float, maxH: Float, color: Color) {
    val pile = Path().apply {
        moveTo(0f, topY)
        val steps = 24
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = t * width
            val wave = sin(t * 6.28f * 2.5).toFloat() * maxH * 0.15f
            val baseY = topY - maxH + wave
            lineTo(x, baseY)
        }
        lineTo(width, topY)
        close()
    }
    drawPath(pile, color = color.copy(alpha = 0.92f), style = Fill)
}

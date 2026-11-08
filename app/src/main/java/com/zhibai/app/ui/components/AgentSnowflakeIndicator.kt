package com.zhibai.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zhibai.app.agent.AgentSessionResult
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotating snowflake indicator shown above the input bar during Agent execution.
 *
 * States:
 * - IN_PROGRESS: slowly rotating snowflake
 * - SUCCESS: snowflakes fall down (celebration)
 * - FAILED: snowflake shakes / cracks
 * - NONE: invisible
 */
@Composable
fun AgentSnowflakeIndicator(
    result: AgentSessionResult,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isActive && result == AgentSessionResult.NONE) return

    val infiniteTransition = rememberInfiniteTransition(label = "agentSnow")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val shake by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (result == AgentSessionResult.FAILED) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shake"
    )
    val fallProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (result == AgentSessionResult.SUCCESS) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fall"
    )

    val isFailed = result == AgentSessionResult.FAILED
    val isSuccess = result == AgentSessionResult.SUCCESS

    val baseColor = when {
        isFailed -> Color(0xFFD32F2F)  // red
        isSuccess -> Color(0xFF2196F3) // blue
        else -> Color(0xFF9E9E9E)      // gray while running
    }

    Box(
        modifier = modifier.height(36.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val armLen = size.width * 0.38f
            val strokeW = 2.dp.toPx()

            if (isSuccess) {
                // Snowfall celebration — small dots falling
                for (i in 0 until 8) {
                    val seed = i * 47f
                    val x = cx + (sin(seed.toDouble()).toFloat() * armLen * 1.2f)
                    val y = ((fallProgress + i * 0.12f) % 1f) * size.height
                    val alpha = (1f - fallProgress).coerceIn(0.3f, 1f)
                    drawCircle(
                        color = Color(0xFF2196F3).copy(alpha = alpha * 0.7f),
                        radius = 2.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
                // Still draw the snowflake in center
            }

            if (isFailed) {
                // Shaking snowflake with crack lines
                val offsetX = shake * 3.dp.toPx() * (if ((rotation.toInt() % 2 == 0)) 1f else -1f)
                drawSnowflake(cx + offsetX, cy, armLen, strokeW, baseColor, rotation)
                // Crack lines
                drawLine(
                    color = Color(0xFFD32F2F),
                    start = Offset(cx - armLen * 0.5f + offsetX, cy - armLen * 0.3f),
                    end = Offset(cx + armLen * 0.3f + offsetX, cy + armLen * 0.5f),
                    strokeWidth = strokeW * 0.8f,
                    cap = StrokeCap.Round
                )
            } else {
                // Normal rotating snowflake
                drawSnowflake(cx, cy, armLen, strokeW, baseColor, rotation)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSnowflake(
    cx: Float, cy: Float, armLen: Float, strokeW: Float,
    color: Color, rotationDeg: Float
) {
    val rot = Math.toRadians(rotationDeg.toDouble())
    for (arm in 0 until 6) {
        val angle = rot + arm * Math.PI / 3
        val dx = cos(angle).toFloat() * armLen
        val dy = sin(angle).toFloat() * armLen
        // Main arm
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(cx + dx, cy + dy),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        // Branch at 60% point
        val bx = cx + dx * 0.6f
        val by = cy + dy * 0.6f
        val branchLen = armLen * 0.3f
        val branchAngle1 = angle + 0.5
        val branchAngle2 = angle - 0.5
        drawLine(
            color = color,
            start = Offset(bx, by),
            end = Offset(bx + cos(branchAngle1).toFloat() * branchLen, by + sin(branchAngle1).toFloat() * branchLen),
            strokeWidth = strokeW * 0.7f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(bx, by),
            end = Offset(bx + cos(branchAngle2).toFloat() * branchLen, by + sin(branchAngle2).toFloat() * branchLen),
            strokeWidth = strokeW * 0.7f,
            cap = StrokeCap.Round
        )
    }
}

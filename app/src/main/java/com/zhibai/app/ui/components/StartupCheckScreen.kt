package com.zhibai.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.zhibai.app.R

/**
 * Startup dependency check screen:
 * logo + spinning ring + "正在检查xxx (xx%)".
 * Progress is cosmetic (心理安慰) — real work is the sequential checks in MainActivity.
 */
@Composable
fun StartupCheckScreen(
    label: String,
    progress: Float, // 0f..1f
    modifier: Modifier = Modifier
) {
    val pct = (progress * 100).toInt().coerceIn(0, 100)
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.mimo_logo_foreground),
                contentDescription = null,
                modifier = Modifier.size(120.dp)
            )
            Spacer(modifier = Modifier.height(36.dp))
            val primary = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.size(36.dp)) {
                val stroke = 3.dp.toPx()
                val r = size.minDimension / 2 - stroke / 2
                drawCircle(
                    color = Color.Gray.copy(alpha = 0.25f),
                    radius = r,
                    style = Stroke(width = stroke)
                )
                drawArc(
                    color = primary,
                    startAngle = angle,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "正在检查$label（$pct%）",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

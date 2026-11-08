package com.zhibai.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Parse "#AARRGGBB" or "#RRGGBB" hex string to Color. */
internal fun parseHexColor(hex: String): Color? {
    val s = hex.trim().removePrefix("#")
    return try {
        when (s.length) {
            6 -> Color(android.graphics.Color.parseColor("#FF$s"))
            8 -> Color(android.graphics.Color.parseColor("#$s"))
            else -> null
        }
    } catch (_: Exception) { null }
}

/**
 * Floating container for the chat input area.
 *
 * Backdrop mask around the rounded input (full-width box):
 * - Outside the TOP rounded corners (square cutouts): transparent — message text shows through.
 * - Outside the BOTTOM rounded corners + side margins + everything below: opaque — text hidden.
 *
 * Corner radius is fixed at 32dp (no user setting).
 */
@Composable
fun GlassInputBar(
    isGlass: Boolean,
    blurColorHex: String,
    shadowEnabled: Boolean,
    isTyping: Boolean,
    blurIntensity: Int = 50,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()

    if (!isGlass) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            content()
        }
        return
    }

    // "auto" follows system light/dark; otherwise parse hex (fallback tint).
    val tint = when {
        blurColorHex.equals("auto", ignoreCase = true) ->
            if (isDark) Color(0xB01E1E1E) else Color(0xB0FFFFFF)
        else -> parseHexColor(blurColorHex)
            ?: if (isDark) Color(0x991E1E1E) else Color(0x99FFFFFF)
    }

    // Input body is fully opaque — no message text through the bar itself.
    val animatedTint by animateColorAsState(
        targetValue = tint.copy(alpha = 1f),
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "glassTint"
    )

    val shape: Shape = RoundedCornerShape(32.dp)
    val maskColor = MaterialTheme.colorScheme.background
    val cornerR = 32.dp
    val insetH = 10.dp
    val insetV = 8.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Full-width mask: side margins + input bbox with square bottom / round top
            .drawBehind {
                val r = cornerR.toPx()
                val left = insetH.toPx()
                val right = size.width - insetH.toPx()
                val top = insetV.toPx()
                if (right <= left || r <= 0f) return@drawBehind

                // Side margins — fully opaque (full height).
                drawRect(maskColor, size = Size(left, size.height))
                drawRect(
                    maskColor,
                    topLeft = Offset(right, 0f),
                    size = Size(size.width - right, size.height)
                )

                // Input bounding box: rounded TOP corners (cutouts stay transparent),
                // square BOTTOM corners (cutouts filled opaque).
                val path = Path().apply {
                    moveTo(left, size.height)
                    lineTo(left, top + r)
                    arcTo(
                        rect = Rect(left, top, left + 2f * r, top + 2f * r),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(right - r, top)
                    arcTo(
                        rect = Rect(right - 2f * r, top, right, top + 2f * r),
                        startAngleDegrees = 270f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(right, size.height)
                    close()
                }
                drawPath(path, maskColor)
            }
            .padding(horizontal = insetH, vertical = insetV)
            .clip(shape)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = animatedTint,
            content = content
        )
    }
}

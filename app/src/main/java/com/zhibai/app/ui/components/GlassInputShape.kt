package com.zhibai.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Superellipse ("squircle") rounded-rect — iOS-style continuous-curvature corner.
 * [exponent] = 2 is a normal circular corner; higher = flatter, more "squircle".
 * We use n=3 for the "200万模式" look.
 */
class SuperellipseShape(
    private val cornerRadius: Dp = 20.dp,
    private val exponent: Float = 3f
) : androidx.compose.ui.graphics.Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val r = with(density) { cornerRadius.toPx() }
            .coerceAtMost(minOf(size.width, size.height) / 2f)
        val w = size.width
        val h = size.height
        // invN = 2/n; when n=2 → invN=1 (circle), n=3 → invN≈0.667 (squircle)
        val invN = (2.0 / exponent).toFloat()

        // |sin t|^invN and |cos t|^invN for t in [0, π/2]
        fun sp(t: Float): Pair<Float, Float> {
            val s = abs(sin(t.toDouble())).pow(invN.toDouble()).toFloat()
            val c = abs(cos(t.toDouble())).pow(invN.toDouble()).toFloat()
            return s to c
        }

        val path = Path()
        val step = (Math.PI / 2).toFloat() / 20f

        // Start: top edge, after top-left corner
        path.moveTo(r, 0f)

        // ── Top edge → top-right corner: (w-r, 0) → (w, r) ──
        path.lineTo(w - r, 0f)
        for (i in 1..20) {
            val (s, c) = sp(step * i)
            path.lineTo(w - r + r * s, r - r * c)
        }

        // ── Right edge → bottom-right corner: (w, h-r) → (w-r, h) ──
        path.lineTo(w, h - r)
        for (i in 1..20) {
            val (s, c) = sp(step * i)
            path.lineTo(w - r + r * c, h - r + r * s)
        }

        // ── Bottom edge → bottom-left corner: (r, h) → (0, h-r) ──
        path.lineTo(r, h)
        for (i in 1..20) {
            val (s, c) = sp(step * i)
            path.lineTo(r - r * s, h - r + r * c)
        }

        // ── Left edge → top-left corner: (0, r) → (r, 0) ──
        path.lineTo(0f, r)
        for (i in 1..20) {
            val (s, c) = sp(step * i)
            path.lineTo(r - r * c, r - r * s)
        }

        path.close()
        return Outline.Generic(path)
    }
}

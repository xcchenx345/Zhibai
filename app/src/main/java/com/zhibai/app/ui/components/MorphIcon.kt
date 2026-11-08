package com.zhibai.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stroke-icon morphing in the spirit of morphicons (MIT):
 * normalize → resample → match → Procrustes align → polar interpolate → spring.
 *
 * Icons are plain point data on a 24×24 grid — no VectorAsset / PathParser needed.
 */

/** One stroke icon: open or closed polylines in 24×24 design space. */
data class StrokeIcon(
    val subpaths: List<List<Offset>>,
    val closed: List<Boolean> = List(subpaths.size) { false }
) {
    companion object {
        /** + */
        val Plus = StrokeIcon(
            subpaths = listOf(
                listOf(Offset(12f, 5f), Offset(12f, 19f)),
                listOf(Offset(5f, 12f), Offset(19f, 12f))
            ),
            closed = listOf(false, false)
        )

        /** × — Plus rotated 45°; Procrustes should recover that rotation. */
        val Close = StrokeIcon(
            subpaths = listOf(
                listOf(Offset(6.2f, 6.2f), Offset(17.8f, 17.8f)),
                listOf(Offset(17.8f, 6.2f), Offset(6.2f, 17.8f))
            ),
            closed = listOf(false, false)
        )

        /** Microphone: slim capsule head + U-shaped stand (Material-like). */
        val Mic = StrokeIcon(
            subpaths = listOf(
                // capsule head — slimmer, taller proportions
                buildCapsule(cx = 12f, cy = 8f, hw = 2.8f, top = 2.5f, bottom = 12f, r = 2.8f),
                // U stand: left → bottom → right (open arc)
                buildMicUStand(),
                // stem + base bar
                listOf(
                    Offset(12f, 16.5f),
                    Offset(12f, 20f),
                    Offset(8.5f, 20f),
                    Offset(15.5f, 20f)
                )
            ),
            closed = listOf(true, false, false)
        )

        /** Check mark (open polyline). */
        val Check = StrokeIcon(
            subpaths = listOf(
                listOf(
                    Offset(5.5f, 12.5f),
                    Offset(9.8f, 16.8f),
                    Offset(18.5f, 7.5f)
                )
            ),
            closed = listOf(false)
        )

        /** Paper-plane send (closed outline). */
        val Send = StrokeIcon(
            subpaths = listOf(
                listOf(
                    Offset(3.5f, 12f),
                    Offset(20.5f, 4.5f),
                    Offset(20.5f, 19.5f),
                    Offset(12.5f, 14.2f),
                    Offset(8.5f, 18.5f),
                    Offset(8.5f, 13.2f),
                    Offset(3.5f, 12f)
                )
            ),
            closed = listOf(true)
        )

        /** Rounded stop square (for send → stop morph). */
        val Stop = StrokeIcon(
            subpaths = listOf(
                roundedRectPts(l = 7f, t = 7f, r = 17f, b = 17f, rad = 3.5f)
            ),
            closed = listOf(true)
        )

        /** Sample a rounded rectangle outline (closed). */
        private fun roundedRectPts(l: Float, t: Float, r: Float, b: Float, rad: Float): List<Offset> {
            val pts = mutableListOf<Offset>()
            val n = 6
            val pi = Math.PI
            fun corner(cx: Float, cy: Float, a0: Double, a1: Double) {
                for (i in 0..n) {
                    val a = a0 + (a1 - a0) * i / n
                    pts.add(Offset(cx + (rad * cos(a)).toFloat(), cy + (rad * sin(a)).toFloat()))
                }
            }
            // y-down screen: 180°=left, 270°=top, 0°=right, 90°=bottom
            corner(l + rad, t + rad, pi, 1.5 * pi)        // TL
            corner(r - rad, t + rad, 1.5 * pi, 2.0 * pi)  // TR
            corner(r - rad, b - rad, 0.0, 0.5 * pi)       // BR
            corner(l + rad, b - rad, 0.5 * pi, pi)        // BL
            pts.add(pts[0])
            return pts
        }

        private fun buildCapsule(
            cx: Float, hw: Float, top: Float, bottom: Float, r: Float, cy: Float = 0f
        ): List<Offset> {
            val pts = mutableListOf<Offset>()
            // top semicircle, center (cx, top+r): left → right
            for (i in 0..16) {
                val a = Math.PI + Math.PI * i / 16.0
                pts.add(Offset(cx + (r * cos(a)).toFloat(), (top + r) + (r * sin(a)).toFloat()))
            }
            // right side down to bottom arc
            pts.add(Offset(cx + hw, bottom - r))
            // bottom semicircle, center (cx, bottom-r): right → left
            for (i in 0..16) {
                val a = Math.PI * i / 16.0
                pts.add(Offset(cx + (r * cos(a)).toFloat(), (bottom - r) + (r * sin(a)).toFloat()))
            }
            // left side back up
            pts.add(Offset(cx - hw, top + r))
            return pts
        }

        private fun buildMicStand(): List<Offset> {
            val pts = mutableListOf<Offset>()
            pts.add(Offset(12f, 12.5f))
            pts.add(Offset(12f, 17f))
            // base arc under the stem (screen y grows downward)
            for (i in 0..16) {
                val ang = Math.PI + Math.PI * i / 16.0
                pts.add(
                    Offset(
                        12f + (4.5f * cos(ang)).toFloat(),
                        17f + (2.2f * sin(ang)).toFloat()
                    )
                )
            }
            return pts
        }

        /** U-shaped mic stand arc: left rim → bottom → right rim. */
        private fun buildMicUStand(): List<Offset> {
            val pts = mutableListOf<Offset>()
            val cx = 12f
            val cy = 12f
            val r = 5.2f
            // angle π (left) → 0 (right) through π/2 (bottom, y-down screen)
            for (i in 0..24) {
                val t = i / 24.0
                val ang = Math.PI * (1.0 - t)
                pts.add(
                    Offset(
                        cx + (r * cos(ang)).toFloat(),
                        cy + (r * sin(ang)).toFloat()
                    )
                )
            }
            return pts
        }
    }
}

// ----- pipeline -----

private const val SAMPLES = 64

/** Evenly resample a polyline by arc length to [n] points (corners preserved as samples). */
private fun resample(pts: List<Offset>, n: Int, closed: Boolean): List<Offset> {
    if (pts.size < 2) return List(n) { pts.firstOrNull() ?: Offset.Zero }
    val src = if (closed && pts.first() != pts.last()) pts + pts.first() else pts
    val segLen = FloatArray(src.size - 1)
    var total = 0f
    for (i in 0 until src.size - 1) {
        segLen[i] = hypot(src[i + 1].x - src[i].x, src[i + 1].y - src[i].y)
        total += segLen[i]
    }
    if (total <= 0f) return List(n) { src[0] }
    val out = ArrayList<Offset>(n)
    // Always emit exactly n points: closed walks [0, n), open includes both endpoints.
    if (closed) {
        for (s in 0 until n) {
            val target = total * s / n
            var acc = 0f
            var i = 0
            while (i < segLen.size - 1 && acc + segLen[i] < target) {
                acc += segLen[i]
                i++
            }
            val t = if (segLen[i] > 0f) (target - acc) / segLen[i] else 0f
            out.add(
                Offset(
                    src[i].x + (src[i + 1].x - src[i].x) * t,
                    src[i].y + (src[i + 1].y - src[i].y) * t
                )
            )
        }
    } else {
        for (s in 0 until n) {
            val target = total * s / (n - 1).coerceAtLeast(1)
            var acc = 0f
            var i = 0
            while (i < segLen.size - 1 && acc + segLen[i] < target) {
                acc += segLen[i]
                i++
            }
            val t = if (segLen[i] > 0f) (target - acc) / segLen[i] else 0f
            out.add(
                Offset(
                    src[i].x + (src[i + 1].x - src[i].x) * t,
                    src[i].y + (src[i + 1].y - src[i].y) * t
                )
            )
        }
    }
    return out
}

private class Align(val theta: Float, val scale: Float, val err: Float)

/**
 * 2D Procrustes: best rotation θ and scale σ mapping centered A onto centered B.
 * Closed form — rotations emerge from the data (morphicons §4).
 */
private fun procrustes(a: List<Offset>, b: List<Offset>): Align {
    val n = minOf(a.size, b.size)
    if (n == 0) return Align(0f, 1f, 0f)
    var ca = Offset.Zero
    var cb = Offset.Zero
    for (i in 0 until n) {
        ca += a[i]
        cb += b[i]
    }
    ca /= n.toFloat()
    cb /= n.toFloat()
    var sxx = 0f; var sxy = 0f; var syx = 0f; var syy = 0f
    var normA = 0f
    for (i in 0 until n) {
        val ax = a[i].x - ca.x
        val ay = a[i].y - ca.y
        val bx = b[i].x - cb.x
        val by = b[i].y - cb.y
        sxx += ax * bx; sxy += ax * by
        syx += ay * bx; syy += ay * by
        normA += ax * ax + ay * ay
    }
    val theta = atan2(sxy - syx, sxx + syy)
    val scale = if (normA > 1e-6f) {
        (cos(theta) * (sxx + syy) + sin(theta) * (sxy - syx)) / normA
    } else 1f
    // residual after transform
    var err = 0f
    val ct = cos(theta); val st = sin(theta)
    val sc = if (absf(scale) > 1e-6f) scale else 1f
    for (i in 0 until n) {
        val ax = a[i].x - ca.x
        val ay = a[i].y - ca.y
        val px = sc * (ct * ax - st * ay)
        val py = sc * (st * ax + ct * ay)
        val bx = b[i].x - cb.x
        val by = b[i].y - cb.y
        err += (px - bx) * (px - bx) + (py - by) * (py - by)
    }
    err = sqrt(err / n)
    return Align(theta, scale, err)
}

private fun absf(v: Float) = if (v < 0) -v else v

/** Match subpaths by index; when counts differ, extras pair with nearest centroid. */
private fun matchSubpaths(
    a: StrokeIcon, b: StrokeIcon
): List<Pair<Int, Int>> {
    val aC = a.subpaths.map { centroid(it) }
    val bC = b.subpaths.map { centroid(it) }
    val pairs = mutableListOf<Pair<Int, Int>>()
    val usedB = mutableSetOf<Int>()
    if (a.subpaths.size <= b.subpaths.size) {
        for (i in a.subpaths.indices) {
            // nearest unused b
            var best = 0; var bestD = Float.MAX_VALUE
            for (j in bC.indices) {
                if (j in usedB) continue
                val d = (aC[i] - bC[j]).getDistanceSquared()
                if (d < bestD) { bestD = d; best = j }
            }
            usedB.add(best)
            pairs.add(i to best)
        }
    } else {
        for (j in b.subpaths.indices) {
            var best = 0; var bestD = Float.MAX_VALUE
            for (i in aC.indices) {
                val d = (aC[i] - bC[j]).getDistanceSquared()
                if (d < bestD) { bestD = d; best = i }
            }
            pairs.add(best to j)
        }
        // ensure every a has a pair (map leftovers to nearest b)
        val pairedA = pairs.map { it.first }.toSet()
        for (i in a.subpaths.indices) {
            if (i !in pairedA) {
                var best = 0; var bestD = Float.MAX_VALUE
                for (j in bC.indices) {
                    val d = (aC[i] - bC[j]).getDistanceSquared()
                    if (d < bestD) { bestD = d; best = j }
                }
                pairs.add(i to best)
            }
        }
    }
    return pairs
}

private fun centroid(pts: List<Offset>): Offset {
    if (pts.isEmpty()) return Offset.Zero
    var x = 0f; var y = 0f
    for (p in pts) { x += p.x; y += p.y }
    return Offset(x / pts.size, y / pts.size)
}

/** Cached plan for a morph pair (morphicons §5). */
private class MorphPlan(
    val pairs: List<Pair<Int, Int>>,
    /** Per pair: resampled centered A, aligned B-in-A-frame, θ, σ, centroids */
    val legs: List<Leg>
) {
    class Leg(
        val a: List<Offset>,          // centered A samples
        val bIntoA: List<Offset>,     // centered B brought into A frame
        val theta: Float,
        val scale: Float,
        val ca: Offset,
        val cb: Offset,
        val closed: Boolean
    )
}

private fun planMorph(from: StrokeIcon, to: StrokeIcon): MorphPlan {
    val pairs = matchSubpaths(from, to)
    val legs = pairs.map { (ai, bi) ->
        val closedA = from.closed.getOrElse(ai) { false }
        val closedB = to.closed.getOrElse(bi) { false }
        val closed = closedA && closedB
        val ra = resample(from.subpaths[ai], SAMPLES, closedA)
        val rb = resample(to.subpaths[bi], SAMPLES, closedB)
        val ca = centroid(ra)
        val cb = centroid(rb)
        val aC = ra.map { it - ca }
        val bC = rb.map { it - cb }
        val al = procrustes(aC, bC)
        // Bring B into A's frame: b̃ = R(-θ)/σ · b  so that σ R(θ) b̃ = b
        val ct = cos(-al.theta); val st = sin(-al.theta)
        val invS = if (absf(al.scale) > 1e-4f) 1f / al.scale else 1f
        val bIntoA = bC.map { p ->
            Offset(invS * (ct * p.x - st * p.y), invS * (st * p.x + ct * p.y))
        }
        MorphPlan.Leg(aC, bIntoA, al.theta, al.scale, ca, cb, closed)
    }
    return MorphPlan(pairs, legs)
}

/**
 * Interpolate plan at t∈[0,1]:
 * P(t) = c(t) + σᵗ · R(t·θ) · [(1−t)·a + t·b̃]
 * Rotation/scale applied in polar space so shapes don't collapse through the middle.
 */
private fun interpolate(plan: MorphPlan, t: Float): List<Pair<List<Offset>, Boolean>> {
    return plan.legs.map { leg ->
        val angle = leg.theta * t
        val c = cos(angle)
        val s = sin(angle)
        val sigmaT = if (leg.scale > 0f) {
            Math.pow(leg.scale.toDouble(), t.toDouble()).toFloat()
        } else 1f
        val cx = leg.ca.x + (leg.cb.x - leg.ca.x) * t
        val cy = leg.ca.y + (leg.cb.y - leg.ca.y) * t
        // Defensive: never index past the shorter side (closed/open resample mismatch).
        val count = minOf(leg.a.size, leg.bIntoA.size)
        val pts = List(count) { i ->
            val ax = leg.a[i].x
            val ay = leg.a[i].y
            val bx = leg.bIntoA[i].x
            val by = leg.bIntoA[i].y
            val x = (1f - t) * ax + t * bx
            val y = (1f - t) * ay + t * by
            Offset(cx + sigmaT * (c * x - s * y), cy + sigmaT * (s * x + c * y))
        }
        pts to leg.closed
    }
}

// ----- composable -----

/**
 * Draws a spring-driven morph between two [StrokeIcon]s.
 *
 * @param active false → [from], true → [to]; transitions use snappy spring
 *               (morphicons default preset, ζ≈0.73, k=420).
 */
@Composable
fun MorphIcon(
    from: StrokeIcon,
    to: StrokeIcon,
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 2.dp,
    contentDescription: String? = null
) {
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.73f,
            stiffness = 420f
        ),
        label = "morphT"
    )
    // Plan once per pair (cache via remember keyed on icons)
    val plan = rememberPlan(from, to)
    val stroke = strokeWidth
    val tint = color
    Canvas(modifier = modifier) {
        if (plan == null) return@Canvas
        val scale = size.minDimension / 24f
        val strokePx = stroke.toPx()
        val legs = interpolate(plan, t)
        for ((pts, closed) in legs) {
            if (pts.isEmpty()) continue
            val path = Path()
            path.moveTo(pts[0].x * scale, pts[0].y * scale)
            for (i in 1 until pts.size) {
                path.lineTo(pts[i].x * scale, pts[i].y * scale)
            }
            if (closed) path.close()
            drawPath(
                path = path,
                color = if (tint == Color.Unspecified) Color.Black else tint,
                style = Stroke(
                    width = strokePx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

@Composable
private fun rememberPlan(from: StrokeIcon, to: StrokeIcon): MorphPlan? {
    return androidx.compose.runtime.remember(from, to) {
        try {
            planMorph(from, to)
        } catch (_: Exception) {
            null
        }
    }
}

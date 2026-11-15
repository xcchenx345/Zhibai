package com.zhibai.app.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sin
import kotlin.random.Random

/**
 * Physics snow celebration.
 *
 * - Flakes fall under gravity and settle into a heightmap pile at the BOTTOM of
 *   the screen (not the input bar).
 * - Device tilt (gravity sensor) is a lateral wind: falling flakes drift and the
 *   settled pile avalanches toward the low side, so the snow moves around as the
 *   phone is tilted.
 * - Hard caps: [SnowPhysics.MAX_PILE_FRACTION] of screen height and
 *   [SnowPhysics.SPAWN_BUDGET] flakes per celebration.
 * - Auto-stops spawning after 15s; the pile melts on [pileRequest] (user typing).
 */
@Composable
fun SnowCelebration(
    active: Boolean,
    pileRequest: Int,
    modifier: Modifier = Modifier,
    /** Increments once per celebration — restarts the sim with a fresh budget. */
    celebrateKey: Int = 0
) {
    if (!active) return

    val isDark = isSystemInDarkTheme()
    val flakeColor = if (isDark) Color.White else Color(0xFF333333)
    val pileColor = if (isDark) Color.White else Color(0xFF555555)

    // Tilt = gravity.x / g, roughly -1..1. Falls back to the accelerometer on
    // devices without a gravity sensor.
    val context = LocalContext.current
    val tilt = remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                tilt.floatValue = (e.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    val sim = remember { SnowPhysics() }
    // A new celebration restarts the sim — otherwise the remembered budget is
    // spent after the first one and later runs never snow.
    LaunchedEffect(celebrateKey) {
        if (celebrateKey > 0) sim.reset()
    }
    // Frame ticker: read inside the draw lambda → per-frame redraw only.
    val tick by rememberInfiniteTransition(label = "snowFall").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "snowTick"
    )

    // Melt only on a fresh request: the effect also runs on composition entry,
    // and a leftover counter from earlier typing used to melt the pile instantly.
    val lastPileRequest = remember { mutableIntStateOf(pileRequest) }
    LaunchedEffect(pileRequest) {
        if (pileRequest > lastPileRequest.intValue) {
            lastPileRequest.intValue = pileRequest
            sim.melt()
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        // `tick` must be read in the draw phase to invalidate once per frame.
        sim.step(
            nowMs = System.currentTimeMillis(),
            tilt = tilt.floatValue,
            w = size.width,
            h = size.height,
            phase = tick
        )
        sim.draw(this, flakeColor, pileColor)
    }
}

private class SnowPhysics {
    companion object {
        const val COLS = 64
        /** Settled snow never covers more than this fraction of screen height. */
        const val MAX_PILE_FRACTION = 0.22f
        /** Hard cap on how many flakes one celebration may ever spawn. */
        const val SPAWN_BUDGET = 220
        private const val SPAWN_PER_SEC = 55f
        private const val GRAVITY = 420f
        private const val TERMINAL_VY = 420f
        private const val WIND_GAIN = 150f
    }

    private val height = FloatArray(COLS)
    private val flakes = ArrayList<Flake>(SPAWN_BUDGET)
    private val rng = Random(7)
    private var lastMs = -1L
    private var spawnAcc = 0f
    private var spawnBudget = SPAWN_BUDGET
    private var melting = false

    private class Flake(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        val r: Float, val alpha: Float
    )

    fun step(nowMs: Long, tilt: Float, w: Float, h: Float, phase: Float) {
        if (w <= 0f || h <= 0f) return
        val dt = if (lastMs < 0) 0.016f else ((nowMs - lastMs) / 1000f).coerceIn(0f, 0.05f)
        lastMs = nowMs
        val maxPile = h * MAX_PILE_FRACTION

        // Melt (user typing): pile decays quickly, flakes keep falling.
        if (melting) {
            val k = 1f - 4f * dt
            var alive = false
            for (i in height.indices) {
                height[i] *= k
                if (height[i] > 0.5f) alive = true else height[i] = 0f
            }
            if (!alive) melting = false
        }

        // Spawn, budget-capped.
        if (spawnBudget > 0) {
            spawnAcc += dt * SPAWN_PER_SEC
            while (spawnAcc >= 1f && spawnBudget > 0) {
                spawnAcc -= 1f
                spawnBudget--
                flakes.add(
                    Flake(
                        x = rng.nextFloat() * w,
                        y = -10f - rng.nextFloat() * h * 0.35f,
                        vx = 0f,
                        vy = 20f + rng.nextFloat() * 60f,
                        r = 1.5f + rng.nextFloat() * 4f,
                        alpha = 0.35f + rng.nextFloat() * 0.65f
                    )
                )
            }
        }

        // Integrate falling flakes.
        val wind = tilt * WIND_GAIN
        var i = 0
        while (i < flakes.size) {
            val f = flakes[i]
            f.vy = (f.vy + GRAVITY * dt).coerceAtMost(TERMINAL_VY)
            f.vx += (wind - f.vx) * 2.2f * dt
            f.x += f.vx * dt + sin((phase * 6.28f + f.y * 0.02f).toDouble()).toFloat() * 8f * dt
            f.y += f.vy * dt
            if (f.x < -10f) f.x += w + 20f
            if (f.x > w + 10f) f.x -= w + 20f
            val col = ((f.x / w) * COLS).toInt().coerceIn(0, COLS - 1)
            if (f.y >= h - height[col]) {
                // Settle into the heightmap. Fill only what fits under the cap —
                // clamping after the add used to destroy the overshoot, which read
                // as the pile melting on its own once it got tall.
                if (!melting) {
                    val room = (maxPile - height[col]).coerceAtLeast(0f)
                    height[col] += (f.r * 1.2f).coerceAtMost(room)
                }
                flakes.removeAt(i)
            } else {
                i++
            }
        }

        // Avalanche: settled snow slides toward the tilt direction. Relaxed from a
        // snapshot (each pair sees the same pre-frame heights) and both sides are
        // capped — a cascading in-place sweep used to drain the uphill columns into
        // the downhill edge every frame (拆东墙补西墙).
        val dir = when {
            tilt > 0.12f -> 1
            tilt < -0.12f -> -1
            else -> 0
        }
        if (dir != 0 && !melting) {
            val gain = (0.25f + kotlin.math.abs(tilt) * 0.6f) * (dt * 60f).coerceAtMost(2f)
            val snapshot = height.clone()
            var k = 0
            while (k < COLS - 1) {
                val a = if (dir > 0) k else COLS - 1 - k
                val b = a + dir
                if (b in 0 until COLS) {
                    val diff = snapshot[a] - snapshot[b]
                    if (diff > 2.2f) {
                        var move = (diff - 2.2f) * 0.35f * gain
                        move = move.coerceAtMost(snapshot[a] * 0.4f)
                        move = move.coerceAtMost((maxPile - snapshot[b]).coerceAtLeast(0f))
                        if (move > 0f) {
                            height[a] -= move
                            height[b] += move
                        }
                    }
                }
                k++
            }
        }
    }

    /** Fresh celebration: new flake budget, empty pile. */
    fun reset() {
        for (i in height.indices) height[i] = 0f
        flakes.clear()
        spawnBudget = SPAWN_BUDGET
        spawnAcc = 0f
        melting = false
    }

    fun melt() {
        melting = true
    }

    fun draw(scope: DrawScope, flakeColor: Color, pileColor: Color) = with(scope) {
        val w = size.width
        val h = size.height
        // Settled pile — heightmap across the full screen bottom.
        var hasPile = false
        for (c in height.indices) if (height[c] > 0.5f) { hasPile = true; break }
        if (hasPile) {
            val path = Path().apply {
                moveTo(0f, h)
                for (c in 0 until COLS) {
                    lineTo(c * w / (COLS - 1), h - height[c])
                }
                lineTo(w, h)
                close()
            }
            drawPath(path, color = pileColor.copy(alpha = 0.92f), style = Fill)
        }
        for (f in flakes) {
            drawCircle(
                color = flakeColor.copy(alpha = f.alpha),
                radius = f.r,
                center = Offset(f.x, f.y)
            )
        }
    }
}

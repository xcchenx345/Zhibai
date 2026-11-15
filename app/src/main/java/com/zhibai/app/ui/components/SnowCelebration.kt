package com.zhibai.app.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
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

/** Process-scoped field — survives Activity recreation on screen rotation. */
private object SnowField {
    val sim = SnowPhysics()
}

private fun displayRotation(context: Context): Int = try {
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
} catch (_: Throwable) {
    Surface.ROTATION_0
}

/**
 * Physics snow — once triggered it keeps falling for the life of the process.
 *
 * - Flakes fall under gravity and settle into a heightmap pile at the BOTTOM of
 *   the screen (not the input bar).
 * - Device tilt is a lateral wind: falling flakes drift and the settled pile
 *   avalanches toward the low side. The gravity vector is remapped through the
 *   display rotation so landscape tilt points the wind the same way the screen
 *   faces.
 * - Hard caps: [SnowPhysics.MAX_PILE_FRACTION] of screen height and
 *   [SnowPhysics.MAX_ALIVE] concurrent flakes.
 * - The sim lives in [SnowField] (process scope) so rotating the phone keeps the
 *   field instead of re-randomizing every flake.
 */
@Composable
fun SnowCelebration(
    active: Boolean,
    modifier: Modifier = Modifier
) {
    if (!active) return

    val isDark = isSystemInDarkTheme()
    val flakeColor = if (isDark) Color.White else Color(0xFF333333)
    val pileColor = if (isDark) Color.White else Color(0xFF555555)

    // Tilt = screen-x component of gravity / g, roughly -1..1. Falls back to the
    // accelerometer on devices without a gravity sensor.
    val context = LocalContext.current
    val tilt = remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val gx = e.values[0]
                val gy = e.values[1]
                // Sensor axes follow the device's natural orientation — remap to
                // screen axes or landscape tilt blows the snow the wrong way.
                val screenX = when (displayRotation(context)) {
                    Surface.ROTATION_90 -> gy
                    Surface.ROTATION_180 -> -gx
                    Surface.ROTATION_270 -> -gy
                    else -> gx
                }
                tilt.floatValue = (screenX / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        // Real gyroscope: rotation rate feeds the wind so twisting the phone blows
        // the snow around (no permission needed for motion sensors on Android).
        val sim = SnowField.sim
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gyroListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // values[2] = rotation rate around the screen normal (spin).
                sim.addSpin(e.values[2])
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (gyro != null) sm.registerListener(gyroListener, gyro, SensorManager.SENSOR_DELAY_GAME)
        com.zhibai.app.util.AppLog.d("snow sensors: tilt=${sensor?.name ?: "none"} gyro=${gyro?.name ?: "none"}")
        onDispose {
            sm.unregisterListener(listener)
            sm.unregisterListener(gyroListener)
        }
    }

    val sim = SnowField.sim
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
        const val MAX_PILE_FRACTION = 0.12f
        /** Concurrent falling flakes (perf cap) — spawning runs forever (一直下). */
        const val MAX_ALIVE = 160
        private const val SPAWN_PER_SEC = 30f
        private const val GRAVITY = 420f
        private const val TERMINAL_VY = 420f
        private const val WIND_GAIN = 220f
    }

    private val height = FloatArray(COLS)
    private val flakes = ArrayList<Flake>(MAX_ALIVE)
    private val rng = Random(7)
    private var lastMs = -1L
    private var spawnAcc = 0f
    private var lastW = 0f
    private var lastH = 0f

    /** Gyro spin wind, written from the sensor thread and decayed in [step]. */
    @Volatile
    private var spin = 0f

    fun addSpin(rateRadPerSec: Float) {
        spin = (spin + rateRadPerSec * 0.22f).coerceIn(-1.5f, 1.5f)
    }

    private class Flake(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        val r: Float, val alpha: Float
    )

    fun step(nowMs: Long, tilt: Float, w: Float, h: Float, phase: Float) {
        if (w <= 0f || h <= 0f) return
        // Keyboard-open resizes shrink the canvas; without rescaling every falling
        // flake is suddenly below the pile surface and drops in one frame — the
        // snow looks like it bursts and vanishes when the input box opens.
        if (lastW > 0f && (w != lastW || h != lastH)) {
            val sx = w / lastW
            val sy = h / lastH
            for (f in flakes) {
                f.x *= sx
                f.y *= sy
            }
            for (i in height.indices) height[i] *= sy
        }
        lastW = w
        lastH = h
        val dt = if (lastMs < 0) 0.016f else ((nowMs - lastMs) / 1000f).coerceIn(0f, 0.05f)
        lastMs = nowMs
        val maxPile = h * MAX_PILE_FRACTION

        // Continuous spawn — it snows until the process dies. The only cap is how
        // many flakes may be airborne at once.
        if (flakes.size < MAX_ALIVE) {
            spawnAcc += dt * SPAWN_PER_SEC
            while (spawnAcc >= 1f && flakes.size < MAX_ALIVE) {
                spawnAcc -= 1f
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

        // Integrate falling flakes. Wind = steady tilt + gyro spin impulse.
        spin *= (1f - 2.5f * dt)
        val wind = (tilt + spin) * WIND_GAIN
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
                val room = (maxPile - height[col]).coerceAtLeast(0f)
                height[col] += (f.r * 1.2f).coerceAtMost(room)
                flakes.removeAt(i)
            } else {
                i++
            }
        }

        // Avalanche: settled snow slides toward the tilt direction. Relaxed from a
        // snapshot (each pair sees the same pre-frame heights) and both sides are
        // capped — a cascading in-place sweep used to drain the uphill columns into
        // the downhill edge every frame (拆东墙补西墙).
        val eff = tilt + spin
        val dir = when {
            eff > 0.12f -> 1
            eff < -0.12f -> -1
            else -> 0
        }
        if (dir != 0) {
            val gain = (0.25f + kotlin.math.abs(eff) * 0.6f) * (dt * 60f).coerceAtMost(2f)
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

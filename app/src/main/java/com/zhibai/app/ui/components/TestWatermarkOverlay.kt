package com.zhibai.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Static diagonal tiled test watermark, rasterized once to a Bitmap.
 * No per-frame text drawing — safe for chat streaming.
 *
 * Disable: create empty `nowatermark` in getExternalFilesDir(null) or filesDir.
 */
@Composable
fun TestWatermarkOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ip by remember { mutableStateOf("…") }
    val disabled = remember(context) { isWatermarkDisabled(context) }

    LaunchedEffect(Unit) {
        ip = withContext(Dispatchers.IO) { resolvePublicIp(context) }
    }

    if (disabled) return

    // Rebuild bitmap only when IP arrives/changes (once), not on every recomposition.
    val bitmap = remember(ip) {
        buildWatermarkBitmap("测试版非正式产出结果${Build.MODEL}$ip")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(Float.MAX_VALUE)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
            alpha = 0.18f,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Draw the dense diagonal tile into a screen-sized bitmap once.
 * Uses a tile strip then tiles it — far cheaper than N×M drawText every frame.
 */
private fun buildWatermarkBitmap(line: String): Bitmap {
    // Logical screen size; Image + FillBounds scales it to the real view.
    val w = 1080
    val h = 1920

    val textPaint = Paint().apply {
        color = android.graphics.Color.argb(255, 0, 0, 0)
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val metrics = textPaint.fontMetrics
    val textW = textPaint.measureText(line)
    val textH = metrics.descent - metrics.ascent

    val stepX = textW + 8f
    val stepY = textH + 12f
    val angle = -28f

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(out)
    canvas.drawColor(android.graphics.Color.TRANSPARENT)

    canvas.save()
    canvas.rotate(angle, w / 2f, h / 2f)

    val overscan = (w + h).toFloat()
    val cols = ((overscan * 2) / stepX).toInt() + 4
    val rows = ((overscan * 2) / stepY).toInt() + 4
    val startX = w / 2f - (cols / 2f) * stepX
    val startY = h / 2f - (rows / 2f) * stepY

    for (r in 0 until rows) {
        val rowOff = if (r % 2 == 0) 0f else stepX * 0.5f
        val y = startY + r * stepY
        for (c in 0 until cols) {
            val x = startX + c * stepX + rowOff
            canvas.drawText(line, x, y - metrics.ascent, textPaint)
        }
    }
    canvas.restore()
    return out
}

/** File-only kill switch. */
private fun isWatermarkDisabled(context: Context): Boolean {
    val names = listOf("nowatermark", "nowatermark.txt")
    val candidates = listOfNotNull(
        context.getExternalFilesDir(null),
        context.filesDir
    ).flatMap { dir -> names.map { java.io.File(dir, it) } }
    return candidates.any { it.exists() }
}

private fun resolvePublicIp(context: Context): String {
    fetchPublicIp()?.let { return it }
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                return addr.hostAddress ?: "0.0.0.0"
            }
        }
    } catch (_: Exception) {}
    return "0.0.0.0"
}

private fun fetchPublicIp(): String? {
    val urls = listOf(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://ipinfo.io/ip",
        "https://checkip.amazonaws.com"
    )
    for (url in urls) {
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MiMo-Watermark")
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText().trim()
                if (body.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) ||
                    (body.contains(':') && body.length <= 45)
                ) return body
            }
            conn.disconnect()
        } catch (_: Exception) {}
    }
    return null
}

package com.mimo.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.mimo.app.ui.theme.MiMoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ===== annotation model (coordinates in bitmap pixel space) =====

sealed class Shape {
    data class Pen(val pts: List<Offset>, val color: Color, val w: Float) : Shape()
    data class Box(val start: Offset, val end: Offset, val color: Color, val w: Float) : Shape()
    data class CircleShape(val start: Offset, val end: Offset, val color: Color, val w: Float) : Shape()
    data class Arrow(val start: Offset, val end: Offset, val color: Color, val w: Float) : Shape()
    data class Mosaic(val start: Offset, val end: Offset) : Shape()
}

enum class EditTool { BROWSE, PEN, MOSAIC, RECT, ARROW, CIRCLE, CROP }

private val PALETTE = listOf(
    Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
    Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF111111), Color(0xFFFFFFFF)
)

/**
 * Full-screen media editor.
 * Image: HD preview, pinch zoom, pen / mosaic / rect / arrow / circle / crop.
 * Video: HD preview + pinch zoom (frame-level editing out of scope).
 * onDone receives edited uri (original uri when nothing changed).
 */
@Composable
fun MediaEditorScreen(
    uri: Uri,
    isVideo: Boolean,
    onDone: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .zIndex(50f)
    ) {
        if (isVideo) {
            VideoPreview(uri = uri, onDismiss = onDismiss, onDone = { onDone(uri) })
        } else {
            ImageEditor(uri = uri, onDone = onDone, onDismiss = onDismiss)
        }
    }
}

// ===== video preview =====

@Composable
private fun VideoPreview(uri: Uri, onDismiss: () -> Unit, onDone: () -> Unit) {
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(uri)
                    setOnPreparedListener { mp -> mp.isLooping = true; start() }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGesturesSimple { z, pan ->
                        zoom = (zoom * z).coerceIn(1f, 8f)
                        if (zoom <= 1.01f) offset = Offset.Zero else offset += pan
                    }
                }
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("视频预览 · 双指缩放", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = MiMoColors.Primary)
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("完成")
            }
        }
    }
}

/** Minimal pinch-zoom gesture (zoom around centroid via simple accumulation). */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTransformGesturesSimple(
    onGesture: (zoom: Float, pan: Offset) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var prevCentroid = Offset.Zero
        var prevDistance = 0f
        var isFirst = true
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size < 2) {
                if (pressed.isEmpty()) break
                prevDistance = 0f
                continue
            }
            val c = Offset(
                (pressed[0].position.x + pressed[1].position.x) / 2,
                (pressed[0].position.y + pressed[1].position.y) / 2
            )
            val dx = pressed[0].position.x - pressed[1].position.x
            val dy = pressed[0].position.y - pressed[1].position.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (!isFirst && prevDistance > 1f && dist > 1f) {
                onGesture(dist / prevDistance, c - prevCentroid)
            }
            prevCentroid = c
            prevDistance = dist
            isFirst = false
        }
    }
}

// ===== image editor =====

@Composable
private fun ImageEditor(uri: Uri, onDone: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // view transform
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // editing state
    var tool by remember { mutableStateOf(EditTool.BROWSE) }
    var color by remember { mutableStateOf(PALETTE[0]) }
    val shapes = remember { mutableStateListOf<Shape>() }
    var draft by remember { mutableStateOf<Shape?>(null) }
    var cropRect by remember { mutableStateOf<Rect?>(null) }       // bitmap coords, while cropping
    var draftCrop by remember { mutableStateOf<Rect?>(null) }      // while dragging crop
    var saving by remember { mutableStateOf(false) }
    val editorScope = rememberCoroutineScope()

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                // downsample extreme sizes to keep memory sane (max ~4096 long edge)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                val longEdge = max(bounds.outWidth, bounds.outHeight)
                while (longEdge / sample > 4096) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                if (bmp != null) { bitmap = bmp } else { loadError = "无法解码图片" }
            } catch (e: Exception) {
                loadError = e.message
            }
        }
    }

    val bmp = bitmap

    // fit transform: screen = (bmpCoord * totalScale) + origin
    fun totalScale(b: Bitmap): Float {
        if (viewSize == IntSize.Zero) return 1f
        val fit = min(viewSize.width / b.width.toFloat(), viewSize.height / b.height.toFloat())
        return fit * zoom
    }
    fun origin(b: Bitmap): Offset {
        val s = totalScale(b)
        return Offset(
            (viewSize.width - b.width * s) / 2f + pan.x,
            (viewSize.height - b.height * s) / 2f + pan.y
        )
    }
    fun toBitmap(p: Offset, b: Bitmap): Offset {
        val o = origin(b)
        val s = totalScale(b)
        return Offset((p.x - o.x) / s, (p.y - o.y) / s)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Cancel", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Text("编辑图片", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { if (shapes.isNotEmpty()) shapes.removeAt(shapes.lastIndex) },
                enabled = shapes.isNotEmpty()
            ) {
                Icon(Icons.Default.Undo, "Undo", tint = if (shapes.isNotEmpty()) Color.White else Color.Gray)
            }
            if (tool == EditTool.CROP && cropRect != null) {
                TextButton(onClick = {
                    // apply crop
                    val b = bitmap ?: return@TextButton
                    val rc = cropRect!!
                    val r = Rect(
                        max(rc.left, 0f), max(rc.top, 0f),
                        min(rc.right, b.width.toFloat()), min(rc.bottom, b.height.toFloat())
                    )
                    if (r.width >= 16 && r.height >= 16) {
                        val cropped = Bitmap.createBitmap(
                            b, r.left.roundToInt(), r.top.roundToInt(),
                            r.width.roundToInt(), r.height.roundToInt()
                        )
                        val shift = Offset(r.left, r.top)
                        for (i in shapes.indices) {
                            shapes[i] = shapes[i].shiftedBy(-shift)
                        }
                        bitmap = cropped
                        cropRect = null
                        draftCrop = null
                        zoom = 1f
                        pan = Offset.Zero
                        tool = EditTool.BROWSE
                    }
                }) {
                    Text("裁剪", color = MiMoColors.Primary, fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = {
                    val b = bitmap ?: return@Button
                    if (shapes.isEmpty() && cropRect == null) { onDone(uri); return@Button }
                    saving = true
                    val shapeList = shapes.toList()
                    val crop = cropRect
                    editorScope.launch {
                        try {
                            val newUri = withContext(Dispatchers.IO) {
                                renderEdit(context, b, shapeList, crop)
                            }
                            saving = false
                            onDone(newUri)
                        } catch (e: Exception) {
                            saving = false
                            android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = MiMoColors.Primary)
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保存")
                }
            }
        }

        if (bmp == null && loadError != null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("无法加载图片: $loadError", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        }

        // canvas
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .pointerInput(bmp, tool) {
                    if (bmp == null) return@pointerInput
                    val b = bmp
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var pointerCount = 1
                        var lastCentroid = down.position
                        var lastDist = 0f
                        var prevPos = down.position
                        var drawing = tool != EditTool.BROWSE && tool != EditTool.CROP
                        var cropping = tool == EditTool.CROP

                        if (drawing) draft = makeDraft(tool, toBitmap(down.position, b), toBitmap(down.position, b), color, strokeW(b))
                        if (cropping) draftCrop = run { val pp = toBitmap(down.position, b); Rect(pp.x, pp.y, pp.x, pp.y) }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            pointerCount = pressed.size

                            if (pointerCount >= 2) {
                                // pinch zoom + pan (always available)
                                val c = Offset(
                                    (pressed[0].position.x + pressed[1].position.x) / 2,
                                    (pressed[0].position.y + pressed[1].position.y) / 2
                                )
                                val dx = pressed[0].position.x - pressed[1].position.x
                                val dy = pressed[0].position.y - pressed[1].position.y
                                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                if (lastDist > 1f && dist > 1f) {
                                    val factor = dist / lastDist
                                    zoom = (zoom * factor).coerceIn(0.5f, 12f)
                                    pan += c - lastCentroid
                                }
                                lastDist = dist
                                lastCentroid = c
                                draft = null
                                draftCrop = null
                                drawing = false
                                cropping = false
                                continue
                            }

                            // single finger
                            val pos = pressed[0].position
                            if (tool == EditTool.BROWSE) {
                                pan += pos - prevPos
                            } else if (drawing) {
                                val bp = toBitmap(pos, b)
                                val start = when (val d = draft) {
                                    is Shape.Pen -> d.pts.first()
                                    is Shape.Box -> d.start
                                    is Shape.CircleShape -> d.start
                                    is Shape.Arrow -> d.start
                                    is Shape.Mosaic -> d.start
                                    null -> bp
                                }
                                draft = if (tool == EditTool.PEN) {
                                    val prev = (draft as? Shape.Pen)?.pts ?: listOf(start)
                                    Shape.Pen(prev + bp, color, strokeW(b))
                                } else {
                                    makeDraft(tool, start, bp, color, strokeW(b))
                                }
                            } else if (cropping) {
                                val a = toBitmap(down.position, b)
                                val bp = toBitmap(pos, b)
                                draftCrop = Rect(
                                    min(a.x, bp.x), min(a.y, bp.y),
                                    max(a.x, bp.x), max(a.y, bp.y)
                                )
                            }
                            prevPos = pos
                        }

                        // gesture end
                        draft?.let { s ->
                            val valid = when (s) {
                                is Shape.Pen -> s.pts.size > 1
                                is Shape.Mosaic -> abs(s.end.x - s.start.x) > 8 && abs(s.end.y - s.start.y) > 8
                                else -> abs(s.endX() - s.startX()) > 6 || abs(s.endY() - s.startY()) > 6
                            }
                            if (valid) shapes.add(s)
                        }
                        draft = null
                        draftCrop?.let {
                            if (it.width > 8 && it.height > 8) cropRect = it
                        }
                        draftCrop = null
                    }
                }
        ) {
            if (bmp != null) {
                val s = totalScale(bmp)
                val o = origin(bmp)
                drawImage(
                    bmp.asImageBitmap(),
                    dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()),
                    dstSize = IntSize((bmp.width * s).roundToInt(), (bmp.height * s).roundToInt())
                )
                val all = shapes + listOfNotNull(draft)
                all.forEach { sh ->
                    drawShapeOnCanvas(sh, o, s)
                }
                val cr = draftCrop ?: cropRect
                if (tool == EditTool.CROP && cr != null) {
                    // dim only OUTSIDE the crop selection
                    val so = Offset(o.x + cr.left * s, o.y + cr.top * s)
                    val ssize = Size(cr.width * s, cr.height * s)
                    val dim = Color.Black.copy(alpha = 0.55f)
                    drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, so.y))
                    drawRect(dim, topLeft = Offset(0f, so.y + ssize.height), size = Size(size.width, size.height - so.y - ssize.height))
                    drawRect(dim, topLeft = Offset(0f, so.y), size = Size(so.x, ssize.height))
                    drawRect(dim, topLeft = Offset(so.x + ssize.width, so.y), size = Size(size.width - so.x - ssize.width, ssize.height))
                    drawRect(Color.White, topLeft = so, size = ssize, style = Stroke(width = 2f))
                }
            }
        }

        // bottom tools
        if (bmp != null) {
            // palette (when drawing tool active)
            if (tool == EditTool.PEN || tool == EditTool.RECT || tool == EditTool.ARROW || tool == EditTool.CIRCLE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PALETTE.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(c)
                                .then(
                                    if (c == color) Modifier.border(2.dp, Color.White, CircleShape)
                                    else Modifier
                                )
                                .clickableNoRipple { color = c }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ToolButton("浏览", Icons.Default.OpenWith, tool == EditTool.BROWSE) { tool = EditTool.BROWSE; cropRect = null }
                ToolButton("画笔", Icons.Default.Brush, tool == EditTool.PEN) { tool = EditTool.PEN }
                ToolButton("马赛克", Icons.Default.GridOn, tool == EditTool.MOSAIC) { tool = EditTool.MOSAIC }
                ToolButton("框", Icons.Default.CropSquare, tool == EditTool.RECT) { tool = EditTool.RECT }
                ToolButton("箭头", Icons.Default.ArrowUpward, tool == EditTool.ARROW) { tool = EditTool.ARROW }
                ToolButton("圆", Icons.Default.Circle, tool == EditTool.CIRCLE) { tool = EditTool.CIRCLE }
                ToolButton("裁剪", Icons.Default.Crop, tool == EditTool.CROP) {
                    tool = EditTool.CROP; cropRect = null; draftCrop = null
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun Shape.startX() = when (this) { is Shape.Pen -> pts.first().x; else -> (this as? Shape.Box)?.start?.x ?: (this as? Shape.CircleShape)?.start?.x ?: (this as? Shape.Arrow)?.start?.x ?: (this as? Shape.Mosaic)?.start?.x ?: 0f }
private fun Shape.startY() = when (this) { is Shape.Pen -> pts.first().y; else -> (this as? Shape.Box)?.start?.y ?: (this as? Shape.CircleShape)?.start?.y ?: (this as? Shape.Arrow)?.start?.y ?: (this as? Shape.Mosaic)?.start?.y ?: 0f }
private fun Shape.endX() = when (this) { is Shape.Pen -> pts.last().x; else -> (this as? Shape.Box)?.end?.x ?: (this as? Shape.CircleShape)?.end?.x ?: (this as? Shape.Arrow)?.end?.x ?: (this as? Shape.Mosaic)?.end?.x ?: 0f }
private fun Shape.endY() = when (this) { is Shape.Pen -> pts.last().y; else -> (this as? Shape.Box)?.end?.y ?: (this as? Shape.CircleShape)?.end?.y ?: (this as? Shape.Arrow)?.end?.y ?: (this as? Shape.Mosaic)?.end?.y ?: 0f }

private fun Shape.shiftedBy(d: Offset): Shape = when (this) {
    is Shape.Pen -> copy(pts = pts.map { it + d })
    is Shape.Box -> copy(start = start + d, end = end + d)
    is Shape.CircleShape -> copy(start = start + d, end = end + d)
    is Shape.Arrow -> copy(start = start + d, end = end + d)
    is Shape.Mosaic -> copy(start = start + d, end = end + d)
}

private fun strokeW(b: Bitmap): Float = max(b.width, b.height) / 150f

private fun makeDraft(tool: EditTool, start: Offset, end: Offset, color: Color, w: Float): Shape = when (tool) {
    EditTool.PEN -> Shape.Pen(listOf(start, end), color, w)
    EditTool.RECT -> Shape.Box(start, end, color, w)
    EditTool.CIRCLE -> Shape.CircleShape(start, end, color, w)
    EditTool.ARROW -> Shape.Arrow(start, end, color, w)
    EditTool.MOSAIC -> Shape.Mosaic(start, end)
    else -> Shape.Pen(listOf(start, end), color, w)
}

// ===== canvas rendering of a shape (screen = bmpCoord * scale + origin) =====

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShapeOnCanvas(
    sh: Shape, origin: Offset, scale: Float
) {
    fun p(v: Offset) = Offset(origin.x + v.x * scale, origin.y + v.y * scale)
    val sw = max(1f, sh.wOf() * scale)
    when (sh) {
        is Shape.Pen -> {
            if (sh.pts.size < 2) return
            val path = Path().apply {
                moveTo(p(sh.pts.first()).x, p(sh.pts.first()).y)
                for (i in 1 until sh.pts.size) lineTo(p(sh.pts[i]).x, p(sh.pts[i]).y)
            }
            drawPath(path, sh.color, style = Stroke(width = sw))
        }
        is Shape.Box -> {
            val a = p(sh.start); val b = p(sh.end)
            drawRect(
                sh.color,
                topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                size = Size(abs(b.x - a.x), abs(b.y - a.y)),
                style = Stroke(width = sw)
            )
        }
        is Shape.CircleShape -> {
            val a = p(sh.start); val b = p(sh.end)
            drawOval(
                sh.color,
                topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                size = Size(abs(b.x - a.x), abs(b.y - a.y)),
                style = Stroke(width = sw)
            )
        }
        is Shape.Arrow -> {
            val a = p(sh.start); val b = p(sh.end)
            drawLine(sh.color, a, b, strokeWidth = sw)
            val angle = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            val headLen = sw * 4f
            val a1 = angle + Math.toRadians(35.0)
            val a2 = angle - Math.toRadians(35.0)
            drawLine(
                sh.color, b,
                Offset(b.x - headLen * kotlin.math.cos(a1).toFloat(), b.y - headLen * kotlin.math.sin(a1).toFloat()),
                strokeWidth = sw
            )
            drawLine(
                sh.color, b,
                Offset(b.x - headLen * kotlin.math.cos(a2).toFloat(), b.y - headLen * kotlin.math.sin(a2).toFloat()),
                strokeWidth = sw
            )
        }
        is Shape.Mosaic -> {
            // preview: translucent gray block (real pixelation happens on save)
            val a = p(sh.start); val b = p(sh.end)
            drawRect(
                Color.Gray.copy(alpha = 0.75f),
                topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                size = Size(abs(b.x - a.x), abs(b.y - a.y))
            )
        }
    }
}

private fun Shape.wOf(): Float = when (this) {
    is Shape.Pen -> w
    is Shape.Box -> w
    is Shape.CircleShape -> w
    is Shape.Arrow -> w
    is Shape.Mosaic -> 0f
}


// ===== save: render shapes onto bitmap, optional crop, write cache file =====

private fun renderEdit(
    context: android.content.Context,
    bitmap: Bitmap,
    shapes: List<Shape>,
    crop: Rect?
): Uri {
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = AndroidCanvas(out)

    // mosaic: pixelate regions first
    val mosaicRegions = shapes.filterIsInstance<Shape.Mosaic>()
    if (mosaicRegions.isNotEmpty()) {
        val tmp = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
        val tCanvas = AndroidCanvas(tmp)
        for (m in mosaicRegions) {
            val l = min(m.start.x, m.end.x).toInt().coerceIn(0, out.width - 1)
            val t = min(m.start.y, m.end.y).toInt().coerceIn(0, out.height - 1)
            val w = abs(m.end.x - m.start.x).toInt().coerceAtMost(out.width - l)
            val h = abs(m.end.y - m.start.y).toInt().coerceAtMost(out.height - t)
            if (w < 2 || h < 2) continue
            val block = 16
            val sw = max(1, w / block); val shh = max(1, h / block)
            val small = Bitmap.createBitmap(out, l, t, w, h)
            val tiny = Bitmap.createScaledBitmap(small, sw, shh, false)
            val up = Bitmap.createScaledBitmap(tiny, w, h, false)
            tCanvas.drawBitmap(up, l.toFloat(), t.toFloat(), null)
            small.recycle(); tiny.recycle(); up.recycle()
        }
        val punch = Paint()
        for (m in mosaicRegions) {
            val l = min(m.start.x, m.end.x).toInt().coerceIn(0, out.width - 1)
            val t = min(m.start.y, m.end.y).toInt().coerceIn(0, out.height - 1)
            val w = abs(m.end.x - m.start.x).toInt().coerceAtMost(out.width - l)
            val h = abs(m.end.y - m.start.y).toInt().coerceAtMost(out.height - t)
            if (w < 2 || h < 2) continue
            val region = Bitmap.createBitmap(tmp, l, t, w, h)
            canvas.drawBitmap(region, l.toFloat(), t.toFloat(), punch)
            region.recycle()
        }
        tmp.recycle()
    }

    // other shapes
    for (sh in shapes) {
        if (sh is Shape.Mosaic) continue
        drawShapeOnBitmap(canvas, sh)
    }

    // crop
    val finalBmp = if (crop != null) {
        val l = crop.left.toInt().coerceIn(0, out.width - 1)
        val t = crop.top.toInt().coerceIn(0, out.height - 1)
        val w = crop.width.toInt().coerceAtMost(out.width - l).coerceAtLeast(1)
        val h = crop.height.toInt().coerceAtMost(out.height - t).coerceAtLeast(1)
        Bitmap.createBitmap(out, l, t, w, h)
    } else out

    val file = File(context.cacheDir, "mimo_edit_${System.currentTimeMillis()}.jpg")
    file.outputStream().use { finalBmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    if (finalBmp !== out) finalBmp.recycle()
    out.recycle()
    return Uri.fromFile(file)
}

private fun drawShapeOnBitmap(canvas: AndroidCanvas, sh: Shape) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    when (sh) {
        is Shape.Pen -> {
            paint.color = sh.color.toArgb(); paint.strokeWidth = sh.w
            val path = android.graphics.Path().apply {
                moveTo(sh.pts.first().x, sh.pts.first().y)
                for (i in 1 until sh.pts.size) lineTo(sh.pts[i].x, sh.pts[i].y)
            }
            canvas.drawPath(path, paint)
        }
        is Shape.Box -> {
            paint.color = sh.color.toArgb(); paint.strokeWidth = sh.w
            val l = min(sh.start.x, sh.end.x); val t = min(sh.start.y, sh.end.y)
            canvas.drawRect(l, t, max(sh.start.x, sh.end.x), max(sh.start.y, sh.end.y), paint)
        }
        is Shape.CircleShape -> {
            paint.color = sh.color.toArgb(); paint.strokeWidth = sh.w
            val l = min(sh.start.x, sh.end.x); val t = min(sh.start.y, sh.end.y)
            canvas.drawOval(l, t, max(sh.start.x, sh.end.x), max(sh.start.y, sh.end.y), paint)
        }
        is Shape.Arrow -> {
            paint.color = sh.color.toArgb(); paint.strokeWidth = sh.w
            canvas.drawLine(sh.start.x, sh.start.y, sh.end.x, sh.end.y, paint)
            val angle = atan2((sh.end.y - sh.start.y).toDouble(), (sh.end.x - sh.start.x).toDouble())
            val headLen = sh.w * 4f
            val a1 = angle + Math.toRadians(35.0)
            val a2 = angle - Math.toRadians(35.0)
            canvas.drawLine(
                sh.end.x, sh.end.y,
                sh.end.x - headLen * kotlin.math.cos(a1).toFloat(),
                sh.end.y - headLen * kotlin.math.sin(a1).toFloat(), paint
            )
            canvas.drawLine(
                sh.end.x, sh.end.y,
                sh.end.x - headLen * kotlin.math.cos(a2).toFloat(),
                sh.end.y - headLen * kotlin.math.sin(a2).toFloat(), paint
            )
        }
        is Shape.Mosaic -> Unit
    }
}

// ===== ui helpers =====

@Composable
private fun ToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MiMoColors.Primary.copy(alpha = 0.25f)
                else Color.Transparent
            )
            .clickableNoRipple(onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            icon, label,
            tint = if (selected) MiMoColors.Primary else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = if (selected) MiMoColors.Primary else Color.White.copy(alpha = 0.7f)
        )
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(onClick) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                }
                onClick()
            }
        }
    )

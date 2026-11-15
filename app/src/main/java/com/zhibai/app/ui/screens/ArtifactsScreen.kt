package com.zhibai.app.ui.screens

import android.graphics.ImageDecoder
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 产物中心 — browse everything the agent produced in the workspace:
 *  - text/code/other → file cards with path + open action,
 *  - images (jpg/png/gif) → in-app zoomable viewer,
 *  - video → in-app player with fullscreen,
 *  - audio → in-app player with a seek bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(
    workspaceDir: File,
    onBack: () -> Unit,
    initialFile: String? = null
) {
    var files by remember { mutableStateOf(listOf<File>()) }
    var openFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(workspaceDir) {
        files = workspaceDir.walkTopDown()
            .filter { it.isFile && it.name != ".nomedia" && it.name != ".write_probe" }
            .filter { !it.path.contains("/skills/") }
            .sortedByDescending { it.lastModified() }
            .take(300)
            .toList()
        if (initialFile != null) {
            openFile = File(initialFile).takeIf { it.exists() }
        }
    }

    if (openFile != null) {
        BackHandler { openFile = null }
        ArtifactViewer(file = openFile!!, onBack = { openFile = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("产物中心", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "工作目录暂无产物",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { it.absolutePath }) { f ->
                    ArtifactFileCard(file = f, onClick = { openFile = f })
                }
            }
        }
    }
}

private fun File.kind(): String {
    val ext = extension.lowercase()
    return when {
        ext in listOf("jpg", "jpeg", "png", "webp", "gif") -> "image"
        ext in listOf("mp4", "mkv", "webm", "3gp", "mov") -> "video"
        ext in listOf("mp3", "wav", "ogg", "m4a", "aac", "flac") -> "audio"
        else -> "file"
    }
}

private fun File.typeLabel(): String = when (kind()) {
    "image" -> "图片"
    "video" -> "视频"
    "audio" -> "音频"
    else -> if (extension.isBlank()) "文件" else extension.uppercase(Locale.ROOT) + " 文件"
}

@Composable
private fun ArtifactFileCard(file: File, onClick: () -> Unit) {
    val kind = file.kind()
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (kind) {
                        "image" -> Icons.Default.Image
                        "video" -> Icons.Default.VideoFile
                        "audio" -> Icons.Default.AudioFile
                        else -> Icons.Default.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${file.typeLabel()} · ${formatSize(file.length())} · ${df.format(Date(file.lastModified()))}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    file.absolutePath,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                if (kind == "file") Icons.Default.OpenInNew else Icons.Default.PlayCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Full-screen viewer/player for one artifact. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtifactViewer(file: File, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        file.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Path strip — always visible
            Text(
                file.absolutePath,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            when (file.kind()) {
                "image" -> ImageZoomViewer(file)
                "video" -> VideoPlayerBox(file)
                "audio" -> AudioPlayerBox(file)
                else -> TextViewerBox(file)
            }
        }
    }
}

/** Pinch-zoom + pan image viewer (jpg/png/gif incl. animated). */
@Composable
private fun ImageZoomViewer(file: File) {
    val context = LocalContext.current
    val drawable = remember(file.absolutePath) {
        try {
            if (Build.VERSION.SDK_INT >= 28 && file.extension.lowercase() == "gif") {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeDrawable(source)
            } else {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let {
                    android.graphics.drawable.BitmapDrawable(context.resources, it)
                }
            }
        } catch (_: Exception) { null }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(file.absolutePath) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 8f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f; offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (drawable != null) {
            if (drawable is android.graphics.drawable.Animatable) {
                LaunchedEffect(file.absolutePath) { drawable.start() }
                AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            setImageDrawable(drawable)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offsetX; translationY = offsetY
                        }
                )
            } else {
                androidx.compose.foundation.Image(
                    bitmap = (drawable as android.graphics.drawable.BitmapDrawable).bitmap.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offsetX; translationY = offsetY
                        }
                )
            }
        } else {
            Text("无法解码该图片", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Default.ZoomIn,
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(20.dp),
            tint = Color.White.copy(alpha = 0.5f)
        )
    }
}

/** Video player (system VideoView + media controls). */
@Composable
private fun VideoPlayerBox(file: File) {
    var isPlaying by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoPath(file.absolutePath)
                    setMediaController(android.widget.MediaController(ctx).apply {
                        setAnchorView(this@apply)
                    })
                    setOnPreparedListener { it.start(); isPlaying = true }
                }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
    }
}

/** Audio player with a seek bar (MediaPlayer). */
@Composable
private fun AudioPlayerBox(file: File) {
    val context = LocalContext.current
    val player = remember(file.absolutePath) {
        try {
            android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
        } catch (_: Exception) { null }
    }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(player?.duration?.toLong() ?: 0L) }

    DisposableEffect(file.absolutePath) {
        onDispose { try { player?.release() } catch (_: Exception) {} }
    }

    // Position ticker
    LaunchedEffect(playing) {
        while (playing && player != null) {
            positionMs = player.currentPosition.toLong()
            if (player.currentPosition >= player.duration - 300) {
                playing = false
            }
            kotlinx.coroutines.delay(400)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.AudioFile,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(file.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
            onValueChange = { frac ->
                positionMs = (frac * durationMs).toLong()
                try { player?.seekTo(positionMs.toInt()) } catch (_: Exception) {}
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatMs(positionMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMs(durationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                try {
                    if (playing) player?.pause() else player?.start()
                    playing = !playing
                } catch (_: Exception) {}
            },
            enabled = player != null
        ) {
            Text(if (playing) "暂停" else "播放")
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Text/code viewer — full content, scrollable. */
@Composable
private fun TextViewerBox(file: File) {
    val text = remember(file.absolutePath) {
        try {
            if (file.length() < 500_000) file.readText() else "（文件过大，仅显示前 5000 字符）\n" + file.readText().take(5000)
        } catch (e: Exception) { "无法读取：${e.message}" }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }
    }
}

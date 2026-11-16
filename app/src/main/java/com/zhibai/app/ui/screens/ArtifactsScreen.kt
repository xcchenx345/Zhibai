package com.zhibai.app.ui.screens

import android.graphics.ImageDecoder
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
 * 产物中心 — every artifact generated in Xiaomi MiMo: search, category tabs
 * (全部/中间产物/产物/参考/文档/应用/其他 with counts), grid/list toggle, and
 * card grid with type badges + relative time (the reference design).
 * Tap any card → in-app viewer (image zoom/gif, video, audio, text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(
    workspaceDir: File,
    sessions: List<com.zhibai.app.data.model.ChatSession>,
    onBack: () -> Unit,
    initialFile: String? = null
) {
    var files by remember { mutableStateOf(listOf<File>()) }
    var openFile by remember { mutableStateOf<File?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("全部") }
    var gridMode by remember { mutableStateOf(true) }

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

    // Classification: produced-and-shown vs produced-but-never-shown (中间产物)
    val mentionedBlob = remember(sessions) {
        buildString {
            sessions.forEach { s -> s.messages.forEach { m ->
                append(m.content).append('\n').append(m.reasoningContent)
                m.fileCardPath?.let { append(it).append('\n') }
            } }
        }
    }
    val artifactPaths = remember(sessions) {
        val produced = mutableSetOf<String>()
        val shown = mutableSetOf<String>()
        val referenced = mutableSetOf<String>()
        sessions.forEach { s ->
            val a = s.artifacts
            a?.produced?.forEach { e ->
                produced.add(e.path)
                if (e.name.isNotBlank() && mentionedBlob.contains(e.name)) shown.add(e.path)
            }
            a?.referenced?.forEach { referenced.add(it.path) }
            a?.searches?.forEach { referenced.add(it.path) }
        }
        Triple(produced, shown, referenced)
    }

    val tabs = remember(files, artifactPaths, sessions) {
        val (produced, shown, referenced) = artifactPaths
        val hidden = produced - shown
        val docs = files.filter { it.extension.lowercase() in docExts }
        val apps = files.filter { it.extension.lowercase() in appExts }
        listOf(
            "全部" to files.size,
            "中间产物" to files.count { it.absolutePath in hidden },
            "产物" to files.count { it.absolutePath in shown },
            "参考" to files.count { it.absolutePath in referenced },
            "文档" to docs.size,
            "应用" to apps.size,
            "其他" to (files.size - docs.size - apps.size)
        )
    }

    val filtered = remember(files, activeTab, searchQuery, artifactPaths) {
        val (produced, shown, referenced) = artifactPaths
        val hidden = produced - shown
        files.filter { f ->
            val inTab = when (activeTab) {
                "中间产物" -> f.absolutePath in hidden
                "产物" -> f.absolutePath in shown
                "参考" -> f.absolutePath in referenced
                "文档" -> f.extension.lowercase() in docExts
                "应用" -> f.extension.lowercase() in appExts
                "其他" -> f.extension.lowercase() !in docExts && f.extension.lowercase() !in appExts
                else -> true
            }
            val inSearch = searchQuery.isBlank() ||
                f.name.contains(searchQuery, ignoreCase = true) ||
                f.absolutePath.contains(searchQuery, ignoreCase = true)
            inTab && inSearch
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
                title = { Text("产物中心", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                "这里汇总了你在 Xiaomi MiMo 里生成过的全部产物，可预览、下载或回到对话继续",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            // Search box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索产物", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "清除", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
            )
            // Category tabs + view toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.forEach { (label, count) ->
                        val selected = activeTab == label
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                )
                                .clickable { activeTab = label }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (count > 0) "$label $count" else label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.surface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                IconButton(onClick = { gridMode = !gridMode }) {
                    Icon(
                        if (gridMode) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = "视图切换",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (files.isEmpty()) "工作目录暂无产物" else "没有匹配的产物",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered.size, key = { filtered[it].absolutePath }) { i ->
                        ArtifactGridCard(file = filtered[i], onClick = { openFile = filtered[i] })
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.absolutePath }) { f ->
                        ArtifactFileCard(file = f, onClick = { openFile = f })
                    }
                }
            }
        }
    }
}

private val docExts = setOf("md", "txt", "pdf", "doc", "docx", "xls", "xlsx", "json", "kt", "java", "py", "js", "ts", "xml", "csv", "html", "htm")
private val appExts = setOf("apk", "rpk", "ab", "ipa", "aab", "xapk")

private fun File.kind(): String {
    val ext = extension.lowercase()
    return when {
        ext in listOf("jpg", "jpeg", "png", "webp", "gif") -> "image"
        ext in listOf("mp4", "mkv", "webm", "3gp", "mov") -> "video"
        ext in listOf("mp3", "wav", "ogg", "m4a", "aac", "flac") -> "audio"
        ext in listOf("html", "htm") -> "web"
        else -> "file"
    }
}

private fun File.typeLabel(): String = when (kind()) {
    "image" -> "图片"
    "video" -> "视频"
    "audio" -> "音频"
    "web" -> "落地页"
    else -> if (extension.isBlank()) "文件" else "文本"
}

private fun relTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 86_400_000L * 30 -> "${diff / 86_400_000} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }
}

/** Grid card per the reference: type badge, icon tile, filename, relative time. */
@Composable
private fun ArtifactGridCard(file: File, onClick: () -> Unit) {
    val kind = file.kind()
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(scheme.onSurface.copy(alpha = 0.08f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(scheme.onSurfaceVariant)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(file.typeLabel(), fontSize = 9.sp, color = scheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (kind) {
                        "image" -> Icons.Default.Image
                        "video" -> Icons.Default.VideoFile
                        "audio" -> Icons.Default.AudioFile
                        "web" -> Icons.Default.Public
                        else -> Icons.Default.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = scheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                file.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                relTime(file.lastModified()),
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
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

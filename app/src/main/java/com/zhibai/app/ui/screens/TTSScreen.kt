package com.zhibai.app.ui.screens

import android.content.ContentValues
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.data.api.TTSService
import com.zhibai.app.i18n.LangManager
import com.zhibai.app.ui.theme.MiMoColors
import com.zhibai.app.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

enum class TTSMode(val label: String, val apiModel: String) {
    READ("原版", "mimo-v2.5-tts"),
    DESIGN("设计", "mimo-v2.5-tts-voicedesign"),
    CLONE("克隆", "mimo-v2.5-tts-voiceclone"),
    DIRECTOR("导演", "mimo-v2.5-tts")
}

data class TTSVoice(val name: String, val id: String)

val presetVoices = listOf(
    TTSVoice("冰糖 (默认)", "冰糖"),
    TTSVoice("茉莉", "茉莉"),
    TTSVoice("苏打", "苏打"),
    TTSVoice("白桦", "白桦"),
    TTSVoice("Mia (EN)", "Mia"),
    TTSVoice("Chloe (EN)", "Chloe"),
    TTSVoice("Milo (EN)", "Milo"),
    TTSVoice("Dean (EN)", "Dean")
)

// Localized at call time (functions, not vals) so a language switch takes effect.
private fun tabTitles() = listOf(
    LangManager.t("tts.tab.synth", "语音合成"),
    LangManager.t("tts.tab.design", "音色设计"),
    LangManager.t("tts.tab.clone", "音色克隆")
)

private fun synthFeatures() = listOf(
    Triple(
        LangManager.t("tts.feature.express.title", "丰富的多元表达"),
        LangManager.t("tts.feature.express.sub", "方言、角色、唱歌"),
        Icons.Default.RecordVoiceOver
    ),
    Triple(
        LangManager.t("tts.feature.emotion.title", "细粒度的情绪调节"),
        LangManager.t("tts.feature.emotion.sub", "一句话多种情绪"),
        Icons.Default.SentimentSatisfied
    ),
    Triple(
        LangManager.t("tts.feature.text.title", "强大的文本理解"),
        LangManager.t("tts.feature.text.sub", "导演演绎、高级富文本"),
        Icons.Default.AutoStories
    )
)

private fun styleExamples() = listOf(
    LangManager.t("tts.styleEx.1", "东北话"),
    LangManager.t("tts.styleEx.2", "四川话"),
    LangManager.t("tts.styleEx.3", "河南话"),
    LangManager.t("tts.styleEx.4", "广东话")
)
private fun designExamples() = listOf(
    LangManager.t("tts.designEx.1", "武侠小说"),
    LangManager.t("tts.designEx.2", "高能广告"),
    LangManager.t("tts.designEx.3", "新闻直播"),
    LangManager.t("tts.designEx.4", "DJ 电台")
)
private fun tagModeLabels() = listOf(
    LangManager.t("tts.tag.style", "风格标签"),
    LangManager.t("tts.tag.audio", "音频标签"),
    LangManager.t("tts.tag.sing", "唱歌")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TTSScreen(viewModel: ChatViewModel, initialMode: String = "read") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiKey by viewModel.apiKey.collectAsState()

    var selectedTab by remember {
        mutableStateOf(
            when (initialMode) {
                "design" -> 1
                "clone" -> 2
                else -> 0
            }
        )
    }
    var directorMode by remember { mutableStateOf(initialMode == "director") }
    fun syncMode(): TTSMode = when {
        selectedTab == 1 -> TTSMode.DESIGN
        selectedTab == 2 -> TTSMode.CLONE
        directorMode -> TTSMode.DIRECTOR
        else -> TTSMode.READ
    }
    var selectedMode by remember {
        mutableStateOf(
            when (initialMode) {
                "design" -> TTSMode.DESIGN
                "clone" -> TTSMode.CLONE
                "director" -> TTSMode.DIRECTOR
                else -> TTSMode.READ
            }
        )
    }
    var selectedVoice by remember { mutableStateOf(presetVoices[0]) }
    var inputText by remember { mutableStateOf("") }
    var stylePrompt by remember { mutableStateOf("") }
    // Director free-form: 【角色】…【场景】…【指导】… (user instruction) + inputText = lines to speak
    var directorText by remember { mutableStateOf("") }
    var tagMode by remember { mutableStateOf(0) }
    var isGenerating by remember { mutableStateOf(false) }
    var audioData by remember { mutableStateOf<ByteArray?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playProgress by remember { mutableStateOf(0f) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var showVoiceMenu by remember { mutableStateOf(false) }
    var showTagMenu by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var cloneAudioData by remember { mutableStateOf<ByteArray?>(null) }
    var cloneAudioName by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var audioRecord by remember { mutableStateOf<android.media.AudioRecord?>(null) }
    var recorderThread by remember { mutableStateOf<Thread?>(null) }

    val ttsService = remember { TTSService() }
    LaunchedEffect(apiKey) { ttsService.updateApiKey(apiKey) }

    // Audio file picker for clone mode
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                cloneAudioData = inputStream?.readBytes()
                inputStream?.close()
                cloneAudioName = "Uploaded audio"
            } catch (_: Exception) {
                errorMsg = "Failed to read audio file"
            }
        }
    }

    fun startRecording() {
        try {
            val file = java.io.File(context.cacheDir, "clone_record.wav")
            val sampleRate = 16000
            val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val recorder = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize
            )

            if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
                errorMsg = "Failed to initialize recorder"
                return
            }

            val pcmData = java.io.ByteArrayOutputStream()
            isRecording = true
            audioRecord = recorder

            recorderThread = Thread {
                recorder.startRecording()
                val buffer = ShortArray(1024)
                while (isRecording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val bytes = ByteArray(read * 2)
                        for (i in 0 until read) {
                            bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                        }
                        pcmData.write(bytes)
                    }
                }
                recorder.stop()
                recorder.release()

                // Write WAV file
                val pcmBytes = pcmData.toByteArray()
                val wavFile = java.io.File(context.cacheDir, "clone_record.wav")
                writeWavFile(wavFile, pcmBytes, sampleRate, 1, 16)
            }
            recorderThread?.start()
        } catch (e: Exception) {
            errorMsg = "Recording failed: ${e.message}"
            isRecording = false
        }
    }

    fun stopRecording() {
        isRecording = false
        recorderThread?.join(2000)
        recorderThread = null
        audioRecord = null

        val file = java.io.File(context.cacheDir, "clone_record.wav")
        if (file.exists() && file.length() > 44) {
            cloneAudioData = file.readBytes()
            cloneAudioName = "Recorded audio"
            file.delete()
        }
    }

    fun stopPlayback() {
        val track = audioTrack
        // Null out first so queued onPeriodicNotification/onMarkerReached
        // see a mismatch and bail — calling into a released AudioTrack throws.
        audioTrack = null
        isPlaying = false
        playProgress = 0f
        if (track != null) {
            try {
                track.setPlaybackPositionUpdateListener(null)
            } catch (_: Exception) {}
            try {
                track.stop()
            } catch (_: Exception) {}
            try {
                track.release()
            } catch (_: Exception) {}
        }
    }

    fun playAudio(data: ByteArray) {
        stopPlayback()
        val totalFrames = (data.size / 2).coerceAtLeast(1)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(data.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        audioTrack = track
        track.write(data, 0, data.size)
        // Periodic progress every ~100ms
        track.setNotificationMarkerPosition(totalFrames)
        track.setPositionNotificationPeriod((24000 / 10).coerceAtMost(totalFrames))
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) {
                if (t !== audioTrack) return
                audioTrack = null
                try {
                    t.stop()
                } catch (_: Exception) {}
                try {
                    t.release()
                } catch (_: Exception) {}
                isPlaying = false
                playProgress = 1f
            }
            override fun onPeriodicNotification(t: AudioTrack) {
                if (t !== audioTrack) return
                try {
                    playProgress = (t.playbackHeadPosition.toFloat() / totalFrames)
                        .coerceIn(0f, 1f)
                } catch (_: IllegalStateException) {
                    // Released between dispatch and callback — ignore.
                }
            }
        })
        track.play()
        isPlaying = true
        playProgress = 0f
    }

    // Pause/resume on the live AudioTrack; falls back to a fresh play when the track is gone.
    fun togglePlay(data: ByteArray) {
        val track = audioTrack
        if (track == null) {
            playAudio(data)
            return
        }
        if (isPlaying) {
            try {
                track.pause()
            } catch (_: Exception) {}
            isPlaying = false
        } else {
            try {
                track.play()
                isPlaying = true
            } catch (_: Exception) {
                stopPlayback()
                playAudio(data)
            }
        }
    }

    // Relative seek while paused or playing (MODE_STATIC head position).
    fun seekBy(data: ByteArray, deltaSec: Float) {
        val track = audioTrack ?: return
        val totalFrames = (data.size / 2).coerceAtLeast(1)
        val target = (track.playbackHeadPosition + (deltaSec * 24000).toInt())
            .coerceIn(0, totalFrames)
        try {
            if (isPlaying) track.pause()
            track.setPlaybackHeadPosition(target)
            playProgress = target.toFloat() / totalFrames
            if (isPlaying) track.play()
        } catch (_: Exception) {}
    }

    fun generateTTS() {
        if (inputText.isBlank() && selectedMode != TTSMode.DESIGN) return
        if (selectedMode == TTSMode.DESIGN && stylePrompt.isBlank()) return
        if (selectedMode == TTSMode.DIRECTOR && (directorText.isBlank() || inputText.isBlank())) return
        if (selectedMode == TTSMode.CLONE && cloneAudioData == null) return

        scope.launch(Dispatchers.IO) {
            isGenerating = true
            errorMsg = null
            audioData = null
            stopPlayback()

            try {
                val messages = when (selectedMode) {
                    TTSMode.READ -> if (stylePrompt.isBlank()) {
                        listOf(
                            com.zhibai.app.data.model.Message(role = "assistant", content = inputText)
                        )
                    } else {
                        // stylePrompt feeds the merged 语音合成 entry as the style instruction
                        listOf(
                            com.zhibai.app.data.model.Message(role = "user", content = stylePrompt),
                            com.zhibai.app.data.model.Message(role = "assistant", content = inputText)
                        )
                    }
                    TTSMode.DESIGN -> listOf(
                        com.zhibai.app.data.model.Message(role = "user", content = stylePrompt),
                        com.zhibai.app.data.model.Message(role = "assistant", content = inputText.ifBlank { "请用这个声音说一句话。" })
                    )
                    TTSMode.CLONE -> listOf(
                        com.zhibai.app.data.model.Message(role = "user", content = ""),
                        com.zhibai.app.data.model.Message(role = "assistant", content = inputText)
                    )
                    // Director: user = free-form 【角色】【场景】【指导】; assistant = lines to speak
                    TTSMode.DIRECTOR -> listOf(
                        com.zhibai.app.data.model.Message(role = "user", content = directorText),
                        com.zhibai.app.data.model.Message(role = "assistant", content = inputText)
                    )
                }

                val audioConfig = when (selectedMode) {
                    TTSMode.READ, TTSMode.DIRECTOR -> mapOf("format" to "pcm16", "voice" to selectedVoice.id)
                    TTSMode.DESIGN -> mapOf("format" to "pcm16")
                    TTSMode.CLONE -> {
                        val audio = cloneAudioData
                        if (audio == null) {
                            errorMsg = LangManager.t("tts.needRefAudio", "请先上传或录制参考音频")
                            isGenerating = false
                            return@launch
                        }
                        val base64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
                        mapOf("format" to "pcm16", "voice" to "data:audio/wav;base64,$base64")
                    }
                }

                val request = com.zhibai.app.data.model.TTSRequestDirect(
                    model = selectedMode.apiModel,
                    messages = messages,
                    audio = audioConfig
                )

                val json = com.google.gson.Gson().toJson(request)
                val body = json.toRequestBody("application/json".toMediaType())
                val httpRequest = okhttp3.Request.Builder()
                    .url("https://api.xiaomimimo.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = ttsService.getClient().newCall(httpRequest).execute()
                if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: ""
                    val resp = com.google.gson.Gson().fromJson(respBody, com.zhibai.app.data.api.TTSResponse::class.java)
                    val base64 = resp.choices.firstOrNull()?.message?.audio?.data
                    if (base64 != null) {
                        audioData = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    } else {
                        errorMsg = "No audio returned"
                    }
                } else {
                    errorMsg = "HTTP ${response.code}: ${response.message}"
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "Unknown error"
            } finally {
                isGenerating = false
            }
        }
    }

    fun saveAudio() {
        val data = audioData ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "MiMo_TTS_$timestamp.wav"
                val wavData = pcmToWav(data, 24000, 1, 16)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MiMo")
                }
                val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(wavData) }
                    scope.launch(Dispatchers.Main) {
                        Toast.makeText(context, "Saved to Music/MiMo/$fileName", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val canGenerate = when (selectedMode) {
        TTSMode.READ -> inputText.isNotBlank()
        TTSMode.DESIGN -> stylePrompt.isNotBlank()
        TTSMode.CLONE -> inputText.isNotBlank() && cloneAudioData != null
        TTSMode.DIRECTOR -> directorText.isNotBlank() && inputText.isNotBlank()
    }
    val waveformAmps = remember(audioData) { audioData?.let { computeWaveformAmps(it) } ?: emptyList() }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        focusedIndicatorColor = MaterialTheme.colorScheme.onSurface,
        unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
        cursorColor = MaterialTheme.colorScheme.onSurface
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Page tabs ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabTitles().forEachIndexed { index, title ->
                val selected = selectedTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable {
                            selectedTab = index
                            selectedMode = syncMode()
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> {
                // ── 语音合成 ──────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        LangManager.t("tts.synth.title", "Xiaomi MIMO 语音合成"),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    synthFeatures().forEach { (title, sub, icon) ->
                        Surface(
                            modifier = Modifier.width(170.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    sub,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                SectionCard {
                    Text(LangManager.t("tts.tagControl", "音频标签控制"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Style-control dropdown (drives stylePrompt semantics)
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                onClick = { showTagMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(tagModeLabels()[tagMode], fontSize = 13.sp)
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = showTagMenu,
                                onDismissRequest = { showTagMenu = false }
                            ) {
                                tagModeLabels().forEachIndexed { i, label ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = { tagMode = i; showTagMenu = false }
                                    )
                                }
                            }
                        }
                        // Voice dropdown (presetVoices)
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                onClick = { showVoiceMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        selectedVoice.name,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = showVoiceMenu,
                                onDismissRequest = { showVoiceMenu = false }
                            ) {
                                presetVoices.forEach { voice ->
                                    DropdownMenuItem(
                                        text = { Text(voice.name) },
                                        onClick = { selectedVoice = voice; showVoiceMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    // Tag-type chips + director toggle
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tagModeLabels().forEachIndexed { i, label ->
                            ToggleChip(label = label, selected = tagMode == i) { tagMode = i }
                        }
                        ToggleChip(label = LangManager.t("tts.director", "导演演绎"), selected = directorMode) {
                            directorMode = !directorMode
                            selectedMode = syncMode()
                        }
                    }
                }

                SectionCard {
                    RequiredLabel(LangManager.t("tts.styleLabel", "风格指令"), required = false)
                    OutlinedTextField(
                        value = stylePrompt,
                        onValueChange = { stylePrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(LangManager.t("tts.stylePlaceholder", "请用 1-3 个短词描述"), fontSize = 13.sp) },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        styleExamples().forEach { ex ->
                            ExampleChip(ex) { stylePrompt = ex }
                        }
                    }
                    Text(
                        "${stylePrompt.length}/1024",
                        fontSize = 11.sp,
                        color = if (stylePrompt.length > 1024) MiMoColors.Error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )

                    if (directorMode) {
                        RequiredLabel(LangManager.t("tts.directorLabel", "导演指令"), required = false)
                        OutlinedTextField(
                            value = directorText,
                            onValueChange = { directorText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    LangManager.t(
                                        "tts.directorPlaceholder",
                                        "【角色】青年男性，自信张扬…\n【场景】刚查到竞赛成绩，忍不住得意惊呼…\n【指导】语速偏快，高亢明亮…"
                                    ),
                                    fontSize = 12.sp
                                )
                            },
                            minLines = 3,
                            maxLines = 8,
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors
                        )
                        Text(
                            LangManager.t("tts.directorTip", "也可以一句话描述，例如：用轻快上扬的语调向领导报喜，语速稍快，带着压抑不住的激动。"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SectionCard {
                    RequiredLabel(LangManager.t("tts.textLabel", "播报文本"))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(LangManager.t("tts.textHintSynth", "输入要合成的文本，开启试听之旅吧"), fontSize = 13.sp) },
                        minLines = 3,
                        maxLines = 8,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors
                    )
                    Text(
                        LangManager.t("tts.charCount", "{n} 字").replace("{n}", inputText.length.toString()),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }

                ErrorBanner(errorMsg)

                SynthesisResultPanel(
                    audioData = audioData,
                    amps = waveformAmps,
                    isPlaying = isPlaying,
                    isGenerating = isGenerating,
                    playProgress = playProgress,
                    generateEnabled = canGenerate,
                    emptyHint = false,
                    onGenerate = { generateTTS() },
                    onTogglePlay = { audioData?.let { togglePlay(it) } },
                    onSeek = { delta -> audioData?.let { seekBy(it, delta) } },
                    onSave = { saveAudio() }
                )
            }

            1 -> {
                // ── 音色设计 ──────────────────────────────────────
                Text(
                    LangManager.t("tts.design.title", "Xiaomi MIMO 音色设计"),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    LangManager.t("tts.design.subtitle", "自然语言描绘理想声线，AI 一键定制专属音色，轻松拥有独特音质。"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SectionCard {
                    RequiredLabel(LangManager.t("tts.voiceDescLabel", "音色描述"))
                    OutlinedTextField(
                        value = stylePrompt,
                        onValueChange = { stylePrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(LangManager.t("tts.voiceDescPlaceholder", "例如：年轻女性，温暖温柔，略带气声……"), fontSize = 13.sp) },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        designExamples().forEach { ex ->
                            ExampleChip(ex) { stylePrompt = ex }
                        }
                    }
                    Button(
                        onClick = { generateTTS() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = stylePrompt.isNotBlank() && !isGenerating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(LangManager.t("tts.generate", "生成"))
                    }
                }

                SectionCard {
                    RequiredLabel(LangManager.t("tts.textLabel", "播报文本"))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(LangManager.t("tts.textHint", "输入播报文本，开启试听之旅吧"), fontSize = 13.sp) },
                        minLines = 3,
                        maxLines = 8,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors
                    )
                    Text(
                        LangManager.t("tts.charCount", "{n} 字").replace("{n}", inputText.length.toString()),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }

                ErrorBanner(errorMsg)

                SynthesisResultPanel(
                    audioData = audioData,
                    amps = waveformAmps,
                    isPlaying = isPlaying,
                    isGenerating = isGenerating,
                    playProgress = playProgress,
                    generateEnabled = canGenerate,
                    emptyHint = true,
                    onGenerate = { generateTTS() },
                    onTogglePlay = { audioData?.let { togglePlay(it) } },
                    onSeek = { delta -> audioData?.let { seekBy(it, delta) } },
                    onSave = { saveAudio() }
                )
            }

            else -> {
                // ── 音色克隆 ──────────────────────────────────────
                Text(
                    LangManager.t("tts.clone.title", "Xiaomi MIMO 音色克隆"),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    LangManager.t("tts.clone.subtitle", "一段原声即刻复刻，语音内容创作，随心随意更自由。"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SectionCard {
                    RequiredLabel(LangManager.t("tts.cloneAudioLabel", "克隆音频"))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DashedActionCard(
                            modifier = Modifier.weight(1f),
                            icon = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            title = LangManager.t(
                                if (isRecording) "tts.stopRecording" else "tts.recordAudio",
                                if (isRecording) "停止录制" else "录制音频"
                            ),
                            subtitle = LangManager.t(
                                if (isRecording) "tts.recordingHint" else "tts.recordHint",
                                if (isRecording) "录制中…再次点击结束" else "请在安静环境，录制 5-30s 的音频"
                            ),
                            highlighted = isRecording,
                            onClick = { if (isRecording) stopRecording() else startRecording() }
                        )
                        DashedActionCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Upload,
                            title = LangManager.t("tts.uploadAudio", "上传音频"),
                            subtitle = LangManager.t("tts.uploadHint", "请上传 5-30s、10MB 以内的音频"),
                            highlighted = false,
                            onClick = { audioPicker.launch("audio/*") }
                        )
                    }
                    if (cloneAudioName != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    cloneAudioName!!,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { cloneAudioData = null; cloneAudioName = null },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                SectionCard {
                    RequiredLabel(LangManager.t("tts.textLabel", "播报文本"))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(LangManager.t("tts.textHint", "输入播报文本，开启试听之旅吧"), fontSize = 13.sp) },
                        minLines = 3,
                        maxLines = 8,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors
                    )
                    Text(
                        LangManager.t("tts.charCount", "{n} 字").replace("{n}", inputText.length.toString()),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }

                ErrorBanner(errorMsg)

                SynthesisResultPanel(
                    audioData = audioData,
                    amps = waveformAmps,
                    isPlaying = isPlaying,
                    isGenerating = isGenerating,
                    playProgress = playProgress,
                    generateEnabled = canGenerate,
                    emptyHint = true,
                    onGenerate = { generateTTS() },
                    onTogglePlay = { audioData?.let { togglePlay(it) } },
                    onSeek = { delta -> audioData?.let { seekBy(it, delta) } },
                    onSave = { saveAudio() }
                )
            }
        }
    }
}

// ── UI building blocks ──────────────────────────────────────────

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun RequiredLabel(text: String, required: Boolean = true) {
    Row {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (required) {
            Text("*", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MiMoColors.Error)
        }
    }
}

@Composable
private fun ExampleChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .drawBehind {
                val strokePx = 1.dp.toPx()
                drawRoundRect(
                    color = Color.Gray.copy(alpha = 0.4f),
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(size.height / 2 - strokePx / 2f),
                    style = Stroke(width = strokePx)
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .drawBehind {
                if (!selected) {
                    val strokePx = 1.dp.toPx()
                    drawRoundRect(
                        color = Color.Gray.copy(alpha = 0.4f),
                        topLeft = Offset(strokePx / 2f, strokePx / 2f),
                        size = Size(size.width - strokePx, size.height - strokePx),
                        cornerRadius = CornerRadius(size.height / 2 - strokePx / 2f),
                        style = Stroke(width = strokePx)
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ErrorBanner(errorMsg: String?) {
    errorMsg?.let {
        Surface(
            color = MiMoColors.Error.copy(alpha = 0.1f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MiMoColors.Error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(it, fontSize = 13.sp, color = MiMoColors.Error)
            }
        }
    }
}

private fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp): Modifier = this.drawBehind {
    val strokePx = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(strokePx / 2f, strokePx / 2f),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius((radius.toPx() - strokePx / 2f).coerceAtLeast(0f)),
        style = Stroke(
            width = strokePx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
        )
    )
}

@Composable
private fun DashedActionCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    val border = if (highlighted) MiMoColors.Error else MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (highlighted) MiMoColors.Error.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .dashedBorder(border, 1.dp, 14.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (highlighted) MiMoColors.Error else MaterialTheme.colorScheme.onSurface
        )
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            subtitle,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun SynthesisResultPanel(
    audioData: ByteArray?,
    amps: List<Float>,
    isPlaying: Boolean,
    isGenerating: Boolean,
    playProgress: Float,
    generateEnabled: Boolean,
    emptyHint: Boolean,
    onGenerate: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onSave: () -> Unit
) {
    val totalSec = audioData?.let { it.size / 2.0 / 24000.0 } ?: 0.0
    val curSec = totalSec * playProgress

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    LangManager.t("tts.resultTitle", "合成结果"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (audioData != null) {
                    Text(
                        "${audioData.size / 1024} KB",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (audioData == null && emptyHint) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        LangManager.t("tts.emptyTitle", "还未生成语音"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        LangManager.t("tts.textHint", "输入播报文本，开启试听之旅吧"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Waveform: static dot grid while idle, amplitude bars with playhead otherwise
            WaveformCanvas(
                amps = amps,
                hasAudio = audioData != null,
                progress = playProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            )

            LinearProgressIndicator(
                progress = { playProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                gapSize = 0.dp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatMmSs(curSec),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatMmSs(totalSec),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onSeek(-10f) },
                    enabled = audioData != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.FastRewind,
                        contentDescription = LangManager.t("tts.seekBack", "后退"),
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                FilledIconButton(
                    onClick = onTogglePlay,
                    enabled = audioData != null,
                    modifier = Modifier.size(54.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = LangManager.t(
                            if (isPlaying) "common.pause" else "common.play",
                            if (isPlaying) "暂停" else "播放"
                        ),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                IconButton(
                    onClick = { onSeek(10f) },
                    enabled = audioData != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.FastForward,
                        contentDescription = LangManager.t("tts.seekForward", "前进"),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onGenerate,
                    modifier = Modifier.weight(1f),
                    enabled = generateEnabled && !isGenerating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(LangManager.t("tts.generating", "生成中……"))
                    } else {
                        Text(LangManager.t("tts.generateVoice", "生成语音"))
                    }
                }
                OutlinedButton(
                    onClick = onSave,
                    enabled = audioData != null,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = LangManager.t("common.download", "下载"),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WaveformCanvas(
    amps: List<Float>,
    hasAudio: Boolean,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val idleDot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val playedBar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val idleBar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (!hasAudio) {
            val cols = 42
            val rows = 5
            val dx = w / cols
            val dy = h / rows
            for (c in 0 until cols) {
                for (r in 0 until rows) {
                    drawCircle(
                        color = idleDot,
                        radius = 1.6f,
                        center = Offset(dx * c + dx / 2f, dy * r + dy / 2f)
                    )
                }
            }
        } else if (amps.isNotEmpty()) {
            val n = amps.size
            val slot = w / n
            val barW = (slot * 0.55f).coerceAtLeast(2f)
            for (i in 0 until n) {
                val barH = amps[i] * h * 0.82f
                val x = slot * i + (slot - barW) / 2f
                drawRoundRect(
                    color = if (i.toFloat() / n <= progress) playedBar else idleBar,
                    topLeft = Offset(x, (h - barH) / 2f),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2f)
                )
            }
        }
    }
}

// ── Audio helpers ───────────────────────────────────────────────

private fun computeWaveformAmps(data: ByteArray, bars: Int = 48): List<Float> {
    val samples = data.size / 2
    if (samples <= 0) return emptyList()
    return (0 until bars).map { i ->
        val start = (i.toLong() * samples / bars).toInt()
        val end = (((i + 1).toLong() * samples / bars).toInt()).coerceAtMost(samples)
        var max = 0
        var s = start
        while (s < end) {
            val lo = data[s * 2].toInt() and 0xFF
            val hi = data[s * 2 + 1].toInt() shl 8
            val v = (hi or lo).toShort().toInt()
            val a = abs(v)
            if (a > max) max = a
            s += 2
        }
        (max / 32768f).coerceIn(0.06f, 1f)
    }
}

private fun formatMmSs(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcmData.size
    val totalSize = 44 + dataSize

    val header = ByteArray(44)
    // RIFF header
    header[0] = 'R'.code.toByte()
    header[1] = 'I'.code.toByte()
    header[2] = 'F'.code.toByte()
    header[3] = 'F'.code.toByte()
    writeInt(header, 4, totalSize - 8)
    header[8] = 'W'.code.toByte()
    header[9] = 'A'.code.toByte()
    header[10] = 'V'.code.toByte()
    header[11] = 'E'.code.toByte()
    // fmt chunk
    header[12] = 'f'.code.toByte()
    header[13] = 'm'.code.toByte()
    header[14] = 't'.code.toByte()
    header[15] = ' '.code.toByte()
    writeInt(header, 16, 16) // chunk size
    writeShort(header, 20, 1) // PCM format
    writeShort(header, 22, channels)
    writeInt(header, 24, sampleRate)
    writeInt(header, 28, byteRate)
    writeShort(header, 32, blockAlign)
    writeShort(header, 34, bitsPerSample)
    // data chunk
    header[36] = 'd'.code.toByte()
    header[37] = 'a'.code.toByte()
    header[38] = 't'.code.toByte()
    header[39] = 'a'.code.toByte()
    writeInt(header, 40, dataSize)

    return header + pcmData
}

private fun writeInt(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value and 0xFF).toByte()
    data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    data[offset + 2] = ((value shr 16) and 0xFF).toByte()
    data[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun writeShort(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value and 0xFF).toByte()
    data[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun writeWavFile(file: java.io.File, pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
    val header = pcmToWav(pcmData, sampleRate, channels, bitsPerSample)
    file.writeBytes(header)
}

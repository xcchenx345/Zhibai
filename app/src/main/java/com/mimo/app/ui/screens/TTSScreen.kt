package com.mimo.app.ui.screens

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
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mimo.app.data.api.TTSService
import com.mimo.app.ui.theme.MiMoColors
import com.mimo.app.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TTSScreen(viewModel: ChatViewModel, initialMode: String = "read") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiKey by viewModel.apiKey.collectAsState()

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
    var isGenerating by remember { mutableStateOf(false) }
    var audioData by remember { mutableStateOf<ByteArray?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playProgress by remember { mutableStateOf(0f) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var showVoiceMenu by remember { mutableStateOf(false) }
    var showModeMenu by remember { mutableStateOf(false) }
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
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        isPlaying = false
        playProgress = 0f
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
                t.stop()
                t.release()
                audioTrack = null
                isPlaying = false
                playProgress = 1f
            }
            override fun onPeriodicNotification(t: AudioTrack) {
                playProgress = (t.playbackHeadPosition.toFloat() / totalFrames).coerceIn(0f, 1f)
            }
        })
        track.play()
        isPlaying = true
        playProgress = 0f
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
                    TTSMode.READ -> listOf(
                        com.mimo.app.data.model.Message(role = "assistant", content = inputText)
                    )
                    TTSMode.DESIGN -> listOf(
                        com.mimo.app.data.model.Message(role = "user", content = stylePrompt),
                        com.mimo.app.data.model.Message(role = "assistant", content = inputText.ifBlank { "请用这个声音说一句话。" })
                    )
                    TTSMode.CLONE -> listOf(
                        com.mimo.app.data.model.Message(role = "user", content = ""),
                        com.mimo.app.data.model.Message(role = "assistant", content = inputText)
                    )
                    // Director: user = free-form 【角色】【场景】【指导】; assistant = lines to speak
                    TTSMode.DIRECTOR -> listOf(
                        com.mimo.app.data.model.Message(role = "user", content = directorText),
                        com.mimo.app.data.model.Message(role = "assistant", content = inputText)
                    )
                }

                val audioConfig = when (selectedMode) {
                    TTSMode.READ, TTSMode.DIRECTOR -> mapOf("format" to "pcm16", "voice" to selectedVoice.id)
                    TTSMode.DESIGN -> mapOf("format" to "pcm16")
                    TTSMode.CLONE -> {
                        val audio = cloneAudioData
                        if (audio == null) {
                            errorMsg = "请先上传或录制参考音频"
                            isGenerating = false
                            return@launch
                        }
                        val base64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
                        mapOf("format" to "pcm16", "voice" to "data:audio/wav;base64,$base64")
                    }
                }

                val request = com.mimo.app.data.model.TTSRequestDirect(
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
                    val resp = com.google.gson.Gson().fromJson(respBody, com.mimo.app.data.api.TTSResponse::class.java)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode selector
        Text("模式", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TTSMode.entries.forEach { mode ->
                FilterChip(
                    onClick = { selectedMode = mode },
                    label = { Text(mode.label, fontSize = 13.sp) },
                    selected = selectedMode == mode,
                    leadingIcon = {
                            Icon(
                                when (mode) {
                                    TTSMode.READ -> Icons.Default.VolumeUp
                                    TTSMode.DESIGN -> Icons.Default.Palette
                                    TTSMode.CLONE -> Icons.Default.ContentCopy
                                    TTSMode.DIRECTOR -> Icons.Default.Movie
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                    }
                )
            }
        }

        // Voice selector (not for DESIGN or CLONE mode)
        if (selectedMode != TTSMode.DESIGN && selectedMode != TTSMode.CLONE) {
            Text("音色", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedCard(
                    onClick = { showVoiceMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(selectedVoice.name, fontSize = 15.sp)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }
                DropdownMenu(expanded = showVoiceMenu, onDismissRequest = { showVoiceMenu = false }) {
                    presetVoices.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice.name) },
                            onClick = { selectedVoice = voice; showVoiceMenu = false }
                        )
                    }
                }
            }
        }

        // Clone mode: audio upload/record
        if (selectedMode == TTSMode.CLONE) {
            Text("语音样本", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { audioPicker.launch("audio/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("上传")
                }
                OutlinedButton(
                    onClick = { if (isRecording) stopRecording() else startRecording() },
                    modifier = Modifier.weight(1f),
                    colors = if (isRecording) ButtonDefaults.outlinedButtonColors(
                        containerColor = MiMoColors.Error.copy(alpha = 0.1f)
                    ) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isRecording) MiMoColors.Error else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isRecording) "Stop" else "Record")
                }
            }
            if (cloneAudioName != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MiMoColors.Primary.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MiMoColors.Primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(cloneAudioName!!, fontSize = 13.sp, color = MiMoColors.Primary, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { cloneAudioData = null; cloneAudioName = null },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        // Style prompt for DESIGN mode
        if (selectedMode == TTSMode.DESIGN) {
            Text("音色描述", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = stylePrompt,
                onValueChange = { stylePrompt = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如：年轻女性，温暖温柔，略带气声……") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp)
            )
        }

        // Director free-form instruction + spoken lines
        if (selectedMode == TTSMode.DIRECTOR) {
            Text("导演指令（自然语言）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = directorText,
                onValueChange = { directorText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("【角色】青年男性，自信张扬…\n【场景】刚查到竞赛成绩，忍不住得意惊呼…\n【指导】语速偏快，高亢明亮…") },
                minLines = 4,
                maxLines = 12,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "也可以一句话描述，例如：用轻快上扬的语调向领导报喜，语速稍快，带着压抑不住的激动。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            when (selectedMode) {
                TTSMode.READ -> "要朗读的文本"
                TTSMode.DESIGN -> "试听文本（可选）"
                TTSMode.CLONE -> "要说的话"
                TTSMode.DIRECTOR -> "台词"
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(when (selectedMode) {
                    TTSMode.READ -> "输入要朗读的文字…"
                    TTSMode.DESIGN -> "留空则自动预览…"
                    TTSMode.CLONE -> "输入要合成的文字…"
                    TTSMode.DIRECTOR -> "输入要演绎的台词…"
                })
            },
            minLines = 3,
            maxLines = 8,
            shape = RoundedCornerShape(12.dp)
        )

        // Generate button
        Button(
            onClick = { generateTTS() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isGenerating && when (selectedMode) {
                TTSMode.READ -> inputText.isNotBlank()
                TTSMode.DESIGN -> stylePrompt.isNotBlank()
                TTSMode.CLONE -> inputText.isNotBlank() && cloneAudioData != null
                TTSMode.DIRECTOR -> directorText.isNotBlank() && inputText.isNotBlank()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MiMoColors.Primary)
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MiMoColors.OnPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("生成中……")
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("生成")
            }
        }

        // Error
        errorMsg?.let {
            Surface(
                color = MiMoColors.Error.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MiMoColors.Error, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(it, fontSize = 13.sp, color = MiMoColors.Error)
                }
            }
        }

        // Audio player
        audioData?.let { data ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("音频已就绪", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${data.size / 1024} KB · ${(data.size / 2.0 / 24000).toInt()}s",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Play/Pause
                            FilledIconButton(
                                onClick = {
                                    if (isPlaying) stopPlayback()
                                    else playAudio(data)
                                }
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                            }
                            // Download
                            FilledIconButton(onClick = { saveAudio() }) {
                                Icon(Icons.Default.Download, contentDescription = null)
                            }
                        }
                    }
                    if (isPlaying || playProgress > 0f) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { playProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = MiMoColors.Primary,
                            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            gapSize = 0.dp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val totalSec = data.size / 2.0 / 24000.0
                        val curSec = totalSec * playProgress
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("%.1fs".format(curSec), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("%.1fs".format(totalSec), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
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

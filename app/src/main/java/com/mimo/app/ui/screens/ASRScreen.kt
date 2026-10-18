package com.mimo.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mimo.app.data.api.TTSService
import com.mimo.app.ui.theme.MiMoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ASRScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ttsService = remember { TTSService() }

    // Load API key from prefs
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("mimo_settings", android.content.Context.MODE_PRIVATE)
        ttsService.updateApiKey(prefs.getString("api_key", "") ?: "")
    }

    var isRecording by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var audioJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var uploading by remember { mutableStateOf(false) }

    val audioFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && !uploading && !isRecording) {
            uploading = true
            error = null
            result = ""
            audioJob = scope.launch(Dispatchers.IO) {
                try {
                    isProcessing = true
                    val resolver = context.contentResolver
                    val mimeType = resolver.getType(uri) ?: "audio/mpeg"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        error = "读取音频文件失败"
                        return@launch
                    }
                    // API limit: base64 <= 10MB (~7.5MB file), mp3/wav supported
                    if (bytes.size > 7_500_000) {
                        error = "文件过大（上限约 7.5MB），请截取更短的音频"
                        return@launch
                    }
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val text = ttsService.speechToText(base64, mimeType)
                    result = text ?: ""
                    if (text == null) error = ttsService.lastAsrError ?: "识别失败，请检查 API Key 或网络"
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    isProcessing = false
                    uploading = false
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            error = "需要麦克风权限"
        }
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        error = null
        result = ""
        isRecording = true
        audioJob = scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate, channelConfig, audioFormat,
                    maxOf(minBuf, 8192)
                )
                val buffer = ShortArray(4096)
                val pcm = ByteArrayOutputStream()
                recorder.startRecording()
                val maxSeconds = 30
                var totalRead = 0L
                val maxSamples = sampleRate.toLong() * maxSeconds
                while (isRecording && totalRead < maxSamples) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val bytes = ByteArray(read * 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, read)
                        pcm.write(bytes)
                        totalRead += read
                    }
                }
                recorder.stop()
                recorder.release()
                isRecording = false

                if (pcm.size() == 0) return@launch

                isProcessing = true
                val wavBytes = buildWav(pcm.toByteArray(), sampleRate)
                val base64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                val text = ttsService.speechToText(base64, "audio/wav")
                result = text ?: ""
                if (text == null) error = ttsService.lastAsrError ?: "识别失败，请检查 API Key 或网络"
            } catch (e: Exception) {
                isRecording = false
                error = e.message
            } finally {
                isProcessing = false
            }
        }
    }

    fun stopRecording() {
        isRecording = false
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("语音识别 ASR") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "mimo-v2.5-asr · 支持中英双语与方言",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Record button
            FilledIconButton(
                onClick = { if (isRecording) stopRecording() else startRecording() },
                modifier = Modifier.size(88.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isRecording) MiMoColors.Error else MiMoColors.Primary
                )
            ) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MiMoColors.OnPrimary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                when {
                    isRecording -> "正在录音... 点击停止"
                    isProcessing && uploading -> "解析音频文件..."
                    isProcessing -> "识别中..."
                    else -> "点击开始录音"
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Upload audio file
            OutlinedButton(
                onClick = { audioFilePicker.launch("audio/*") },
                enabled = !isRecording && !isProcessing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Upload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("上传音频文件识别（mp3 / wav，≤7.5MB）", fontSize = 13.sp)
            }

            if (isProcessing) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.width(160.dp))
            }

            error?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MiMoColors.Error.copy(alpha = 0.1f))
                ) {
                    Text(it, modifier = Modifier.padding(12.dp), color = MiMoColors.Error, fontSize = 13.sp)
                }
            }

            if (result.isNotBlank()) {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("识别结果", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                com.mimo.app.ui.components.ClipboardHelper.copyWithToast(context, result, label = "语音识别")
                            }) {
                                Text("复制")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(result, fontSize = 15.sp, lineHeight = 24.sp, textAlign = TextAlign.Start)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private fun buildWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val outputStream = ByteArrayOutputStream()
    val header = ByteBuffer.allocate(44).apply {
        order(ByteOrder.LITTLE_ENDIAN)
        put("RIFF".toByteArray())
        putInt(36 + pcm.size)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)
        putShort(1) // PCM
        putShort(1) // mono
        putInt(sampleRate)
        putInt(sampleRate * 2) // byte rate
        putShort(2) // block align
        putShort(16) // bits per sample
        put("data".toByteArray())
        putInt(pcm.size)
    }
    outputStream.write(header.array())
    outputStream.write(pcm)
    return outputStream.toByteArray()
}

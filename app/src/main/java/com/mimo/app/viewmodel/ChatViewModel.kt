package com.mimo.app.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mimo.app.data.api.MiMoApiService
import com.mimo.app.data.api.StreamChunk
import com.mimo.app.data.api.TTSService
import com.mimo.app.data.api.filterForSpeech
import com.mimo.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.io.File

private val Application.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mimo_settings")

/** One speed sample: t = seconds since stream start, think/answer in chars per second. */
data class SpeedPoint(val t: Float, val think: Float, val answer: Float)

data class SpeedStats(
    val points: List<SpeedPoint> = emptyList(),
    val currentTotal: Float = 0f,
    val currentThink: Float = 0f,
    val currentAnswer: Float = 0f,
    val peakTotal: Float = 0f,
    val elapsedSec: Float = 0f,
    val finished: Boolean = false,
    /** ms from send to first content token; -1 = not yet */
    val firstTokenMs: Long = -1L,
    /** index where reasoning stopped / content began (chart join point) */
    val thinkEndT: Float = -1f
) {
    val avgTotal: Float get() = if (points.isEmpty()) 0f else points.map { it.think + it.answer }.average().toFloat()
    val avgThink: Float get() = if (points.isEmpty()) 0f else points.map { it.think }.average().toFloat()
    val avgAnswer: Float get() = if (points.isEmpty()) 0f else points.map { it.answer }.average().toFloat()
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val apiService = MiMoApiService()
    private val agentChatService = MiMoApiService()
    private val ttsService = TTSService()
    private val islandService = com.mimo.app.data.api.IslandNotificationService(application)
    private val context = application

    private val API_KEY = stringPreferencesKey("api_key")
    private val TTS_VOICE_KEY = stringPreferencesKey("tts_voice")
    private val FONT_SIZE_KEY = stringPreferencesKey("font_size")
    private val FORCE_NEW_SESSION_KEY = stringPreferencesKey("force_new_session")
    private val CUSTOM_PROMPT_KEY = stringPreferencesKey("custom_prompt")
    private val AGENT_ENABLED_KEY = stringPreferencesKey("agent_enabled")
    private val AGENT_RISK_KEY = stringPreferencesKey("agent_risk")
    private val AGENT_MODE_KEY = stringPreferencesKey("agent_permission_mode")
    private val AGENT_API_KEY = stringPreferencesKey("agent_api_key")
    private val AGENT_BASE_URL = stringPreferencesKey("agent_base_url")
    private val ZHIPU_API_KEY = stringPreferencesKey("zhipu_api_key")
    private val WORKSPACE_PATH_KEY = stringPreferencesKey("workspace_path")
    private val STRICT_FAITHFUL_KEY = stringPreferencesKey("strict_faithful")
    private val UNLIMITED_ATTACH_KEY = stringPreferencesKey("unlimited_attachments")
    private val SESSIONS_KEY = stringPreferencesKey("sessions")
    private val gson = com.google.gson.GsonBuilder()
        .serializeNulls()
        .setPrettyPrinting()
        .create()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSession = MutableStateFlow<ChatSession?>(null)
    val currentSession: StateFlow<ChatSession?> = _currentSession.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentModel = MutableStateFlow("mimo-v2.6-flash")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    // Chat models from GET /models + TTS/ASR as first-class entries
    private val _modelIds = MutableStateFlow(
        listOf(
            "mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed",
            "tts-read", "tts-design", "tts-clone", "asr"
        )
    )
    val modelIds: StateFlow<List<String>> = _modelIds.asStateFlow()

    val isTTSMode: Boolean get() = _currentModel.value.startsWith("tts")
    val isASRMode: Boolean get() = _currentModel.value == "asr"
    /** Which TTS sub-model is selected (read / design / clone). */
    val ttsScreenMode: String get() = _currentModel.value.removePrefix("tts-").ifBlank { "read" }

    private val _enableThinking = MutableStateFlow(true)
    val enableThinking: StateFlow<Boolean> = _enableThinking.asStateFlow()

    private val _enableSearch = MutableStateFlow(false)
    val enableSearch: StateFlow<Boolean> = _enableSearch.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _apiKeyConfigured = MutableStateFlow(false)
    val apiKeyConfigured: StateFlow<Boolean> = _apiKeyConfigured.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _ttsVoice = MutableStateFlow("冰糖")
    val ttsVoice: StateFlow<String> = _ttsVoice.asStateFlow()

    private val _fontSize = MutableStateFlow(15)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _forceNewSessionOnModelSwitch = MutableStateFlow(true)
    val forceNewSessionOnModelSwitch: StateFlow<Boolean> = _forceNewSessionOnModelSwitch.asStateFlow()

    private val _customPrompt = MutableStateFlow("")
    val customPrompt: StateFlow<String> = _customPrompt.asStateFlow()

    private val _agentEnabled = MutableStateFlow(false)
    val agentEnabled: StateFlow<Boolean> = _agentEnabled.asStateFlow()

    private val _agentPermissionMode = MutableStateFlow(com.mimo.app.agent.AgentEngine.MODE_DEFAULT)
    val agentPermissionMode: StateFlow<Int> = _agentPermissionMode.asStateFlow()

    private val _agentApiKey = MutableStateFlow("")
    val agentApiKey: StateFlow<String> = _agentApiKey.asStateFlow()

    private val _agentBaseUrl = MutableStateFlow("https://token-plan-cn.xiaomimimo.com/v1")
    val agentBaseUrl: StateFlow<String> = _agentBaseUrl.asStateFlow()

    private val _zhipuApiKey = MutableStateFlow("")
    val zhipuApiKey: StateFlow<String> = _zhipuApiKey.asStateFlow()

    private val _workspacePath = MutableStateFlow("")
    val workspacePath: StateFlow<String> = _workspacePath.asStateFlow()

    // Default ON: forbid fabrication, stick strictly to provided content
    private val _strictFaithful = MutableStateFlow(true)
    val strictFaithful: StateFlow<Boolean> = _strictFaithful.asStateFlow()

    // Hidden setting: lift attachment count limits
    private val _unlimitedAttachments = MutableStateFlow(false)
    val unlimitedAttachments: StateFlow<Boolean> = _unlimitedAttachments.asStateFlow()

    // Agent-detected missing permission → ask user to jump to system Settings
    data class PermissionRequest(val type: String, val reason: String)
    private val _pendingPermission = MutableStateFlow<PermissionRequest?>(null)
    val pendingPermission: StateFlow<PermissionRequest?> = _pendingPermission.asStateFlow()

    private val _pendingToolCall = MutableStateFlow<com.mimo.app.agent.ToolCallRequest?>(null)
    val pendingToolCall: StateFlow<com.mimo.app.agent.ToolCallRequest?> = _pendingToolCall.asStateFlow()

    private val _toolExecuting = MutableStateFlow(false)
    val toolExecuting: StateFlow<Boolean> = _toolExecuting.asStateFlow()

    private val _speedStats = MutableStateFlow<SpeedStats?>(null)
    val speedStats: StateFlow<SpeedStats?> = _speedStats.asStateFlow()

    var agentEngine: com.mimo.app.agent.AgentEngine? = null
        private set

    private var streamingJob: Job? = null
    private var currentAudioTrack: android.media.AudioTrack? = null

    init {
        viewModelScope.launch {
            context.dataStore.data.first().let { prefs ->
                val savedKey = prefs[API_KEY] ?: ""
                if (savedKey.isNotBlank()) {
                    _apiKey.value = savedKey
                    _apiKeyConfigured.value = true
                    apiService.updateApiKey(savedKey)
                    ttsService.updateApiKey(savedKey)
                }
                val savedVoice = prefs[TTS_VOICE_KEY]
                if (!savedVoice.isNullOrBlank()) {
                    _ttsVoice.value = savedVoice
                }
                val savedFontSize = prefs[FONT_SIZE_KEY]
                if (!savedFontSize.isNullOrBlank()) {
                    _fontSize.value = savedFontSize.toIntOrNull() ?: 15
                }
                val savedForceNew = prefs[FORCE_NEW_SESSION_KEY]
                if (!savedForceNew.isNullOrBlank()) {
                    _forceNewSessionOnModelSwitch.value = savedForceNew == "true"
                }
                val savedPrompt = prefs[CUSTOM_PROMPT_KEY]
                if (!savedPrompt.isNullOrBlank()) {
                    _customPrompt.value = savedPrompt
                }
                val savedAgent = prefs[AGENT_ENABLED_KEY]
                if (!savedAgent.isNullOrBlank()) {
                    _agentEnabled.value = savedAgent == "true"
                }
                val savedMode = prefs[AGENT_MODE_KEY]
                if (!savedMode.isNullOrBlank()) {
                    _agentPermissionMode.value = savedMode.toIntOrNull()
                        ?: com.mimo.app.agent.AgentEngine.MODE_DEFAULT
                } else {
                    // Migrate from old 1-5 risk slider
                    val oldRisk = prefs[AGENT_RISK_KEY]?.toIntOrNull()
                    _agentPermissionMode.value = when {
                        oldRisk == null -> com.mimo.app.agent.AgentEngine.MODE_DEFAULT
                        oldRisk <= 1 -> com.mimo.app.agent.AgentEngine.MODE_AUTO
                        oldRisk >= 5 -> com.mimo.app.agent.AgentEngine.MODE_ASK_ALL
                        else -> com.mimo.app.agent.AgentEngine.MODE_DEFAULT
                    }
                }
                val savedAgentKey = prefs[AGENT_API_KEY]
                if (!savedAgentKey.isNullOrBlank()) {
                    _agentApiKey.value = savedAgentKey
                }
                val savedAgentUrl = prefs[AGENT_BASE_URL]
                if (!savedAgentUrl.isNullOrBlank()) {
                    _agentBaseUrl.value = savedAgentUrl
                }
                val savedWorkspace = prefs[WORKSPACE_PATH_KEY]
                if (savedWorkspace != null) {
                    _workspacePath.value = savedWorkspace
                }
                prefs[STRICT_FAITHFUL_KEY]?.let { _strictFaithful.value = it == "true" }
                prefs[UNLIMITED_ATTACH_KEY]?.let { _unlimitedAttachments.value = it == "true" }
                if (_agentEnabled.value) {
                    val key = if (_agentApiKey.value.isNotBlank()) _agentApiKey.value else _apiKey.value
                    val url = if (_agentApiKey.value.isNotBlank()) _agentBaseUrl.value else "https://api.xiaomimimo.com/v1"
                    agentChatService.updateApiKey(key)
                    agentChatService.updateBaseUrl(url)
                    val agentToolService = MiMoApiService()
                    agentToolService.updateApiKey(key)
                    agentToolService.updateBaseUrl(url)
                    agentEngine = createAgentEngine(agentToolService)
                }
                // Load saved sessions into the drawer, but always start on a fresh chat
                val savedSessions = loadSessions()
                if (!savedSessions.isNullOrEmpty()) {
                    _sessions.value = savedSessions
                }
            }
            refreshModelList()
            if (_currentSession.value == null) createNewSession()
        }
    }

    /**
     * Fetch available chat models from GET /models.
     * Keeps only mimo-v* chat models; TTS/ASR stay fixed special modes.
     * On failure the previous/default list is kept.
     */
    fun refreshModelList() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ids = apiService.listModels()
                    .filter { it.startsWith("mimo-v") }
                    .filterNot { it.contains("tts") || it.contains("asr") }
                    .distinct()
                if (ids.isNotEmpty()) {
                    // TTS modes appear as first-class models alongside flash/pro
                    _modelIds.value = ids + listOf("tts-read", "tts-design", "tts-clone")
                    val cur = _currentModel.value
                    if (cur != "asr" && !cur.startsWith("tts")) {
                        val base = cur.removeSuffix("-agent")
                        if (base !in ids && cur !in ids) {
                            ids.firstOrNull()?.let { _currentModel.value = it }
                        }
                    }
                }
            } catch (_: Exception) {
                // keep current list on network failure
            }
        }
    }

    fun setApiKey(key: String) {
        apiService.updateApiKey(key)
        ttsService.updateApiKey(key)
        _apiKey.value = key
        _apiKeyConfigured.value = key.isNotBlank()
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[API_KEY] = key
            }
        }
    }

    fun setTtsVoice(voice: String) {
        _ttsVoice.value = voice
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[TTS_VOICE_KEY] = voice
            }
        }
    }

    fun setFontSize(size: Int) {
        _fontSize.value = size.coerceIn(12, 24)
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[FONT_SIZE_KEY] = size.toString()
            }
        }
    }

    fun setForceNewSessionOnModelSwitch(enabled: Boolean) {
        _forceNewSessionOnModelSwitch.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[FORCE_NEW_SESSION_KEY] = enabled.toString()
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "mimo_sessions.json")
                if (file.exists()) file.delete()
                context.dataStore.edit { it.clear() }
            } catch (_: Exception) {}
        }
    }

    fun setCustomPrompt(prompt: String) {
        _customPrompt.value = prompt
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[CUSTOM_PROMPT_KEY] = prompt
            }
        }
    }

    private fun createAgentEngine(toolService: MiMoApiService): com.mimo.app.agent.AgentEngine {
        return com.mimo.app.agent.AgentEngine(
            context, toolService,
            workspaceOverride = _workspacePath.value.trim()
        ).also {
            it.zhipuApiKey = _zhipuApiKey.value
            // Surface confirmation requests from all permission modes as a UI dialog
            it.onConfirmationRequested = { tool, args, desc ->
                // Not executing yet — waiting for user, so enable the Approve/Deny buttons
                _toolExecuting.value = false
                _pendingToolCall.value = com.mimo.app.agent.ToolCallRequest(
                    tool = tool, args = args, reason = desc
                )
            }
        }
    }

    fun setAgentEnabled(enabled: Boolean) {
        _agentEnabled.value = enabled
        if (enabled && agentEngine == null) {
            val key = if (_agentApiKey.value.isNotBlank()) _agentApiKey.value else _apiKey.value
            val url = if (_agentApiKey.value.isNotBlank()) _agentBaseUrl.value else "https://api.xiaomimimo.com/v1"
            agentChatService.updateApiKey(key)
            agentChatService.updateBaseUrl(url)
            val agentToolService = MiMoApiService()
            agentToolService.updateApiKey(key)
            agentToolService.updateBaseUrl(url)
            agentEngine = createAgentEngine(agentToolService)
        }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[AGENT_ENABLED_KEY] = enabled.toString()
            }
        }
    }

    fun setAgentPermissionMode(mode: Int) {
        _agentPermissionMode.value = mode.coerceIn(0, 3)
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[AGENT_MODE_KEY] = mode.coerceIn(0, 3).toString()
            }
        }
    }

    fun setStrictFaithful(enabled: Boolean) {
        _strictFaithful.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { it[STRICT_FAITHFUL_KEY] = enabled.toString() }
        }
    }

    fun setUnlimitedAttachments(enabled: Boolean) {
        _unlimitedAttachments.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { it[UNLIMITED_ATTACH_KEY] = enabled.toString() }
        }
    }

    fun setAgentApiKey(key: String) {
        _agentApiKey.value = key
        val effectiveKey = if (key.isNotBlank()) key else _apiKey.value
        val effectiveUrl = if (key.isNotBlank()) _agentBaseUrl.value else "https://api.xiaomimimo.com/v1"
        agentChatService.updateApiKey(effectiveKey)
        agentChatService.updateBaseUrl(effectiveUrl)
        agentEngine?.let {
            val agentToolService = MiMoApiService()
            agentToolService.updateApiKey(effectiveKey)
            agentToolService.updateBaseUrl(effectiveUrl)
            agentEngine = createAgentEngine(agentToolService)
        }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[AGENT_API_KEY] = key
            }
        }
    }

    fun setAgentBaseUrl(url: String) {
        _agentBaseUrl.value = url
        val effectiveKey = if (_agentApiKey.value.isNotBlank()) _agentApiKey.value else _apiKey.value
        agentChatService.updateApiKey(effectiveKey)
        agentChatService.updateBaseUrl(url)
        agentEngine?.let {
            val agentToolService = MiMoApiService()
            agentToolService.updateApiKey(effectiveKey)
            agentToolService.updateBaseUrl(url)
            agentEngine = createAgentEngine(agentToolService)
        }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[AGENT_BASE_URL] = url
            }
        }
    }

    fun setWorkspacePath(path: String) {
        _workspacePath.value = path.trim()
        agentEngine?.let {
            val agentToolService = MiMoApiService()
            val key = if (_agentApiKey.value.isNotBlank()) _agentApiKey.value else _apiKey.value
            val url = if (_agentApiKey.value.isNotBlank()) _agentBaseUrl.value else "https://api.xiaomimimo.com/v1"
            agentToolService.updateApiKey(key)
            agentToolService.updateBaseUrl(url)
            agentEngine = createAgentEngine(agentToolService)
        }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[WORKSPACE_PATH_KEY] = path.trim()
            }
        }
    }

    fun setZhipuApiKey(key: String) {
        _zhipuApiKey.value = key
        agentEngine?.zhipuApiKey = key
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[ZHIPU_API_KEY] = key
            }
        }
    }

    fun setModel(model: String) {
        if (_currentModel.value != model) {
            _currentModel.value = model
            if (_forceNewSessionOnModelSwitch.value) {
                // New chat with the new model — leave the old session's model untouched
                createNewSession()
            } else {
                // Stay in this session: persist the choice onto it
                _currentSession.value?.let { cur ->
                    if (cur.model != model) {
                        val updated = cur.copy(model = model)
                        _currentSession.value = updated
                        _sessions.value = _sessions.value.map { if (it.id == updated.id) updated else it }
                        saveSessions()
                    }
                }
            }
        }
    }

    fun toggleThinking() {
        _enableThinking.value = !_enableThinking.value
    }

    fun toggleSearch() {
        _enableSearch.value = !_enableSearch.value
    }

    fun toggleSettings() {
        _showSettings.value = !_showSettings.value
    }

    fun createNewSession() {
        val session = ChatSession(model = _currentModel.value)
        _currentSession.value = session
    }

    fun selectSession(sessionId: String) {
        val target = _sessions.value.find { it.id == sessionId } ?: return
        _currentSession.value = target
        // Restore this session's own model (each session keeps its selection)
        val sessionModel = target.model
        if (sessionModel.isNotBlank() && _currentModel.value != sessionModel) {
            _currentModel.value = sessionModel
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val updated = _sessions.value.find { it.id == sessionId }?.copy(title = newTitle) ?: return
        _sessions.value = _sessions.value.map { if (it.id == sessionId) updated else it }
        if (_currentSession.value?.id == sessionId) {
            _currentSession.value = updated
        }
        saveSessions()
    }

    fun deleteSession(sessionId: String) {
        _sessions.value = _sessions.value.filter { it.id != sessionId }
        if (_currentSession.value?.id == sessionId) {
            _currentSession.value = _sessions.value.lastOrNull()
        }
        saveSessions()
    }

    fun editAndResend(messageId: String, newText: String) {
        val session = _currentSession.value ?: return
        if (newText.isBlank()) return

        val msgIndex = session.messages.indexOfFirst { it.id == messageId }
        if (msgIndex < 0 || session.messages[msgIndex].role != "user") return

        // Remove the user message and the assistant response after it
        val messagesBefore = session.messages.take(msgIndex)
        val messagesAfter = session.messages.drop(msgIndex + 1).dropWhile { it.role == "assistant" }

        // Edit text only — preserve imageUri / fileName so re-sent message keeps attachments
        val editedUserMsg = session.messages[msgIndex].copy(content = newText)
        val assistantMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)

        val updatedMessages = messagesBefore + editedUserMsg + assistantMsg + messagesAfter
        updateSession(session.copy(messages = updatedMessages))

        // Now trigger the API call
        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _isGenerating.value = true
                _error.value = null

                val apiMessages = mutableListOf<Message>(
                    Message(role = "system", content = getSystemPrompt())
                )

                // Add conversation history up to the edited message; re-attach image on the edited msg
                val history = updatedMessages.filter { it.role == "user" || (it.role == "assistant" && it.content.isNotBlank()) }.takeLast(20)
                for (msg in history) {
                    if (msg.role == "user") {
                        val imgB64 = msg.imageUri?.let { uri ->
                            try {
                                context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { s ->
                                    android.util.Base64.encodeToString(s.readBytes(), android.util.Base64.NO_WRAP)
                                }
                            } catch (_: Exception) { null }
                        }
                        apiMessages.add(getActiveApiService().buildUserMessage(
                            text = msg.content,
                            imageBase64 = imgB64
                        ))
                    } else if (msg.role == "assistant" && msg.content.isNotBlank()) {
                        apiMessages.add(Message(role = "assistant", content = msg.content))
                    }
                }

                val activeApi = getActiveApiService()
                val request = activeApi.buildRequest(
                    messages = apiMessages,
                    model = getApiModel(),
                    enableThinking = _enableThinking.value,
                    enableSearch = _enableSearch.value
                )

                var fullContent = ""
                var fullReasoning = ""
                var allAnnotations = mutableListOf<com.mimo.app.data.model.Annotation>()
                var lastDataTime = System.currentTimeMillis()

                val watchdogJob = launch {
                    while (true) {
                        kotlinx.coroutines.delay(10_000)
                        if (System.currentTimeMillis() - lastDataTime > 90_000) {
                            _isGenerating.value = false
                            val current = _currentSession.value
                            if (current != null) {
                                updateSession(current.copy(messages = current.messages.map {
                                    if (it.isStreaming) it.copy(isStreaming = false) else it
                                }))
                            }
                            break
                        }
                    }
                }

                activeApi.chatCompletion(request).collect { chunk ->
                    lastDataTime = System.currentTimeMillis()
                    when (chunk) {
                        is StreamChunk.Data -> {
                            fullContent += chunk.content
                            fullReasoning += chunk.reasoning
                            chunk.annotations?.let { allAnnotations.addAll(it) }
                            val current = _currentSession.value ?: return@collect
                            val updatedMsgs = current.messages.toMutableList()
                            val lastIdx = updatedMsgs.indexOfLast { it.isStreaming }
                            if (lastIdx >= 0) {
                                updatedMsgs[lastIdx] = updatedMsgs[lastIdx].copy(content = fullContent, reasoningContent = fullReasoning)
                                updateSession(current.copy(messages = updatedMsgs))
                            }
                        }
                        is StreamChunk.Done -> {
                            _isGenerating.value = false
                            watchdogJob.cancel()
                            val current = _currentSession.value ?: return@collect
                            val updatedMsgs = current.messages.toMutableList()
                            val lastIdx = updatedMsgs.indexOfLast { it.isStreaming }
                            if (lastIdx >= 0) {
                                updatedMsgs[lastIdx] = updatedMsgs[lastIdx].copy(
                                    isStreaming = false,
                                    annotations = if (allAnnotations.isNotEmpty()) allAnnotations.toList() else null
                                )
                                updateSession(current.copy(messages = updatedMsgs))
                            }
                        }
                    }
                }
                watchdogJob.cancel()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user pressed stop — not an error
            } catch (e: Exception) {
                _error.value = e.message ?: "Unknown error"
                val current = _currentSession.value ?: return@launch
                updateSession(current.copy(messages = current.messages
                    .filterNot { it.role == "assistant" && it.content.isBlank() && it.isStreaming }
                    .map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                ))
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun sendMessage(text: String, imageUris: List<Uri> = emptyList(), fileUri: Uri? = null, fileName: String? = null) {
        val session = _currentSession.value ?: return
        if (text.isBlank() && imageUris.isEmpty() && fileUri == null) return

        val isFirstMessage = session.messages.isEmpty()
        if (isFirstMessage && !_sessions.value.contains(session)) {
            _sessions.value = _sessions.value + session
            saveSessions()
        }
        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileContent = ""
                var audioBase64: String? = null
                var audioMimeType: String? = null
                var videoBase64: String? = null
                var videoMimeType: String? = null

                if (fileUri != null) {
                    val mimeType = context.contentResolver.getType(fileUri) ?: ""
                    when {
                        mimeType.startsWith("audio/") -> {
                            // raw bytes — BitmapFactory can't decode audio
                            audioBase64 = uriToRawBase64(fileUri)
                            audioMimeType = mimeType
                        }
                        mimeType.startsWith("video/") -> {
                            videoBase64 = uriToRawBase64(fileUri)
                            videoMimeType = mimeType
                        }
                        else -> {
                            fileContent = readFileContent(fileUri) ?: ""
                        }
                    }
                }

                var imageBase64List = imageUris.mapNotNull { uriToBase64(it) }
                var extractedNote = ""

                // Non-vision models can't consume image_url parts — extract to text first
                if (imageBase64List.isNotEmpty() && !supportsVision(_currentModel.value)) {
                    _isGenerating.value = true
                    islandService.showAgentTaskNotification(
                        taskTitle = "MiMo",
                        taskStatus = "正在提取图片内容...",
                        progress = -1
                    )
                    val extracted = extractImageContent(imageBase64List)
                    if (extracted != null) {
                        extractedNote = "\n\n[图片内容 - 已自动提取为文档]\n$extracted"
                        imageBase64List = emptyList() // text-only from here on
                    }
                    // extraction failed → keep images and send as-is (API may still accept or surface a clear error)
                }

                val fullUserContent = buildString {
                    if (text.isNotBlank()) append(text)
                    if (fileContent.isNotBlank()) {
                        if (isNotBlank()) append("\n\n")
                        append("File: ${fileName ?: "file"}\n```\n$fileContent\n```")
                    }
                    if (extractedNote.isNotBlank()) append(extractedNote)
                }

                // Persist picker URIs into app storage — content:// dies after process kill
                val persistedImages = imageUris.mapNotNull { persistImage(it) }
                val userMessage = ChatMessage(
                    role = "user",
                    content = fullUserContent,
                    imageUri = persistedImages.joinToString("|").ifBlank { null },
                    fileName = fileName
                )
                val assistantMessage = ChatMessage(role = "assistant", content = "", isStreaming = true)

                updateSession(session.copy(
                    messages = session.messages + userMessage + assistantMessage
                ))

                _isGenerating.value = true
                _error.value = null

                val isAgent = _currentModel.value.contains("agent") && agentEngine != null

                if (isAgent) {
                    executeAgentFlow(text, fullUserContent, imageBase64List, audioBase64, audioMimeType, videoBase64, videoMimeType, isFirstMessage, session)
                } else {
                    executeNormalFlow(text, fullUserContent, imageBase64List, audioBase64, audioMimeType, videoBase64, videoMimeType, isFirstMessage, session)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user pressed stop — not an error
            } catch (e: Exception) {
                _error.value = e.message ?: "Unknown error"
                val current = _currentSession.value ?: return@launch
                updateSession(current.copy(messages = current.messages
                    .filterNot { it.role == "assistant" && it.content.isBlank() && it.isStreaming }
                    .map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                ))
            } finally {
                _isGenerating.value = false
                islandService.cancelNotification()
            }
        }
    }

    private suspend fun executeNormalFlow(
        text: String, fullUserContent: String, imageBase64List: List<String>,
        audioBase64: String?, audioMimeType: String?,
        videoBase64: String?, videoMimeType: String?,
        isFirstMessage: Boolean, session: ChatSession
    ) {
        val apiMessages = mutableListOf<Message>(
            Message(role = "system", content = getSystemPrompt())
        )

        val history = session.messages.takeLast(20)
        for (msg in history) {
            if (msg.role == "user") {
                apiMessages.add(getActiveApiService().buildUserMessage(msg.content))
            } else if (msg.role == "assistant" && msg.content.isNotBlank()) {
                apiMessages.add(Message(role = "assistant", content = msg.content))
            }
        }

        // fullUserContent = text + file content + extracted image doc — what the model actually needs
        apiMessages.add(getActiveApiService().buildUserMessage(
            text = fullUserContent,
            imageBase64 = imageBase64List.firstOrNull(),
            audioBase64 = audioBase64,
            audioMimeType = audioMimeType,
            videoBase64 = videoBase64,
            videoMimeType = videoMimeType
        ))

        val activeApi = getActiveApiService()
        val request = activeApi.buildRequest(
            messages = apiMessages,
            model = getApiModel(),
            enableThinking = _enableThinking.value,
            enableSearch = _enableSearch.value
        )

        try {
            islandService.showAgentTaskNotification(
                taskTitle = "MiMo",
                taskStatus = "Thinking...",
                progress = -1
            )
            streamResponse(activeApi, request, isFirstMessage, session.id, text)
        } catch (e: Exception) {
            if (isBalanceError(e) && _agentApiKey.value.isNotBlank()) {
                val fallbackApi = agentChatService
                streamResponse(fallbackApi, request, isFirstMessage, session.id, text)
            } else {
                throw e
            }
        }
    }

    private suspend fun executeAgentFlow(
        text: String, fullUserContent: String, imageBase64List: List<String>,
        audioBase64: String?, audioMimeType: String?,
        videoBase64: String?, videoMimeType: String?,
        isFirstMessage: Boolean, session: ChatSession
    ) {
        val engine = agentEngine ?: return
        val activeApi = getActiveApiService()
        val maxRounds = 5

        // Caller appended [user, streaming placeholder]; everything from here on is this run's output.
        val runStartIdx = (_currentSession.value?.messages?.size ?: 1) - 1
        val toolSteps = mutableListOf<Map<String, String>>()

        val apiMessages = mutableListOf<Message>(
            Message(role = "system", content = getSystemPrompt())
        )

        val history = session.messages.filter { !it.isStreaming }.takeLast(20)
        for (msg in history) {
            when (msg.role) {
                "user" -> apiMessages.add(Message(role = "user", content = msg.content))
                "assistant" -> if (msg.content.isNotBlank()) apiMessages.add(Message(role = "assistant", content = msg.content))
                "tool" -> apiMessages.add(Message(role = "tool", content = msg.content, toolCallId = msg.id))
                "tool_group" -> {
                    // Expand collapsed operations back into tool messages for model context
                    try {
                        val arr = com.google.gson.JsonParser.parseString(msg.content).asJsonArray
                        arr.forEach { el ->
                            val o = el.asJsonObject
                            val t = o.get("tool")?.asString ?: "tool"
                            val r = o.get("result")?.asString ?: ""
                            apiMessages.add(Message(role = "tool", content = r, toolCallId = t))
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        apiMessages.add(activeApi.buildUserMessage(
            text = fullUserContent,
            imageBase64 = imageBase64List.firstOrNull(),
            audioBase64 = audioBase64,
            audioMimeType = audioMimeType,
            videoBase64 = videoBase64,
            videoMimeType = videoMimeType
        ))

        var round = 0
        while (round < maxRounds) {
            round++
            val request = ChatRequest(
                model = getApiModel(),
                messages = apiMessages,
                maxCompletionTokens = 4096,
                temperature = 0.7f,
                stream = false,
                thinking = if (_enableThinking.value) ThinkingConfig(type = "enabled") else ThinkingConfig(type = "disabled"),
                responseFormat = com.mimo.app.agent.ToolCallSchema.toResponseFormat()
            )

            val response = try {
                activeApi.chatCompletionStructured(request)
            } catch (e: Exception) {
                if (isBalanceError(e) && _agentApiKey.value.isNotBlank()) {
                    try {
                        agentChatService.chatCompletionStructured(request)
                    } catch (e2: Exception) {
                        _error.value = "Agent request failed: ${e2.message}"
                        val current = _currentSession.value
                        if (current != null) {
                            updateSession(current.copy(messages = current.messages.filterNot {
                                it.role == "assistant" && it.content.isBlank() && it.isStreaming
                            }))
                        }
                        islandService.cancelNotification()
                        return
                    }
                } else {
                    _error.value = "Agent request failed: ${e.message}"
                    val current = _currentSession.value
                    if (current != null) {
                        updateSession(current.copy(messages = current.messages.filterNot {
                            it.role == "assistant" && it.content.isBlank() && it.isStreaming
                        }))
                    }
                    islandService.cancelNotification()
                    return
                }
            }

            val content = response.choices.firstOrNull()?.message?.content?.toString() ?: break
            val rawText = content as? String ?: content.toString()
            val jsonText = extractJson(rawText)
            val structured = try {
                gson.fromJson(jsonText, com.mimo.app.agent.StructuredToolCallResponse::class.java)
            } catch (e: Exception) {
                null
            }
            if (structured == null) {
                // Unparseable output (gson returns null for "null"/empty instead of throwing):
                // show raw text as the reply rather than crashing
                val current = _currentSession.value
                if (current != null) {
                    val updatedMsgs = current.messages.toMutableList()
                    val lastIdx = updatedMsgs.indexOfLast { it.isStreaming }
                    if (lastIdx >= 0) {
                        updatedMsgs[lastIdx] = updatedMsgs[lastIdx].copy(content = rawText, isStreaming = false)
                        updateSession(current.copy(messages = updatedMsgs))
                    }
                }
                islandService.cancelNotification()
                return
            }

            val toolCalls = structured.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                val responseText = structured.response?.trim().orEmpty()
                if (responseText.isEmpty() && round < maxRounds) {
                    // BUG fix: model thought but returned no answer — nudge it once more
                    // instead of ending the task with a blank reply
                    apiMessages.add(Message(role = "assistant", content = content))
                    apiMessages.add(Message(
                        role = "user",
                        content = "Continue. Provide your final response to the user now. Do not stop at thinking only."
                    ))
                    continue
                }
                val finalText = responseText.ifBlank {
                    structured.thinking?.trim()?.takeIf { it.isNotBlank() } ?: "No response"
                }
                val current = _currentSession.value
                if (current != null) {
                    // Collapse this run's live tool cards into one group, reply goes below it
                    val collapsed = collapseRunMessages(current, toolSteps, runStartIdx)
                    updateSession(current.copy(
                        messages = collapsed + ChatMessage(role = "assistant", content = finalText, isStreaming = false)
                    ))
                }
                if (isFirstMessage && finalText.isNotBlank()) {
                    generateTitle(session.id, text, finalText)
                }
                islandService.cancelNotification()
                return
            }

            apiMessages.add(Message(role = "assistant", content = content))

            // Surface this round's thinking/narration to the user LIVE (previously discarded).
            // content = short narration the model said alongside tool calls;
            // reasoningContent = its thinking (renders as collapsible "Thinking" card).
            val narration = structured.response?.trim().orEmpty()
            val roundThink = structured.thinking?.trim().orEmpty()
            if (narration.isNotBlank() || roundThink.isNotBlank()) {
                if (narration.isNotBlank()) {
                    // also surface the narration in the ongoing notification
                    islandService.showAgentTaskNotification(
                        taskTitle = "MiMo Agent",
                        taskStatus = narration.take(80),
                        progress = -1
                    )
                }
                val cur = _currentSession.value
                if (cur != null) {
                    updateSession(cur.copy(messages = cur.messages + ChatMessage(
                        role = "assistant",
                        content = narration,
                        reasoningContent = roundThink,
                        isStreaming = false
                    )))
                }
            }

            toolCalls.forEachIndexed { idx, tc ->
                _toolExecuting.value = true
                islandService.showAgentTaskNotification(
                    taskTitle = "MiMo Agent",
                    taskStatus = "Executing: ${tc.tool}",
                    progress = ((idx) * 100 / toolCalls.size)
                )
                val toolResult = try {
                    engine.executeTool(tc.tool, tc.args, _agentPermissionMode.value)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                } finally {
                    _toolExecuting.value = false
                    _pendingToolCall.value = null
                }
                // Tool reported a missing system permission → ask the user, offer Settings jump
                val displayResult = if (toolResult.startsWith("NEED_PERMISSION:")) {
                    val rest = toolResult.removePrefix("NEED_PERMISSION:")
                    val type = rest.substringBefore("|", "storage")
                    val reason = rest.substringAfter("|", "This permission is required")
                    _pendingPermission.value = PermissionRequest(type, reason)
                    "权限缺失（${permissionLabel(type)}）：$reason。已询问用户是否前往系统设置授权。"
                } else toolResult
                toolSteps.add(mapOf("tool" to tc.tool, "result" to displayResult))

                val current = _currentSession.value
                if (current != null) {
                    val toolMsg = ChatMessage(
                        role = "tool",
                        content = "[${tc.tool}] $displayResult",
                        id = tc.tool
                    )
                    updateSession(current.copy(messages = current.messages + toolMsg))
                }

                apiMessages.add(Message(role = "tool", content = displayResult, toolCallId = tc.tool))
            }
            // Continue loop for next round of tool calls
        }

        // All tool rounds done, stream final response
        val finalRequest = activeApi.buildRequest(
            messages = apiMessages,
            model = getApiModel(),
            enableThinking = _enableThinking.value,
            enableSearch = false
        )

        val current = _currentSession.value
        if (current != null) {
            val collapsed = collapseRunMessages(current, toolSteps, runStartIdx)
            val streamingMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)
            updateSession(current.copy(messages = collapsed + streamingMsg))
        }

        streamResponse(activeApi, finalRequest, isFirstMessage, session.id, text)
        islandService.cancelNotification()
    }

    /**
     * Replace this run's live tool messages (and the streaming placeholder) with a
     * single collapsed "tool_group" message, keeping history before the run intact.
     * Live narration messages (model's per-round thinking/speech) are PRESERVED above
     * the operations group. During execution each tool shows individually; collapse
     * happens once at the end.
     */
    private fun collapseRunMessages(
        current: ChatSession,
        toolSteps: List<Map<String, String>>,
        runStartIdx: Int
    ): List<com.mimo.app.data.model.ChatMessage> {
        val msgs = current.messages
        val beforeRun = if (runStartIdx in 0..msgs.size) msgs.subList(0, runStartIdx) else msgs
        // run segment = placeholder + narrations + individual tool cards
        val runSeg = if (runStartIdx < msgs.size) msgs.subList(runStartIdx, msgs.size) else emptyList()
        // Keep narration/speech messages; drop the streaming placeholder and tool cards
        val narrations = runSeg.filter {
            it.role == "assistant" && !it.isStreaming &&
                    (it.content.isNotBlank() || it.reasoningContent.isNotBlank())
        }
        if (toolSteps.isEmpty()) return beforeRun + narrations
        val groupMsg = com.mimo.app.data.model.ChatMessage(
            role = "tool_group",
            content = gson.toJson(toolSteps)
        )
        return beforeRun + narrations + groupMsg
    }

    private suspend fun streamResponse(
        activeApi: MiMoApiService, request: ChatRequest,
        isFirstMessage: Boolean, sessionId: String, userText: String
    ) {
        var fullContent = ""
        var fullReasoning = ""
        var allAnnotations = mutableListOf<com.mimo.app.data.model.Annotation>()
        var lastDataTime = System.currentTimeMillis()
        var thinkingNotified = false

        // Speed sampling (Ultra models only)
        val trackSpeed = _currentModel.value.contains("ultraspeed")
        val streamStart = System.currentTimeMillis()
        var lastSampleTime = 0L
        var lastSampleContent = 0
        var lastSampleReasoning = 0
        var peakTotal = 0f
        var firstTokenAt = -1L
        var thinkEnded = false
        _speedStats.value = if (trackSpeed) SpeedStats() else null

        fun sampleSpeed(force: Boolean = false) {
            if (!trackSpeed) return
            val now = System.currentTimeMillis()
            val sinceLast = now - lastSampleTime
            if (!force && sinceLast < 300) return
            val dt: Float = if (lastSampleTime == 0L) 0.3f else sinceLast / 1000f
            if (lastSampleTime != 0L && dt > 0f) {
                val thinkSpeed = (fullReasoning.length - lastSampleReasoning) / dt
                val answerSpeed = (fullContent.length - lastSampleContent) / dt
                val total = thinkSpeed + answerSpeed
                if (total > peakTotal) peakTotal = total
                val prev = _speedStats.value ?: SpeedStats()
                val t = (now - streamStart) / 1000f
                // Join point: first moment content starts flowing after thinking
                val thinkEnd = if (!thinkEnded && fullContent.isNotEmpty() && fullReasoning.isNotEmpty()) {
                    thinkEnded = true
                    t
                } else prev.thinkEndT
                val point = SpeedPoint(t, thinkSpeed, answerSpeed)
                _speedStats.value = prev.copy(
                    points = (prev.points + point).takeLast(240),
                    currentTotal = total,
                    currentThink = thinkSpeed,
                    currentAnswer = answerSpeed,
                    peakTotal = peakTotal,
                    elapsedSec = t,
                    firstTokenMs = firstTokenAt,
                    thinkEndT = thinkEnd
                )
            }
            lastSampleTime = now
            lastSampleContent = fullContent.length
            lastSampleReasoning = fullReasoning.length
        }

        val watchdogJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (System.currentTimeMillis() - lastDataTime > 90_000) {
                    _isGenerating.value = false
                    if (trackSpeed) {
                        val prev = _speedStats.value
                        if (prev != null) _speedStats.value = prev.copy(finished = true, currentTotal = 0f, currentThink = 0f, currentAnswer = 0f)
                    }
                    val current = _currentSession.value
                    if (current != null) {
                        updateSession(current.copy(messages = current.messages.map {
                            if (it.isStreaming) it.copy(isStreaming = false) else it
                        }))
                    }
                    break
                }
            }
        }

        try {
            activeApi.chatCompletion(request).collect { chunk ->
                lastDataTime = System.currentTimeMillis()
                when (chunk) {
                    is StreamChunk.Data -> {
                        fullContent += chunk.content
                        fullReasoning += chunk.reasoning
                        if (firstTokenAt < 0 && (chunk.content.isNotBlank() || chunk.reasoning.isNotBlank())) {
                            firstTokenAt = System.currentTimeMillis() - streamStart
                        }
                        sampleSpeed()
                        if (chunk.reasoning.isNotBlank() && !thinkingNotified) {
                            thinkingNotified = true
                            islandService.showAgentTaskNotification(
                                taskTitle = "MiMo",
                                taskStatus = "Thinking deeply...",
                                progress = -1
                            )
                        }
                        if (chunk.content.isNotBlank() && thinkingNotified) {
                            thinkingNotified = false
                            islandService.showAgentTaskNotification(
                                taskTitle = "MiMo - Thinking Done",
                                taskStatus = chunk.content.take(80),
                                progress = 100
                            )
                        }
                        chunk.annotations?.let { allAnnotations.addAll(it) }
                        val current = _currentSession.value ?: return@collect
                        val updatedMessages = current.messages.toMutableList()
                        val lastIdx = updatedMessages.indexOfLast { it.isStreaming }
                        if (lastIdx >= 0) {
                            updatedMessages[lastIdx] = updatedMessages[lastIdx].copy(content = fullContent, reasoningContent = fullReasoning)
                            updateSession(current.copy(messages = updatedMessages))
                        }
                    }
                    is StreamChunk.Done -> {
                        _isGenerating.value = false
                        watchdogJob.cancel()
                        if (trackSpeed) {
                            sampleSpeed(force = true)
                            val prev = _speedStats.value
                            if (prev != null) {
                                _speedStats.value = prev.copy(
                                    currentTotal = 0f, currentThink = 0f, currentAnswer = 0f,
                                    finished = true
                                )
                            }
                        }
                        val current = _currentSession.value ?: return@collect
                        val updatedMessages = current.messages.toMutableList()
                        val lastIdx = updatedMessages.indexOfLast { it.isStreaming }
                        if (lastIdx >= 0) {
                            updatedMessages[lastIdx] = updatedMessages[lastIdx].copy(
                                isStreaming = false,
                                annotations = if (allAnnotations.isNotEmpty()) allAnnotations.toList() else null
                            )
                            updateSession(current.copy(messages = updatedMessages))
                        }
                        if (isFirstMessage && fullContent.isNotBlank()) {
                            generateTitle(sessionId, userText, fullContent)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User pressed stop — stopGeneration already cleaned UI; do not show an error
            throw e
        } catch (e: Exception) {
            // Mid-stream failure: keep whatever partial content arrived, just stop the spinner
            _error.value = e.message ?: "连接中断"
            watchdogJob.cancel()
            if (trackSpeed) {
                val prev = _speedStats.value
                if (prev != null) _speedStats.value = prev.copy(finished = true, currentTotal = 0f, currentThink = 0f, currentAnswer = 0f)
            }
            val current = _currentSession.value
            if (current != null) {
                updateSession(current.copy(messages = current.messages.map {
                    if (it.isStreaming) it.copy(isStreaming = false, content = it.content.ifBlank { fullContent }, reasoningContent = it.reasoningContent.ifBlank { fullReasoning }) else it
                }))
            }
        }
        watchdogJob.cancel()
    }

    private fun generateTitle(sessionId: String, userMsg: String, aiResponse: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val activeApi = getActiveApiService()
                val prompt = "Generate a very short title (max 20 chars, Chinese preferred) for this conversation.\nUser: ${userMsg.take(200)}\nAssistant: ${aiResponse.take(200)}\nOutput ONLY the title, no quotes, no punctuation."
                val titleMessages = listOf(
                    Message(role = "user", content = prompt)
                )
                val request = ChatRequest(
                    model = _modelIds.value.firstOrNull { it.contains("flash") }
                        ?: _modelIds.value.firstOrNull()
                        ?: "mimo-v2.6-flash",
                    messages = titleMessages,
                    maxCompletionTokens = 50,
                    temperature = 0.3f,
                    stream = false,
                    thinking = ThinkingConfig(type = "disabled")
                )
                val json = gson.toJson(request)
                val body = json.toRequestBody("application/json".toMediaType())
                val httpRequest = Request.Builder()
                    .url("${activeApi.getBaseUrl()}/chat/completions")
                    .addHeader("Authorization", "Bearer ${activeApi.getApiKey()}")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()
                val response = activeApi.getClient().newCall(httpRequest).execute()
                if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: return@launch
                    val resp = gson.fromJson(respBody, ChatResponse::class.java)
                    val rawTitle = resp.choices.firstOrNull()?.message?.content?.toString() ?: ""
                    val title = rawTitle.trim().replace("\"", "").replace("'", "").take(20)
                    if (title.isNotBlank()) {
                        val current = _sessions.value.find { it.id == sessionId }
                        if (current != null && current.title != title) {
                            val updated = current.copy(title = title)
                            _sessions.value = _sessions.value.map { if (it.id == sessionId) updated else it }
                            if (_currentSession.value?.id == sessionId) {
                                _currentSession.value = updated
                            }
                            saveSessions()
                        }
                    }
                }
            } catch (e: Exception) {
                // Silent fail - title will stay as user message excerpt
            }
        }
    }

    fun stopGeneration() {
        streamingJob?.cancel()
        _isGenerating.value = false
        _error.value = null
        _pendingToolCall.value = null
        islandService.cancelNotification()
        val current = _currentSession.value ?: return
        val updatedMessages = current.messages.map {
            if (it.isStreaming) it.copy(isStreaming = false) else it
        }
        updateSession(current.copy(messages = updatedMessages))
    }

    fun approveToolCall() {
        _toolExecuting.value = true
        agentEngine?.approvePendingConfirmation()
        _pendingToolCall.value = null
    }

    fun denyToolCall() {
        agentEngine?.denyPendingConfirmation()
        _pendingToolCall.value = null
    }

    fun permissionLabel(type: String): String = when (type) {
        "accessibility" -> "无障碍服务"
        "overlay" -> "悬浮窗"
        "storage" -> "所有文件访问"
        "projection" -> "屏幕录制"
        "notification" -> "通知"
        "mic" -> "麦克风"
        else -> type
    }

    /** Jump straight to the relevant system Settings page for this permission type. */
    fun openPermissionSettings(type: String) {
        val pkg = context.packageName
        val intent = try {
            when (type) {
                "accessibility" ->
                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                "overlay" ->
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$pkg")
                    )
                "storage" ->
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$pkg")
                        )
                    } else {
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$pkg")
                        )
                    }
                "notification" ->
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
                // projection / mic / anything else: app detail page (closest jumpable surface)
                else ->
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$pkg")
                    )
            }
        } catch (e: Exception) {
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$pkg")
            )
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            _error.value = "无法打开系统设置: ${e.message}"
        }
    }

    fun dismissPermissionRequest() {
        _pendingPermission.value = null
    }

    fun readAloud(text: String) {
        stopSpeaking()
        val filteredText = filterForSpeech(text)
        if (filteredText.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isSpeaking.value = true
                // Streaming playback: 24kHz PCM16LE mono, stream chunks into AudioTrack as they arrive
                val sampleRate = 24000
                val minBuf = android.media.AudioTrack.getMinBufferSize(
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                )
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 48000)) // ≥1s @24kHz mono 16bit
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()
                currentAudioTrack = track
                track.play()

                var totalBytes = 0
                val ok = ttsService.synthesizeStream(filteredText) { chunk ->
                    // blocking write back-pressures naturally against playback buffering
                    totalBytes += track.write(chunk, 0, chunk.size)
                }

                if (ok && totalBytes > 0) {
                    // stream fully received — wait until playback head catches up (PCM16 mono → frames = bytes/2)
                    val writtenFrames = totalBytes / 2
                    val deadline = System.currentTimeMillis() +
                        (writtenFrames * 1000L / sampleRate) + 5_000 // expected duration + 5s grace
                    while (currentAudioTrack === track) {
                        val head = track.playbackHeadPosition
                        if (head >= writtenFrames) break
                        if (System.currentTimeMillis() > deadline) break
                        kotlinx.coroutines.delay(150)
                    }
                }
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                if (currentAudioTrack === track) currentAudioTrack = null
                _isSpeaking.value = false
            } catch (_: Exception) {
                if (currentAudioTrack !== null) {
                    try { currentAudioTrack?.release() } catch (_: Exception) {}
                }
                currentAudioTrack = null
                _isSpeaking.value = false
            }
        }
    }

    fun stopSpeaking() {
        try {
            currentAudioTrack?.stop()
            currentAudioTrack?.release()
        } catch (_: Exception) {}
        currentAudioTrack = null
        _isSpeaking.value = false
    }

    private fun updateSession(session: ChatSession) {
        _currentSession.value = session
        _sessions.value = _sessions.value.map {
            if (it.id == session.id) session else it
        }
        saveSessions()
    }

    private fun getSystemPrompt(): String {
        val base = """You are MiMo, an AI assistant developed by Xiaomi. You are helpful, creative, and friendly.

When thinking through complex problems, structure your reasoning in these phases:

1. Deconstruct
· Identify the core subject and key entities
· List available tools/constraints mentioned
· Clarify what is actually being asked

2. Evaluate / Check / Brainstorm
· Assess feasibility and safety implications
· Consider multiple approaches (practical, creative, humorous)
· Eliminate clearly unworkable options
· Identify the best path forward

3. Draft / Build Response
· Outline the structure of your answer
· Draft key points for each section
· Self-correct: ensure tone matches the request
· Balance humor with substance when appropriate

4. Polish
· Refine wording for clarity and impact
· Ensure logical flow between sections
· Verify factual accuracy
· Finalize the output

Use this structure especially for complex, multi-part, or creative questions. For simple questions, answer directly without over-structuring."""

        // Workspace files (IDENTITY/SOUL/TOOLS/HEARTBEAT) are ONLY for Agent mode.
        // Normal chat uses the embedded prompt above and must not touch the workspace dir.
        val isAgentMode = _currentModel.value.contains("agent") && agentEngine != null
        val agentCtx = if (isAgentMode) agentEngine?.getAgentContext() ?: "" else ""
        val phoneToolDoc = if (com.mimo.app.agent.AgentEngine.PHONE_AGENT_ENABLED) {
            "- execute_phone_action: {\"instruction\":\"Chinese command like 打开微信 or 点击搜索按钮\"}\n" +
            "  Only pass the instruction, the API key is configured automatically.\n"
        } else ""
        val toolCallInstructions = if (isAgentMode) {
"""

You have access to local device tools. When the user asks you to perform file operations or use tools, you MUST respond with a SINGLE raw JSON object. No other text.

CRITICAL OUTPUT RULES:
- Output ONLY the raw JSON object, nothing else
- Do NOT wrap in markdown code blocks (no ```json)
- Do NOT use > quote prefixes
- Do NOT add any explanation before or after the JSON
- Do NOT use any formatting, just the plain JSON

JSON format:
{"thinking":"your reasoning","tool_calls":[{"tool":"tool_name","args":{"key":"value"},"reason":"why"}],"response":"when using tools: one short sentence telling the user what you are about to do (shown live); when no tools: your full final answer"}

IMPORTANT - Tool arguments:
${phoneToolDoc}- read_file: {"path":"file path"}
- write_file: {"path":"file path","content":"file content"}
- list_files: {"path":"directory path"}
- delete_file: {"path":"file path"}
- move_file: {"source":"from","destination":"to"}
- copy_file: {"source":"from","destination":"to"}
- search_files: {"pattern":"search pattern"}
- get_file_info: {"path":"file path"}
- grep: {"pattern":"regex or literal","path":"dir (optional)","include":"*.ext (optional)"}
- edit_file: {"path":"file path","old_text":"exact existing text","new_text":"replacement"}
- current_time: {}
- download_file: {"url":"https://...","filename":"name.ext (optional)"}
- open_app: {"package":"com.package.name"}
- list_apps: {}
- request_permission: {"permission":"accessibility|overlay|storage|projection|notification|mic","reason":"why it is needed"}
  Use when you are blocked by a missing system permission or a tool reports NEED_PERMISSION. The user will be offered a jump to system Settings.

Workspace: ${agentEngine?.workspaceDir?.absolutePath ?: "/sdcard/知白/"}
""".trimIndent()
        } else ""
        // Custom prompt always goes LAST with explicit override priority so the default
        // prompt can never wash it out (it is user-owned configuration, not a suggestion).
        val customPart = if (_customPrompt.value.isNotBlank()) {
            """

${'='}
USER CUSTOM INSTRUCTIONS (HIGHEST PRIORITY)
${'='}
The following instructions are configured by the user and OVERRIDE any conflicting
default instructions, persona, tone, or formatting rules above. If anything in the
sections above contradicts these instructions, ALWAYS follow these instead.

${_customPrompt.value.trim()}"""
        } else ""
        val agentPart = if (isAgentMode && agentCtx.isNotBlank()) "\n\n$agentCtx" else ""
        val faithfulPart = if (_strictFaithful.value) {
            "\n\n严格参考内容信息，不虚构或者加额外的个人推测。When the user provides content (images, video, audio, files, or text), describe and analyze ONLY what is actually present in it. Never fabricate details or add speculative personal assumptions beyond the provided content."
        } else ""

        return base + agentPart + toolCallInstructions + faithfulPart + customPart
    }

    fun getApiModel(): String {
        // Agent variants are local aliases: any "<base>-agent" maps to "<base>"
        val cur = _currentModel.value
        return if (cur.endsWith("-agent")) cur.removeSuffix("-agent") else cur
    }

    private fun isBalanceError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("402") || msg.contains("balance") || msg.contains("insufficient") ||
                msg.contains("quota") || msg.contains("credit") || msg.contains("payment")
    }

    private fun extractJson(raw: String): String {
        var text = raw.trim()
        // Remove markdown code block wrapper
        if (text.startsWith("```")) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline > 0) text = text.substring(firstNewline + 1)
            if (text.endsWith("```")) text = text.dropLast(3)
            text = text.trim()
        }
        // Remove > line prefixes
        text = text.lines().map { line ->
            if (line.trimStart().startsWith("> ")) line.trimStart().removePrefix("> ")
            else line
        }.joinToString("\n").trim()
        // Extract first JSON object
        val start = text.indexOf('{')
        if (start < 0) return text
        var depth = 0
        for (i in start until text.length) {
            if (text[i] == '{') depth++
            else if (text[i] == '}') depth--
            if (depth == 0) return text.substring(start, i + 1)
        }
        return text.substring(start)
    }

    private fun getActiveApiService(): MiMoApiService {
        return if (_currentModel.value.contains("agent") && _agentApiKey.value.isNotBlank()) {
            agentChatService
        } else {
            apiService
        }
    }

    private fun saveSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Clean up: remove empty streaming messages and reset streaming state
                val cleanSessions = _sessions.value.map { session ->
                    session.copy(messages = session.messages
                        .filterNot { it.isStreaming && it.content.isBlank() }
                        .map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                    )
                }
                val json = gson.toJson(cleanSessions)
                val file = File(context.filesDir, "mimo_sessions.json")
                file.writeText(json)
            } catch (_: Exception) {}
        }
    }

    private suspend fun loadSessions(): List<ChatSession>? {
        return try {
            val file = File(context.filesDir, "mimo_sessions.json")
            if (file.exists()) {
                val json = file.readText()
                if (json.isNotBlank()) {
                    val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, ChatSession::class.java).type
                    val sessions: List<ChatSession>? = gson.fromJson(json, type)
                    sessions?.map { session ->
                        session.copy(messages = session.messages
                            .filterNot { it.isStreaming && it.content.isBlank() }
                            .map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                        )
                    }
                } else null
            } else {
                val prefs = context.dataStore.data.first()
                val sessionsJson = prefs[SESSIONS_KEY]
                if (!sessionsJson.isNullOrBlank()) {
                    val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, ChatSession::class.java).type
                    val sessions: List<ChatSession>? = gson.fromJson(sessionsJson, type)
                    if (sessions != null) {
                        file.writeText(sessionsJson)
                        context.dataStore.edit { it.remove(SESSIONS_KEY) }
                    }
                    sessions?.map { session ->
                        session.copy(messages = session.messages
                            .filterNot { it.isStreaming && it.content.isBlank() }
                            .map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                        )
                    }
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun uriToBase64(uri: Uri): String? {
        return try {
            val context = getApplication<Application>()
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            // Resize if too large
            val maxSize = 1024
            val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height, 1f)
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else bitmap

            val outputStream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** Copy a picker content:// URI into app-private storage so it survives process death. */
    private fun persistImage(uri: Uri): String? {
        return try {
            val dir = File(context.filesDir, "chat_images").apply { mkdirs() }
            val ext = when (context.contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val dest = File(dir, "img_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
            val input = context.contentResolver.openInputStream(uri) ?: return uri.toString()
            input.use { ins ->
                dest.outputStream().use { out -> ins.copyTo(out) }
            }
            dest.absolutePath
        } catch (_: Exception) {
            uri.toString()
        }
    }

    /**
     * Best-effort vision capability check by model id.
     * Known multimodal families (flash/pro/ultraspeed, any version) consume image_url parts;
     * everything else (future text-only models, embedding-style ids, etc.) gets text extraction first.
     */
    private fun supportsVision(modelId: String): Boolean {
        val m = modelId.lowercase()
        if (m == "asr" || m.startsWith("tts")) return false
        if (m.contains("ultraspeed")) return false // UltraSpeed is text-only
        return m.contains("flash") || m.contains("pro") ||
                m.contains("vision") || m.contains("vl-") || m.contains("-vl")
    }

    /**
     * Ask the active (vision-capable) model to convert images into a text document,
     * so non-vision models still receive the information. Returns null on failure
     * (caller then sends images as-is).
     */
    private suspend fun extractImageContent(images: List<String>): String? {
        return try {
            val contentParts = mutableListOf<com.mimo.app.data.model.ImageContent>()
            images.take(6).forEach { b64 ->
                contentParts.add(
                    com.mimo.app.data.model.ImageContent(
                        type = "image_url",
                        imageUrl = com.mimo.app.data.model.ImageUrl(url = "data:image/jpeg;base64,$b64")
                    )
                )
            }
            contentParts.add(
                com.mimo.app.data.model.ImageContent(
                    type = "text",
                    text = "请提取图片中的全部内容并整理成结构化文本文档：\n" +
                            "1. 图中所有文字（原文照录，保留原有结构如表格/列表/标题）\n" +
                            "2. 若无文字，简要客观描述图片内容\n" +
                            "只输出整理后的文档内容，不要任何开场白或解释。"
                )
            )
            val request = ChatRequest(
                model = "mimo-v2.6-flash",
                messages = listOf(
                    com.mimo.app.data.model.Message(role = "user", content = contentParts)
                ),
                maxCompletionTokens = 4096,
                temperature = 0.2f,
                stream = false,
                thinking = ThinkingConfig(type = "disabled")
            )
            val response = getActiveApiService().chatCompletionStructured(request)
            val raw = response.choices.firstOrNull()?.message?.content?.toString()?.trim()
            raw?.takeIf { it.isNotBlank() && it != "null" }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Raw-bytes base64 for audio/video (BitmapFactory can't decode them — it returned null,
     * so audio/video were never actually sent). API limit: base64 string <= 50 MB (~37.5 MB file).
     */
    private fun uriToRawBase64(uri: Uri): String {
        val context = getApplication<Application>()
        val maxBytes = 37L * 1024 * 1024
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Cannot open file")
        inputStream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw java.io.IOException("File too large (max ~37 MB for video/audio)")
                }
                buffer.write(chunk, 0, read)
            }
            return Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
        }
    }
    //Are you OK?
    private fun readFileContent(uri: Uri): String? {
        return try {
            val context = getApplication<Application>()
            val mimeType = context.contentResolver.getType(uri) ?: ""

            // Only read text-based files
            val textTypes = setOf(
                "text/", "application/json", "application/xml", "application/javascript",
                "application/x-yaml", "application/yaml", "application/toml",
                "application/csv", "application/sql"
            )
            val isTextFile = textTypes.any { mimeType.startsWith(it) } ||
                    mimeType.endsWith("+xml") || mimeType.endsWith("+json")

            // Also check common text extensions
            val fileName = getFileName(context, uri).lowercase()
            val textExtensions = setOf(
                ".txt", ".md", ".json", ".xml", ".csv", ".yaml", ".yml", ".toml",
                ".js", ".ts", ".py", ".java", ".kt", ".c", ".cpp", ".h", ".css",
                ".html", ".htm", ".sql", ".sh", ".bat", ".ps1", ".rb", ".go", ".rs",
                ".swift", ".dart", ".lua", ".php", ".r", ".m", ".swift", ".log",
                ".cfg", ".conf", ".ini", ".env", ".gitignore", ".dockerfile"
            )
            val isTextByName = textExtensions.any { fileName.endsWith(it) }

            if (!isTextFile && !isTextByName) {
                return "[Binary file: $fileName (${mimeType})]"
            }

            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()

            val maxSize = 100 * 1024
            if (bytes.size > maxSize) {
                String(bytes, 0, maxSize, Charsets.UTF_8) + "\n...(truncated)"
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileName(context: android.content.Context, uri: Uri): String {
        var name = "file"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = it.getString(nameIndex) ?: name
                }
            }
        }
        return name
    }
}

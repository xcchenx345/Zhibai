package com.zhibai.app.viewmodel

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
import com.zhibai.app.data.api.BingSearch
import com.zhibai.app.data.api.MiMoApiService
import com.zhibai.app.data.api.StreamChunk
import com.zhibai.app.data.api.TTSService
import com.zhibai.app.data.api.filterForSpeech
import com.zhibai.app.data.model.*
import com.zhibai.app.i18n.LangManager
import com.zhibai.app.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.io.File

private val Application.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mimo_settings")

/** One speed sample: t = seconds since stream start, think/answer in chars per second. */
data class SpeedPoint(val t: Float, val think: Float, val answer: Float)

/** Local AI model download progress (MNN Chat DownloadInfo style, simplified). */
data class LocalAiProgress(
    /** 0..100, or -1 when sizes are unknown. */
    val percent: Int,
    val bytesDone: Long,
    val bytesTotal: Long?,
    val speedBps: Long,
    val currentFile: String,
    val filesDone: Int,
    val filesTotal: Int,
)

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
    // Pinned to the CLOUD endpoint so refreshModelList can fetch the cloud list
    // even while the active services are routed to the local model.
    private val cloudListService = MiMoApiService()
    // Held so routeForModel can re-point the Agent engine's tool service too.
    private var agentToolService: MiMoApiService? = null
    private val ttsService = TTSService()
    private val islandService = com.zhibai.app.data.api.IslandNotificationService(application)

    /** True once a stream finished normally — finally must not kill the completion capsule. */
    private var lastStreamCompleted = false

    /**
     * The provider's high-risk rejection arrives as raw English in the reply body —
     * an instant "language uncanny valley". Swap it for a friendly Chinese line.
     */
    private fun isHighRiskRejection(text: String): Boolean {
        val t = text.trim()
        return t.contains("request was rejected", ignoreCase = true) &&
            t.contains("high risk", ignoreCase = true)
    }

    private fun sanitizeHighRisk(text: String): String {
        if (isHighRiskRejection(text)) {
            return com.zhibai.app.ui.components.HighRiskRejection.replies.random()
        }
        return text
    }
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
    private val PROMPT_AMBIGUOUS_KEY = stringPreferencesKey("prompt_ambiguous_clarify")
    private val PROMPT_SENSITIVE_KEY = stringPreferencesKey("prompt_sensitive_refusal")
    private val PROMPT_XIAOMI_GUARD_KEY = stringPreferencesKey("prompt_xiaomi_guard")
    private val THIRD_PARTY_BASE_KEY = stringPreferencesKey("third_party_base_url")
    private val THIRD_PARTY_KEY_KEY = stringPreferencesKey("third_party_api_key")
    private val THIRD_PARTY_MODEL_KEY = stringPreferencesKey("third_party_model_id")
    private val UNLIMITED_ATTACH_KEY = stringPreferencesKey("unlimited_attachments")
    private val SESSIONS_KEY = stringPreferencesKey("sessions")
    private val ONBOARDING_KEY = stringPreferencesKey("onboarding_done")
    private val THINKING_LEVEL_KEY = stringPreferencesKey("thinking_level") // 0-4
    // Floating glass input bar
    private val INPUT_STYLE_KEY = stringPreferencesKey("input_style") // "classic" or "glass"
    private val INPUT_BLUR_COLOR_KEY = stringPreferencesKey("input_blur_color") // hex ARGB string

    // Custom theme colors (empty string = use default)
    private val CUSTOM_FONT_COLOR_KEY = stringPreferencesKey("custom_font_color")
    private val CUSTOM_BG_COLOR_KEY = stringPreferencesKey("custom_bg_color")
    private val CUSTOM_BUBBLE_COLOR_KEY = stringPreferencesKey("custom_bubble_color")
    private val CUSTOM_TEXT_COLOR_KEY = stringPreferencesKey("custom_text_color")

    // Custom font (empty = system default)
    private val CUSTOM_FONT_KEY = stringPreferencesKey("custom_font")
    private val INPUT_SHADOW_KEY = stringPreferencesKey("input_shadow_enabled")
    private val INPUT_CORNER_KEY = stringPreferencesKey("input_corner_radius") // dp int
    private val INPUT_SQUIRCLE_KEY = stringPreferencesKey("input_squircle_enabled") // n=3 superellipse
    private val INPUT_BLUR_INTENSITY_KEY = stringPreferencesKey("input_blur_intensity") // 0-100

    // === Preferences (偏好) ===
    private val PREF_DEFAULT_MODEL = stringPreferencesKey("pref_default_model") // "" = last used
    private val PREF_STARTUP_MODE = stringPreferencesKey("pref_startup_mode") // "new" / "last" / "session"
    private val PREF_STARTUP_SESSION = stringPreferencesKey("pref_startup_session") // session id for "session" mode
    private val PREF_AGENT_THINK_LEVEL = stringPreferencesKey("pref_agent_think_level") // 0-4
    private val PREF_AUTO_COMPRESS = stringPreferencesKey("pref_auto_compress") // "true" / "false"
    private val PREF_AUTO_COMPRESS_THRESHOLD = stringPreferencesKey("pref_auto_compress_threshold") // token count string
    private val PREF_LONG_TERM_MEMORY = stringPreferencesKey("pref_long_term_memory") // "true" / "false"
    private val PREF_MEMORY_AUTO_WRITE = stringPreferencesKey("pref_memory_auto_write") // "true" / "false"
    private val PREF_MODEL_HABIT = stringPreferencesKey("pref_model_habit") // suffix habit, max 10 chars
    private val PREF_SCENARIO = stringPreferencesKey("pref_scenario") // "code" / "work" / "daily" / "study" / ""
    private val PREF_ISLAND_EXPAND_ON_PHASE = stringPreferencesKey("pref_island_expand_on_phase") // "true" = re-expand capsule on phase change
    private val PREF_ENABLE_SEARCH = stringPreferencesKey("pref_enable_search") // "true" / "false"
    private val PREF_ISLAND_ENABLED = stringPreferencesKey("pref_island_enabled") // master switch
    private val PREF_ISLAND_STREAMING = stringPreferencesKey("pref_island_streaming") // multi-token scroll
    private val PREF_USER_AGE = stringPreferencesKey("pref_user_age") // int string; required

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
            "tts", "asr"
        )
    )
    val modelIds: StateFlow<List<String>> = _modelIds.asStateFlow()

    val isTTSMode: Boolean get() = _currentModel.value.startsWith("tts")
    val isASRMode: Boolean get() = _currentModel.value == "asr"
    /** TTS screen sub-mode (read/design/clone). Single "tts" entry defaults to read. */
    val ttsScreenMode: String get() {
        val m = _currentModel.value
        return if (m == "tts") "read" else m.removePrefix("tts-").ifBlank { "read" }
    }

    private val _enableThinking = MutableStateFlow(true)
    val enableThinking: StateFlow<Boolean> = _enableThinking.asStateFlow()
    // 0=Off, 1=Low(1024), 2=Medium(4096), 3=High(8192), 4=MAX(16384)
    private val _thinkingLevel = MutableStateFlow(2)
    val thinkingLevel: StateFlow<Int> = _thinkingLevel.asStateFlow()

    private val _enableSearch = MutableStateFlow(true)
    val enableSearch: StateFlow<Boolean> = _enableSearch.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _apiKeyConfigured = MutableStateFlow(false)
    val apiKeyConfigured: StateFlow<Boolean> = _apiKeyConfigured.asStateFlow()

    // Local AI (built-in MNN engine served on 127.0.0.1)
    private val LOCAL_AI_ENABLED_KEY = stringPreferencesKey("local_ai_enabled")
    private val LOCAL_AI_MODEL_KEY = stringPreferencesKey("local_ai_model")
    private val _localAiEnabled = MutableStateFlow(false)
    val localAiEnabled: StateFlow<Boolean> = _localAiEnabled.asStateFlow()
    private val _localAiModel = MutableStateFlow("")
    val localAiModel: StateFlow<String> = _localAiModel.asStateFlow()
    private val _localAiInstalling = MutableStateFlow(false)
    val localAiInstalling: StateFlow<Boolean> = _localAiInstalling.asStateFlow()
    private val _localAiProgress = MutableStateFlow<LocalAiProgress?>(null)
    val localAiProgress: StateFlow<LocalAiProgress?> = _localAiProgress.asStateFlow()

    /** null = still loading prefs; false = show first-run onboarding. */
    private val _onboardingDone = MutableStateFlow<Boolean?>(null)
    val onboardingDone: StateFlow<Boolean?> = _onboardingDone.asStateFlow()

    // Update preview mode — shows v2.6 changelog as a mini onboarding
    private val _showUpdatePreview = MutableStateFlow(false)
    val showUpdatePreview: StateFlow<Boolean> = _showUpdatePreview.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // ---- Agent config file (config.json at workspace root) ----
    private val _agentConfigName = MutableStateFlow("MiMo")
    val agentConfigName: StateFlow<String> = _agentConfigName.asStateFlow()
    /** Non-null when config is broken → show warning dialog. */
    private val _agentConfigError = MutableStateFlow<String?>(null)
    val agentConfigError: StateFlow<String?> = _agentConfigError.asStateFlow()
    /** True when Agent is disabled due to broken config (user must fix or dismiss). */
    private val _agentConfigBroken = MutableStateFlow(false)
    val agentConfigBroken: StateFlow<Boolean> = _agentConfigBroken.asStateFlow()

    /** Load / auto-repair Agent config. Called when Agent is first enabled. */
    fun loadAgentConfig() {
        val engine = agentEngine ?: return
        val ws = engine.workspaceDir
        when (val result = com.zhibai.app.agent.AgentConfig.load(ws)) {
            is com.zhibai.app.agent.AgentConfig.LoadResult.Ok -> {
                _agentConfigName.value = result.config.appName
                _agentConfigError.value = null
                _agentConfigBroken.value = false
            }
            is com.zhibai.app.agent.AgentConfig.LoadResult.Created -> {
                _agentConfigName.value = result.config.appName
                _agentConfigError.value = null
                _agentConfigBroken.value = false
            }
            is com.zhibai.app.agent.AgentConfig.LoadResult.Repaired -> {
                _agentConfigName.value = result.config.appName
                _agentConfigError.value = result.action
                _agentConfigBroken.value = false
            }
            is com.zhibai.app.agent.AgentConfig.LoadResult.Broken -> {
                _agentConfigError.value = result.reason
                _agentConfigBroken.value = true
            }
        }
    }

    /** User chose "回到正常聊天" — dismiss dialog, keep Agent disabled. */
    fun dismissAgentConfigError() {
        _agentConfigError.value = null
        _agentConfigBroken.value = false
    }

    /** User chose "修复配置文件" — force reset to default, re-enable Agent. */
    fun repairAgentConfig() {
        val engine = agentEngine ?: return
        val ok = com.zhibai.app.agent.AgentConfig.reset(engine.workspaceDir)
        if (ok) {
            _agentConfigName.value = "MiMo"
            _agentConfigError.value = LangManager.t("agent.config.reset", "已重置为默认配置")
            _agentConfigBroken.value = false
        } else {
            _agentConfigError.value = LangManager.t("agent.config.resetFail", "修复失败，请检查存储权限")
        }
    }

    /** User dismissed repair-success toast. */
    fun clearAgentConfigMessage() {
        _agentConfigError.value = null
    }

    // Auto read-aloud
    private val _autoReadAloud = MutableStateFlow(false)
    val autoReadAloud: StateFlow<Boolean> = _autoReadAloud.asStateFlow()

    fun toggleAutoReadAloud() {
        _autoReadAloud.value = !_autoReadAloud.value
        if (!_autoReadAloud.value) stopSpeaking()
    }

    /** Strip markdown/code/tables/etc for TTS — only speak readable prose. */
    fun filterForTTS(text: String): String {
        var t = text
        // Remove code blocks
        t = t.replace(Regex("```[\\s\\S]*?```"), "，代码块已省略，")
        // Remove inline code
        t = t.replace(Regex("`[^`]+`"), "")
        // Remove table rows (lines starting/ending with |)
        t = t.replace(Regex("(?m)^\\|.*\\|\\s*$"), "")
        // Remove markdown headers
        t = t.replace(Regex("(?m)^#{1,6}\\s+"), "")
        // Remove bold/italic markers
        t = t.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        t = t.replace(Regex("\\*([^*]+)\\*"), "$1")
        t = t.replace(Regex("__([^_]+)__"), "$1")
        t = t.replace(Regex("_([^_]+)_"), "$1")
        // Remove links, keep text
        t = t.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        // Remove images
        t = t.replace(Regex("!\\[[^\\]]*\\]\\([^)]+\\)"), "")
        // Remove HTML tags
        t = t.replace(Regex("<[^>]+>"), "")
        // Remove horizontal rules
        t = t.replace(Regex("(?m)^[-*_]{3,}\\s*$"), "")
        // Remove LaTeX
        t = t.replace(Regex("\\$[^$]+\\$"), "")
        t = t.replace(Regex("\\$\\$[\\s\\S]*?\\$\\$"), "")
        // Remove Mermaid/diagram blocks
        t = t.replace(Regex("(?m)^(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram|erDiagram|gantt|pie)\\b.*"), "")
        // Remove markdown list bullets
        t = t.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        t = t.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        // Collapse whitespace
        t = t.replace(Regex("\\s{2,}"), " ").trim()
        return t
    }

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

    private val _agentPermissionMode = MutableStateFlow(com.zhibai.app.agent.AgentEngine.MODE_DEFAULT)
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

    // Hidden: base-prompt sentence toggles
    private val _promptAmbiguousClarify = MutableStateFlow(true)
    val promptAmbiguousClarify: StateFlow<Boolean> = _promptAmbiguousClarify.asStateFlow()
    private val _promptSensitiveRefusal = MutableStateFlow(true)
    val promptSensitiveRefusal: StateFlow<Boolean> = _promptSensitiveRefusal.asStateFlow()
    private val _promptXiaomiGuard = MutableStateFlow(true)
    val promptXiaomiGuard: StateFlow<Boolean> = _promptXiaomiGuard.asStateFlow()

    // Third-party (OpenAI-compatible) model config. It drives the chat only
    // when it is configured AND no MiMo key is set - the MiMo path stays strict.
    private val _thirdPartyBaseUrl = MutableStateFlow("")
    val thirdPartyBaseUrl: StateFlow<String> = _thirdPartyBaseUrl.asStateFlow()
    private val _thirdPartyApiKey = MutableStateFlow("")
    val thirdPartyApiKey: StateFlow<String> = _thirdPartyApiKey.asStateFlow()
    private val _thirdPartyModelId = MutableStateFlow("")
    val thirdPartyModelId: StateFlow<String> = _thirdPartyModelId.asStateFlow()
    private val _thirdPartyActive = MutableStateFlow(false)
    val thirdPartyActive: StateFlow<Boolean> = _thirdPartyActive.asStateFlow()
    private val customChatService = com.zhibai.app.data.api.MiMoApiService()

    private fun refreshThirdPartyActive() {
        _thirdPartyActive.value = _thirdPartyModelId.value.isNotBlank() && _apiKey.value.isBlank()
    }

    /** What the chat UI calls the assistant: model id when third-party, else MiMo. */
    val modelDisplayName: String
        get() = if (_thirdPartyActive.value) _thirdPartyModelId.value.trim() else "MiMo"

    // Hidden setting: lift attachment count limits
    private val _unlimitedAttachments = MutableStateFlow(false)
    val unlimitedAttachments: StateFlow<Boolean> = _unlimitedAttachments.asStateFlow()

    // Floating glass input bar prefs
    private val _inputStyle = MutableStateFlow("glass") // "classic" or "glass"
    val inputStyle: StateFlow<String> = _inputStyle.asStateFlow()
    private val _inputBlurColor = MutableStateFlow("#B0FFFFFF") // ARGB hex
    val inputBlurColor: StateFlow<String> = _inputBlurColor.asStateFlow()
    private val _inputShadowEnabled = MutableStateFlow(true)
    val inputShadowEnabled: StateFlow<Boolean> = _inputShadowEnabled.asStateFlow()
    private val _inputCornerRadius = MutableStateFlow(20) // dp
    val inputCornerRadius: StateFlow<Int> = _inputCornerRadius.asStateFlow()
    private val _inputSquircle = MutableStateFlow(false) // n=3 superellipse
    val inputSquircle: StateFlow<Boolean> = _inputSquircle.asStateFlow()
    private val _inputBlurIntensity = MutableStateFlow(50) // 0=transparent, 100=opaque
    val inputBlurIntensity: StateFlow<Int> = _inputBlurIntensity.asStateFlow()

    // Custom theme colors ("" = use default)
    private val _customFontColor = MutableStateFlow("")
    val customFontColor: StateFlow<String> = _customFontColor.asStateFlow()
    private val _customBgColor = MutableStateFlow("")
    val customBgColor: StateFlow<String> = _customBgColor.asStateFlow()
    private val _customBubbleColor = MutableStateFlow("")
    val customBubbleColor: StateFlow<String> = _customBubbleColor.asStateFlow()
    private val _customTextColor = MutableStateFlow("")
    val customTextColor: StateFlow<String> = _customTextColor.asStateFlow()

    // Custom font (file path, "" = system default)
    private val _customFont = MutableStateFlow("")
    val customFont: StateFlow<String> = _customFont.asStateFlow()

    // === Preferences state ===
    private val _prefDefaultModel = MutableStateFlow("")
    val prefDefaultModel: StateFlow<String> = _prefDefaultModel.asStateFlow()
    private val _prefStartupMode = MutableStateFlow("new") // "new" / "last" / "session"
    val prefStartupMode: StateFlow<String> = _prefStartupMode.asStateFlow()
    private val _prefStartupSession = MutableStateFlow("")
    val prefStartupSession: StateFlow<String> = _prefStartupSession.asStateFlow()
    private val _prefAgentThinkLevel = MutableStateFlow(2)
    val prefAgentThinkLevel: StateFlow<Int> = _prefAgentThinkLevel.asStateFlow()
    private val _prefAutoCompress = MutableStateFlow(false)
    val prefAutoCompress: StateFlow<Boolean> = _prefAutoCompress.asStateFlow()
    private val _prefAutoCompressThreshold = MutableStateFlow(800000)
    val prefAutoCompressThreshold: StateFlow<Int> = _prefAutoCompressThreshold.asStateFlow()
    private val _prefLongTermMemory = MutableStateFlow(false)
    val prefLongTermMemory: StateFlow<Boolean> = _prefLongTermMemory.asStateFlow()
    private val _prefMemoryAutoWrite = MutableStateFlow(false)
    val prefMemoryAutoWrite: StateFlow<Boolean> = _prefMemoryAutoWrite.asStateFlow()
    private val _prefModelHabit = MutableStateFlow("")
    val prefModelHabit: StateFlow<String> = _prefModelHabit.asStateFlow()
    private val _prefScenario = MutableStateFlow("")
    val prefScenario: StateFlow<String> = _prefScenario.asStateFlow()

    private val _prefIslandExpandOnPhase = MutableStateFlow(false)
    val prefIslandExpandOnPhase: StateFlow<Boolean> = _prefIslandExpandOnPhase.asStateFlow()

    private val _prefIslandEnabled = MutableStateFlow(true)
    val prefIslandEnabled: StateFlow<Boolean> = _prefIslandEnabled.asStateFlow()

    private val _prefIslandStreaming = MutableStateFlow(true)
    val prefIslandStreaming: StateFlow<Boolean> = _prefIslandStreaming.asStateFlow()

    /** -1 = not filled yet (must prompt). */
    private val _userAge = MutableStateFlow(-1)
    val userAge: StateFlow<Int> = _userAge.asStateFlow()

    val isMinor: Boolean get() = _userAge.value in 0..17

    val hasAgeSet: Boolean get() = _userAge.value >= 0
    // Long-term memory content (from MEMORY.md)
    private val _memoryContent = MutableStateFlow("")
    val memoryContent: StateFlow<String> = _memoryContent.asStateFlow()

    // Token / context management
    companion object {
        const val MAX_CONTEXT_TOKENS = 900_000
        const val COMPRESSION_KEEP_LAST = 10
    }
    private val _estimatedTokens = MutableStateFlow(0)
    val estimatedTokens: StateFlow<Int> = _estimatedTokens.asStateFlow()
    // Offset added to the char-based estimate so it also covers system-prompt/tools
    // overhead the API reports via usage. Recalibrated on every streamed response.
    private var tokenBias = 0
    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing.asStateFlow()
    private val _compressionProgress = MutableStateFlow(0f)
    val compressionProgress: StateFlow<Float> = _compressionProgress.asStateFlow()

    // ---- Agent Plan/Build phase ----
    enum class AgentPhase { PLAN, BUILD }
    // Every new conversation starts in BUILD (user decision 2026-11);
    // switching happens via the switch_phase confirmation card.
    private val _agentPhase = MutableStateFlow(AgentPhase.BUILD)
    val agentPhase: StateFlow<AgentPhase> = _agentPhase.asStateFlow()

    fun setAgentPhase(phase: AgentPhase) {
        _agentPhase.value = phase
    }

    fun switchToBuild() {
        _agentPhase.value = AgentPhase.BUILD
    }

    fun resetAgentPhase() {
        _agentPhase.value = AgentPhase.BUILD
    }

    /** Rough token estimate: ~3.5 chars/token for CJK-heavy text, ~4 for ASCII. */
    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var other = 0
        for (c in text) {
            if (c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF) cjk++
            else other++
        }
        return (cjk / 3 + other / 4 + 1).toInt()
    }

    /** Recalculate estimated tokens for the current session. */
    fun recalcTokens() {
        val session = _currentSession.value
        if (session == null) {
            _estimatedTokens.value = 0
            return
        }
        var total = 0
        for (msg in session.messages) {
            total += estimateTokens(msg.content)
            total += estimateTokens(msg.reasoningContent)
        }
        _estimatedTokens.value = (total + tokenBias).coerceAtLeast(0)
    }

    /**
     * Recalibrate the char-based token estimate against the API-reported usage of a
     * completed stream: bias = real total − char sum, so system-prompt/tools overhead
     * is folded in. Self-corrects on every response; stays roughly valid after
     * compression/deletion (the overhead is near-constant).
     */
    private fun calibrateTokenBias(usage: Usage, messages: List<ChatMessage>) {
        if (usage.totalTokens <= 0) return
        var charSum = 0
        for (msg in messages) {
            charSum += estimateTokens(msg.content)
            charSum += estimateTokens(msg.reasoningContent)
        }
        tokenBias = usage.totalTokens - charSum
    }

    /** Whether the context limit has been reached. */
    val isContextLimitReached: Boolean get() = _estimatedTokens.value >= MAX_CONTEXT_TOKENS

    /**
     * Compress the conversation:
     * - Send all messages to AI asking for a deep summary
     * - Keep last [COMPRESSION_KEEP_LAST] messages
     * - In Agent mode, also preserve tool_group operation records
     */
    fun compressConversation() {
        val session = _currentSession.value ?: return
        if (_isCompressing.value || session.messages.size <= COMPRESSION_KEEP_LAST + 2) return
        if (_isGenerating.value) return

        _isCompressing.value = true
        _compressionProgress.value = 0f
        _error.value = null

        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Build the full conversation text for summarization
                val allMessages = session.messages.filter { !it.isStreaming }
                val convoText = buildString {
                    for (msg in allMessages) {
                        val role = when (msg.role) {
                            "user" -> "用户"
                            "assistant" -> "AI"
                            "tool" -> "工具结果"
                            "tool_group" -> "操作记录"
                            else -> msg.role
                        }
                        appendLine("[$role]: ${msg.content.take(2000)}")
                        if (msg.reasoningContent.isNotBlank()) {
                            appendLine("[思考]: ${msg.reasoningContent.take(1000)}")
                        }
                    }
                }

                _compressionProgress.value = 0.2f

                // Ask AI to summarize
                val activeApi = getActiveApiService()
                val summaryPrompt = """请深度整理以下对话内容，输出一份结构化摘要，包含：
1. 当前讨论的主题和背景
2. 关键要点和结论
3. 未完成的事项或待办
4. 重要的技术细节或决策

对话内容：
$convoText

请用中文输出摘要，简洁但完整。"""

                _compressionProgress.value = 0.4f

                val request = activeApi.buildRequest(
                    messages = listOf(Message(role = "user", content = summaryPrompt)),
                    model = getApiModel(),
                    thinkingConfig = ThinkingConfig(type = "disabled"),
                    enableSearch = false
                )

                var summary = ""
                activeApi.chatCompletion(request).collect { chunk ->
                    if (chunk is StreamChunk.Data) {
                        summary += chunk.content
                        _compressionProgress.value = 0.4f + 0.5f * (summary.length / 2000f).coerceAtMost(1f)
                    }
                }

                _compressionProgress.value = 0.95f

                // Build compressed session: system summary + last N messages
                val keepLast = allMessages.takeLast(COMPRESSION_KEEP_LAST)
                val compressedMsgs = mutableListOf<ChatMessage>()
                compressedMsgs.add(ChatMessage(
                    role = "assistant",
                    content = "[会话摘要 - 以下是之前对话的深度整理]\n\n$summary"
                ))
                compressedMsgs.addAll(keepLast)

                // Replace only the messages the snapshot covered — anything appended
                // while the summary request was in flight stays put.
                val snapshotIds = session.messages.map { it.id }.toSet()
                updateSessionById(session.id) { live ->
                    val added = live.messages.filterNot { it.id in snapshotIds }
                    live.copy(messages = compressedMsgs + added)
                }
                _compressionProgress.value = 1f
                recalcTokens()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user pressed stop
            } catch (e: Exception) {
                _error.value = LangManager.t("chat.compress.fail", "压缩失败: {error}").replace("{error}", e.message ?: "")
            } finally {
                _isCompressing.value = false
            }
        }
    }

    // Agent-detected missing permission → ask user to jump to system Settings
    data class PermissionRequest(val type: String, val reason: String)
    private val _pendingPermission = MutableStateFlow<PermissionRequest?>(null)
    val pendingPermission: StateFlow<PermissionRequest?> = _pendingPermission.asStateFlow()
    // Resumes the suspended agent flow after the user grants (true) or skips (false).
    private var permissionGrant: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    private val _pendingToolCall = MutableStateFlow<com.zhibai.app.agent.ToolCallRequest?>(null)
    val pendingToolCall: StateFlow<com.zhibai.app.agent.ToolCallRequest?> = _pendingToolCall.asStateFlow()

    private val _pendingQuestionnaire = MutableStateFlow<List<com.zhibai.app.agent.QuestionnaireItem>?>(null)
    val pendingQuestionnaire: StateFlow<List<com.zhibai.app.agent.QuestionnaireItem>?> = _pendingQuestionnaire.asStateFlow()

    private val _toolExecuting = MutableStateFlow(false)
    val toolExecuting: StateFlow<Boolean> = _toolExecuting.asStateFlow()

    // File-change cards this process can undo: card message id → pre-mutation snapshots.
    private val fileUndoStore = java.util.concurrent.ConcurrentHashMap<String, List<com.zhibai.app.data.model.FileChange>>()

    /** Restore every file a run changed back to its pre-run content (undo card action). */
    fun undoFileChanges(cardMessageId: String) {
        val changes = fileUndoStore.remove(cardMessageId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            changes.reversed().forEach { c ->
                try {
                    val f = java.io.File(c.path)
                    when {
                        c.oldContent != null -> {
                            f.parentFile?.mkdirs()
                            f.writeText(c.oldContent)
                        }
                        // null snapshot = the file was created by this run → undo deletes it
                        else -> f.delete()
                    }
                } catch (e: Exception) {
                    AppLog.e("undoFileChanges failed for ${c.path}", e)
                }
            }
        }
    }

    /** Incremented by the model's let_it_snow tool and the agent celebration —
     *  ChatScreen mounts the snow layer while this is above zero (it never stops). */
    private val _manualSnowRequest = MutableStateFlow(0)
    val manualSnowRequest: StateFlow<Int> = _manualSnowRequest.asStateFlow()

    fun snowNow() {
        _manualSnowRequest.value = _manualSnowRequest.value + 1
        AppLog.d("snow: triggered #${_manualSnowRequest.value}")
    }

    /** Mental-health support card trigger — any mode's mental_health_support tool
     *  bumps this; ChatScreen shows the hotline card while it is above zero.
     *  Replaces the old proactive scan of every AI reply. */
    private val _mentalHealthRequest = MutableStateFlow(0)
    val mentalHealthRequest: StateFlow<Int> = _mentalHealthRequest.asStateFlow()

    fun showMentalHealthSupport() {
        _mentalHealthRequest.value = _mentalHealthRequest.value + 1
        AppLog.d("mental_health: support card triggered #${_mentalHealthRequest.value}")
    }

    /** User message contains "协议" — warn about unauthorized prompt-jailbreak attempts. */
    private val _protocolWarning = MutableStateFlow(false)
    val protocolWarning: StateFlow<Boolean> = _protocolWarning.asStateFlow()

    fun dismissProtocolWarning() {
        _protocolWarning.value = false
    }

    // === Agent upload → local copy preference (ask / always / never) ===
    private val PREF_AUTO_SAVE_IMAGES = stringPreferencesKey("pref_auto_save_images")
    private val _prefAutoSaveImages = MutableStateFlow("ask")
    val prefAutoSaveImages: StateFlow<String> = _prefAutoSaveImages.asStateFlow()

    /** Pending dialog: agent-mode uploads and the app asks whether to keep a local copy. */
    data class ImageSaveRequest(val paths: List<String>)
    private val _pendingImageSave = MutableStateFlow<ImageSaveRequest?>(null)
    val pendingImageSave: StateFlow<ImageSaveRequest?> = _pendingImageSave.asStateFlow()

    fun setPrefAutoSaveImages(mode: String) {
        _prefAutoSaveImages.value = if (mode in listOf("ask", "always", "never")) mode else "ask"
        viewModelScope.launch { context.dataStore.edit { it[PREF_AUTO_SAVE_IMAGES] = _prefAutoSaveImages.value } }
    }

    /** Dialog result. rememberChoice=true persists the answer as the new default. */
    fun resolveImageSave(save: Boolean, rememberChoice: Boolean) {
        val req = _pendingImageSave.value ?: return
        _pendingImageSave.value = null
        if (rememberChoice) setPrefAutoSaveImages(if (save) "always" else "never")
        if (save) copyUploadsToLocal(req.paths)
    }

    /** Copy agent uploads into the workspace uploads dir (visible in the file manager). */
    private fun copyUploadsToLocal(paths: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val destDir = File(agentEngine?.workspaceDir ?: File(context.filesDir, "mimo_agent"), "uploads").apply { mkdirs() }
            var copied = 0
            paths.forEach { p ->
                try {
                    val src = File(p)
                    if (src.exists()) {
                        src.copyTo(File(destDir, src.name), overwrite = true)
                        copied++
                    }
                } catch (e: Exception) {
                    AppLog.e("image local copy failed: $p", e)
                }
            }
            AppLog.d("agent uploads copied locally: $copied/${paths.size} -> ${destDir.absolutePath}")
        }
    }

    private val _speedStats = MutableStateFlow<SpeedStats?>(null)
    val speedStats: StateFlow<SpeedStats?> = _speedStats.asStateFlow()

    var agentEngine: com.zhibai.app.agent.AgentEngine? = null
        private set

    val agentTaskManager = com.zhibai.app.agent.AgentTaskManager(
        java.io.File(context.filesDir, "agent_tasks.json")
    )

    private var streamingJob: Job? = null
    /** Per-session run slots — the send/stop button and stopGeneration key off the
     *  CURRENT session, so a new chat shows send (not a stale stop), stopping never
     *  touches another conversation's run, and each chat generates independently. */
    private val streamingJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val _generatingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val generatingSessionIds: StateFlow<Set<String>> = _generatingSessionIds.asStateFlow()

    /** Start-of-run bookkeeping (synchronous, before the job launches). */
    private fun markGenerating(sessionId: String) {
        _generatingSessionIds.value = _generatingSessionIds.value + sessionId
        _isGenerating.value = true
    }

    /** End-of-run bookkeeping (finish/error/stop) for one session. */
    private fun markIdle(sessionId: String) {
        streamingJobs.remove(sessionId)
        val rest = _generatingSessionIds.value - sessionId
        _generatingSessionIds.value = rest
        if (rest.isEmpty()) {
            _isGenerating.value = false
            streamingJob = null
        }
    }
    /** Model route change that arrived mid-generation; applied when the run ends. */
    private var pendingRouteModel: String? = null
    private var currentAudioTrack: android.media.AudioTrack? = null

    init {
        // Keep token estimate in sync with the current session automatically.
        viewModelScope.launch {
            _currentSession.collect { recalcTokens() }
        }
        viewModelScope.launch {
            context.dataStore.data.first().let { prefs ->
                val savedKey = prefs[API_KEY] ?: ""
                if (savedKey.isNotBlank()) {
                    _apiKey.value = savedKey.trim()
                    _apiKeyConfigured.value = true
                    apiService.updateApiKey(savedKey.trim())
                    cloudListService.updateApiKey(savedKey.trim())
                    ttsService.updateApiKey(savedKey.trim())
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
                        ?: com.zhibai.app.agent.AgentEngine.MODE_DEFAULT
                } else {
                    // Migrate from old 1-5 risk slider
                    val oldRisk = prefs[AGENT_RISK_KEY]?.toIntOrNull()
                    _agentPermissionMode.value = when {
                        oldRisk == null -> com.zhibai.app.agent.AgentEngine.MODE_DEFAULT
                        oldRisk <= 1 -> com.zhibai.app.agent.AgentEngine.MODE_AUTO
                        oldRisk >= 5 -> com.zhibai.app.agent.AgentEngine.MODE_ASK_ALL
                        else -> com.zhibai.app.agent.AgentEngine.MODE_DEFAULT
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
                prefs[PROMPT_AMBIGUOUS_KEY]?.let { _promptAmbiguousClarify.value = it == "true" }
                prefs[PROMPT_SENSITIVE_KEY]?.let { _promptSensitiveRefusal.value = it == "true" }
                prefs[PROMPT_XIAOMI_GUARD_KEY]?.let { _promptXiaomiGuard.value = it == "true" }
                prefs[THIRD_PARTY_BASE_KEY]?.let { _thirdPartyBaseUrl.value = it }
                prefs[THIRD_PARTY_KEY_KEY]?.let { _thirdPartyApiKey.value = it }
                prefs[THIRD_PARTY_MODEL_KEY]?.let { _thirdPartyModelId.value = it }
                refreshThirdPartyActive()
                prefs[UNLIMITED_ATTACH_KEY]?.let { _unlimitedAttachments.value = it == "true" }
                _onboardingDone.value = prefs[ONBOARDING_KEY] == "true"
                prefs[INPUT_STYLE_KEY]?.let { _inputStyle.value = it }
                prefs[INPUT_BLUR_COLOR_KEY]?.let { _inputBlurColor.value = it }
                prefs[INPUT_SHADOW_KEY]?.let { _inputShadowEnabled.value = it == "true" }
                prefs[INPUT_CORNER_KEY]?.let { _inputCornerRadius.value = it.toIntOrNull() ?: 20 }
                prefs[INPUT_SQUIRCLE_KEY]?.let { _inputSquircle.value = it == "true" }
                prefs[INPUT_BLUR_INTENSITY_KEY]?.let { _inputBlurIntensity.value = it.toIntOrNull() ?: 50 }
                prefs[CUSTOM_FONT_COLOR_KEY]?.let { _customFontColor.value = it }
                prefs[CUSTOM_BG_COLOR_KEY]?.let { _customBgColor.value = it }
                prefs[CUSTOM_BUBBLE_COLOR_KEY]?.let { _customBubbleColor.value = it }
                prefs[CUSTOM_TEXT_COLOR_KEY]?.let { _customTextColor.value = it }
                prefs[CUSTOM_FONT_KEY]?.let { _customFont.value = it }
                // Load preferences
                prefs[PREF_DEFAULT_MODEL]?.let { _prefDefaultModel.value = it }
                prefs[PREF_STARTUP_MODE]?.let { _prefStartupMode.value = it }
                prefs[PREF_STARTUP_SESSION]?.let { _prefStartupSession.value = it }
                prefs[PREF_AGENT_THINK_LEVEL]?.let { _prefAgentThinkLevel.value = it.toIntOrNull() ?: 2 }
                prefs[PREF_AUTO_COMPRESS]?.let { _prefAutoCompress.value = it == "true" }
                prefs[PREF_AUTO_COMPRESS_THRESHOLD]?.let { _prefAutoCompressThreshold.value = it.toIntOrNull() ?: 800000 }
                prefs[PREF_AUTO_SAVE_IMAGES]?.let { _prefAutoSaveImages.value = it }
                prefs[PREF_ENABLE_SEARCH]?.let { _enableSearch.value = it == "true" }
                prefs[PREF_LONG_TERM_MEMORY]?.let { _prefLongTermMemory.value = it == "true" }
                prefs[PREF_MEMORY_AUTO_WRITE]?.let { _prefMemoryAutoWrite.value = it == "true" }
                prefs[PREF_MODEL_HABIT]?.let { _prefModelHabit.value = it }
                prefs[PREF_SCENARIO]?.let { _prefScenario.value = it }
                prefs[PREF_ISLAND_EXPAND_ON_PHASE]?.let { _prefIslandExpandOnPhase.value = it == "true" }
                prefs[PREF_ISLAND_ENABLED]?.let { _prefIslandEnabled.value = it == "true" }
                prefs[PREF_ISLAND_STREAMING]?.let { _prefIslandStreaming.value = it == "true" }
                prefs[PREF_USER_AGE]?.toIntOrNull()?.let { _userAge.value = it }
                islandService.expandOnPhaseChange = _prefIslandExpandOnPhase.value
                islandService.enabled = _prefIslandEnabled.value
                islandService.showStreaming = _prefIslandStreaming.value
                // Load long-term memory file
                if (_prefLongTermMemory.value) loadMemoryFile()
                prefs[THINKING_LEVEL_KEY]?.let {
                    val level = it.toIntOrNull() ?: 2
                    _thinkingLevel.value = level
                    _enableThinking.value = level > 0
                }
                // Local AI prefs — read BEFORE the agent block so its key/url
                // selection can prefer the local endpoint.
                prefs[LOCAL_AI_ENABLED_KEY]?.let { _localAiEnabled.value = it == "true" }
                prefs[LOCAL_AI_MODEL_KEY]?.let { _localAiModel.value = it }
                if (_agentEnabled.value) {
                    val key = if (_agentApiKey.value.isNotBlank()) _agentApiKey.value else _apiKey.value
                    val url = if (_agentApiKey.value.isNotBlank()) _agentBaseUrl.value
                        else "https://api.xiaomimimo.com/v1"
                    agentChatService.updateApiKey(key)
                    agentChatService.updateBaseUrl(url)
                    agentToolService = MiMoApiService().also {
                        it.updateApiKey(key)
                        it.updateBaseUrl(url)
                    }
                    agentEngine = createAgentEngine(agentToolService!!)
                    // Create / auto-repair config.json at workspace root
                    loadAgentConfig()
                }
                // Load saved sessions into the drawer, but always start on a fresh chat
                val savedSessions = loadSessions()
                if (!savedSessions.isNullOrEmpty()) {
                    _sessions.value = savedSessions
                }
            }
            // Every model selection re-points ALL endpoints (cloud ↔ local MNN),
            // so picking the local model from the list switches chat AND agent.
            viewModelScope.launch {
                _currentModel.collect { model -> routeForModel(model) }
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
            val localIds = if (_localAiEnabled.value && _localAiModel.value.isNotBlank()) {
                listOf("local:${_localAiModel.value}") // UI adds the "-agent" twin
            } else emptyList()
            val cloudIds = try {
                cloudListService.listModels()
                    .filter { it.startsWith("mimo-v") }
                    .filterNot { it.contains("tts") || it.contains("asr") }
                    .distinct()
            } catch (_: Exception) {
                emptyList() // keep whatever cloud ids we already show
            }
            val cloudPart = if (cloudIds.isNotEmpty()) cloudIds
                else _modelIds.value.filter { it.startsWith("mimo-v") }
            if (cloudPart.isNotEmpty() || localIds.isNotEmpty()) {
                // Single TTS entry; sub-modes live inside TTSScreen
                _modelIds.value = cloudPart + localIds + listOf("tts")
                val cur = _currentModel.value
                // Never auto-switch models while a run is live — the collector would
                // re-point the endpoints mid-stream and the run dies.
                if (!_isGenerating.value && cur != "asr" && !cur.startsWith("tts") && !isLocalModelId(cur)) {
                    val base = cur.removeSuffix("-agent")
                    if (base !in cloudPart && cur !in cloudPart) {
                        cloudPart.firstOrNull()?.let { _currentModel.value = it }
                    }
                }
            }
        }
    }

    fun completeOnboarding() {
        _onboardingDone.value = true
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[ONBOARDING_KEY] = "true"
            }
        }
    }

    fun resetOnboarding() {
        _onboardingDone.value = false
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs.remove(ONBOARDING_KEY)
            }
        }
    }

    fun showUpdatePreview() {
        _showUpdatePreview.value = true
    }

    fun dismissUpdatePreview() {
        _showUpdatePreview.value = false
    }

    /**
     * Apply personalized content to agent workspace files after onboarding.
     * Overwrites the default templates so IDENTITY / USER / SOUL reflect user prefs.
     */
    fun applyAgentWorkspaceProfile(profile: Map<String, String>) {
        if (profile.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val engine = agentEngine ?: return@launch
                engine.writeWorkspaceFiles(profile)
            } catch (_: Exception) {}
        }
    }

    fun setApiKey(key: String) {
        val raw = key.trim()
        apiService.updateApiKey(raw)
        cloudListService.updateApiKey(raw)
        ttsService.updateApiKey(raw)
        _apiKey.value = raw
        _apiKeyConfigured.value = raw.isNotBlank()
        refreshThirdPartyActive()
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[API_KEY] = raw
            }
        }
    }

    // ------------------------------------------------------------------ local AI

    /** Device support check (internal tier list). */
    fun localAiSupport(): com.zhibai.app.localai.LocalAiSupport.Support =
        com.zhibai.app.localai.LocalAiSupport.check(context)

    /** Battery strategy must be "unrestricted" before install/enable. */
    fun localAiBatteryOk(): Boolean =
        com.zhibai.app.data.api.IslandNotificationService.isBatteryUnrestricted(context)

    fun openLocalAiBatterySettings() =
        com.zhibai.app.data.api.IslandNotificationService.openBatterySettings(context)

    fun isLocalAiInstalled(model: com.zhibai.app.localai.LocalModel): Boolean =
        com.zhibai.app.localai.ModelDownloader.isInstalled(
            model, com.zhibai.app.localai.LocalAiManager.modelDir(context, model.displayName)
        )

    /**
     * Download + install an MNN model. Progress rides the island notification
     * ("安装的时候可以上岛"), completion/failure posts an island alert.
     */
    fun installLocalModel(
        model: com.zhibai.app.localai.LocalModel,
        onDone: (Boolean, String) -> Unit,
    ) {
        if (_localAiInstalling.value) {
            onDone(false, LangManager.t("localai.install.busy", "已有安装任务进行中"))
            return
        }
        _localAiInstalling.value = true
        viewModelScope.launch {
            try {
                islandService.showAgentTaskNotification(
                    taskTitle = LangManager.t("localai.download.title", "下载本地模型"),
                    taskStatus = LangManager.t("localai.download.preparing", "准备 {name} ...").replace("{name}", model.displayName),
                    phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_RUN
                )
                val dest = com.zhibai.app.localai.LocalAiManager.modelDir(context, model.displayName)
                withContext(Dispatchers.IO) {
                    com.zhibai.app.localai.ModelDownloader.install(model, dest) { p ->
                        val pct = when {
                            p.bytesTotal != null && p.bytesTotal > 0 ->
                                ((p.bytesDone * 100) / p.bytesTotal).toInt()
                            p.filesTotal > 0 -> p.filesDone * 100 / p.filesTotal
                            else -> -1
                        }
                        _localAiProgress.value = LocalAiProgress(
                            percent = pct,
                            bytesDone = p.bytesDone,
                            bytesTotal = p.bytesTotal,
                            speedBps = p.speedBps,
                            currentFile = p.currentFile,
                            filesDone = p.filesDone,
                            filesTotal = p.filesTotal,
                        )
                        islandService.showAgentTaskNotification(
                            taskTitle = LangManager.t("localai.download.title", "下载本地模型"),
                            taskStatus = buildString {
                                if (pct >= 0) append(pct).append("% · ")
                                if (p.bytesTotal != null && p.bytesTotal > 0) {
                                    append(humanBytes(p.bytesDone)).append('/')
                                    append(humanBytes(p.bytesTotal))
                                } else {
                                    append(LangManager.t("localai.download.files", "{done}/{total} 文件").replace("{done}", p.filesDone.toString()).replace("{total}", p.filesTotal.toString()))
                                }
                                if (p.speedBps > 0) {
                                    append(" · ").append(humanBytes(p.speedBps)).append("/s")
                                }
                                append(" · ").append(p.currentFile.substringAfterLast('/'))
                            },
                            progress = pct,
                            isIndeterminate = pct < 0,
                            phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_RUN
                        )
                    }
                }
                islandService.showAgentTaskComplete(
                    taskTitle = LangManager.t("localai.install.doneTitle", "本地模型安装完成"),
                    result = LangManager.t("localai.install.ready", "{name} 已就绪").replace("{name}", model.displayName)
                )
                onDone(true, "ok")
            } catch (e: Exception) {
                islandService.showAgentTaskComplete(
                    taskTitle = LangManager.t("localai.install.failTitle", "本地模型安装失败"),
                    result = e.message ?: LangManager.t("common.unknownError", "未知错误")
                )
                onDone(false, e.message ?: LangManager.t("common.unknownError", "未知错误"))
            } finally {
                _localAiInstalling.value = false
                _localAiProgress.value = null
            }
        }
    }

    private fun humanBytes(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024.0 / 1024.0)
        b >= 1024L * 1024 -> "%.0f MB".format(b / 1024.0 / 1024.0)
        else -> "%.0f KB".format(b / 1024.0)
    }

    /**
     * Enable/disable local AI. Caller has already run the warning dialog,
     * device-tier check and battery gate. When enabling, the model must be
     * installed ([isLocalAiInstalled]).
     */
    fun setLocalAiEnabled(enabled: Boolean, displayName: String = _localAiModel.value) {
        viewModelScope.launch {
            if (enabled && displayName.isNotBlank()) {
                _localAiEnabled.value = true
                _localAiModel.value = displayName
                context.dataStore.edit { prefs ->
                    prefs[LOCAL_AI_ENABLED_KEY] = "true"
                    prefs[LOCAL_AI_MODEL_KEY] = displayName
                }
                // Add the local entries to the model list, then jump onto the
                // local chat entry — the _currentModel collector does the actual
                // endpoint routing + engine start.
                refreshModelList()
                if (!isLocalModelId(_currentModel.value)) {
                    setModel("local:$displayName")
                }
            } else {
                _localAiEnabled.value = false
                context.dataStore.edit { prefs ->
                    prefs[LOCAL_AI_ENABLED_KEY] = "false"
                }
                withContext(Dispatchers.IO) { com.zhibai.app.localai.LocalAiManager.stop() }
                refreshModelList()
                if (isLocalModelId(_currentModel.value)) {
                    // Fall back to the first cloud model — the collector re-routes.
                    _currentModel.value =
                        _modelIds.value.firstOrNull { it.startsWith("mimo-v") }
                            ?: "mimo-v2.6-flash"
                }
            }
        }
    }

    /** Ids of the local MNN model entries as they appear in the model list. */
    fun isLocalModelId(id: String): Boolean = id.startsWith("local:")

    /**
     * Delete the installed local model (frees the downloaded files), turn local
     * AI off, and drop its entries from the model list. If the local model is
     * currently selected we hop back to the first cloud model first, so the
     * _currentModel collector re-routes every endpoint to the cloud.
     */
    fun deleteLocalAi() {
        viewModelScope.launch {
            if (isLocalModelId(_currentModel.value)) {
                _currentModel.value =
                    _modelIds.value.firstOrNull { it.startsWith("mimo-v") }
                        ?: "mimo-v2.6-flash"
            }
            _localAiEnabled.value = false
            _localAiModel.value = ""
            context.dataStore.edit { prefs ->
                prefs[LOCAL_AI_ENABLED_KEY] = "false"
                prefs[LOCAL_AI_MODEL_KEY] = ""
            }
            withContext(Dispatchers.IO) {
                com.zhibai.app.localai.LocalAiManager.deleteData(context)
            }
            refreshModelList()
            islandService.showAgentTaskComplete(
                taskTitle = LangManager.t("localai.deleted.title", "本地AI已删除"),
                result = LangManager.t("localai.deleted.detail", "模型文件已清理，本地模型已从列表移除")
            )
        }
    }

    /** "local:MiMo-7B-RL-agent" → "MiMo-7B-RL" (the installed model dir name). */
    fun localModelName(id: String): String =
        id.removePrefix("local:").removeSuffix("-agent")

    /**
     * Follow the selected model: local ids point every service (chat, agent,
     * agent tool engine) at the localhost MNN server and warm the model; cloud
     * ids restore the normal endpoints. Called by the _currentModel collector.
     */
    private fun routeForModel(model: String) {
        // Re-pointing baseUrl/key mid-run sends the next tool round to a different
        // endpoint and the run dies with no output. Defer until the run ends.
        if (_isGenerating.value) {
            pendingRouteModel = model
            return
        }
        if (isLocalModelId(model)) {
            apiService.updateBaseUrl(com.zhibai.app.localai.LocalAiManager.LOCAL_BASE_URL)
            apiService.updateApiKey(com.zhibai.app.localai.LocalAiManager.LOCAL_API_KEY)
            agentChatService.updateBaseUrl(com.zhibai.app.localai.LocalAiManager.LOCAL_BASE_URL)
            agentChatService.updateApiKey(com.zhibai.app.localai.LocalAiManager.LOCAL_API_KEY)
            agentToolService?.updateBaseUrl(com.zhibai.app.localai.LocalAiManager.LOCAL_BASE_URL)
            agentToolService?.updateApiKey(com.zhibai.app.localai.LocalAiManager.LOCAL_API_KEY)
            _apiKeyConfigured.value = true // local needs no cloud key
            val name = localModelName(model)
            if (name.isNotBlank()) {
                viewModelScope.launch(Dispatchers.IO) {
                    com.zhibai.app.localai.LocalAiManager.ensureStarted(context, name)
                    if (!com.zhibai.app.localai.LocalAiManager.isReady) {
                        _error.value = LangManager.t("localai.load.fail", "本地模型加载失败: {error}").replace(
                            "{error}",
                            com.zhibai.app.localai.LocalAiManager.lastError ?: LangManager.t("common.unknownError", "未知错误")
                        )
                    }
                }
            }
        } else {
            apiService.updateBaseUrl("https://api.xiaomimimo.com/v1")
            apiService.updateApiKey(_apiKey.value.trim())
            val key = if (_agentApiKey.value.isNotBlank()) _agentApiKey.value else _apiKey.value
            val url = if (_agentApiKey.value.isNotBlank()) _agentBaseUrl.value
                else "https://api.xiaomimimo.com/v1"
            agentChatService.updateApiKey(key.trim())
            agentChatService.updateBaseUrl(url)
            agentToolService?.updateApiKey(key.trim())
            agentToolService?.updateBaseUrl(url)
            _apiKeyConfigured.value = _apiKey.value.isNotBlank()
        }
        AppLog.d("route: model=$model local=${isLocalModelId(model)} key=${AppLog.keyShape(_apiKey.value)} agentKey=${AppLog.keyShape(_agentApiKey.value)}")
    }

    /** Apply a route change that arrived while a generation was running. */
    private fun flushPendingRoute() {
        val pending = pendingRouteModel ?: return
        pendingRouteModel = null
        routeForModel(pending)
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

    private fun createAgentEngine(toolService: MiMoApiService): com.zhibai.app.agent.AgentEngine {
        return com.zhibai.app.agent.AgentEngine(
            context, toolService,
            workspaceOverride = _workspacePath.value.trim(),
            taskManager = agentTaskManager
        ).also {
            it.zhipuApiKey = _zhipuApiKey.value
            // Surface confirmation requests from all permission modes as a UI dialog
            it.onConfirmationRequested = { tool, args, desc ->
                // Not executing yet — waiting for user, so enable the Approve/Deny buttons
                _toolExecuting.value = false
                _pendingToolCall.value = com.zhibai.app.agent.ToolCallRequest(
                    tool = tool, args = args, reason = desc,
                    // Plain-language summary of this concrete call, for the user only —
                    // aiApproveTool never receives it, so it can't bias the auto-reviewer.
                    annotation = com.zhibai.app.agent.describeToolCall(tool, args)
                )
                // Show the approval card on the Super Island with inline allow/deny buttons
                islandService.showAgentTaskNotification(
                    taskTitle = LangManager.t("island.needAuth.title", "需要授权"),
                    taskStatus = com.zhibai.app.agent.describeToolCall(tool, args).take(80),
                    progress = -1,
                    showConfirmActions = true,
                    phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_AUTH,
                    forceExpand = true,
                    alert = true
                )
            }
            it.onQuestionnaireRequested = { questions ->
                _pendingQuestionnaire.value = questions
                // Super Island: agent is waiting on a questionnaire — come back to answer.
                islandService.showAgentTaskNotification(
                    taskTitle = LangManager.t("island.needQuiz.title", "需要答题"),
                    taskStatus = LangManager.t("island.needQuiz.status", "MiMo 发来了问卷，请回到 App 继续填写（共 {n} 题）").replace("{n}", questions.size.toString()),
                    progress = -1,
                    phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_AUTH,
                    forceExpand = true,
                    alert = true
                )
            }
            // Island notification buttons → approve/deny the pending tool call
            com.zhibai.app.data.api.AgentIslandBridge.approve = { approveToolCall() }
            com.zhibai.app.data.api.AgentIslandBridge.deny = { denyToolCall() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        com.zhibai.app.data.api.AgentIslandBridge.approve = null
        com.zhibai.app.data.api.AgentIslandBridge.deny = null
        // viewModelScope is gone by now — the debounced writer would lose the last
        // edits, so flush on a plain thread.
        Thread { writeSessionsNow() }.start()
    }

    fun setAgentEnabled(enabled: Boolean) {
        _agentEnabled.value = enabled
        if (enabled && agentEngine == null) {
            val key = if (_agentApiKey.value.isNotBlank()) _agentApiKey.value else _apiKey.value
            val url = if (_agentApiKey.value.isNotBlank()) _agentBaseUrl.value else "https://api.xiaomimimo.com/v1"
            agentChatService.updateApiKey(key)
            agentChatService.updateBaseUrl(url)
            agentToolService = MiMoApiService().also {
                it.updateApiKey(key)
                it.updateBaseUrl(url)
            }
            agentEngine = createAgentEngine(agentToolService!!)
            // Load / auto-repair config.json at workspace root
            loadAgentConfig()
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

    fun setPromptAmbiguousClarify(enabled: Boolean) {
        _promptAmbiguousClarify.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { it[PROMPT_AMBIGUOUS_KEY] = enabled.toString() }
        }
    }

    fun setPromptSensitiveRefusal(enabled: Boolean) {
        _promptSensitiveRefusal.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { it[PROMPT_SENSITIVE_KEY] = enabled.toString() }
        }
    }

    fun setPromptXiaomiGuard(enabled: Boolean) {
        _promptXiaomiGuard.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { it[PROMPT_XIAOMI_GUARD_KEY] = enabled.toString() }
        }
    }

    fun setUnlimitedAttachments(enabled: Boolean) {
        _unlimitedAttachments.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { it[UNLIMITED_ATTACH_KEY] = enabled.toString() }
        }
    }

    fun setInputStyle(style: String) {
        _inputStyle.value = style
        viewModelScope.launch { context.dataStore.edit { it[INPUT_STYLE_KEY] = style } }
    }

    fun setInputBlurColor(color: String) {
        _inputBlurColor.value = color
        viewModelScope.launch { context.dataStore.edit { it[INPUT_BLUR_COLOR_KEY] = color } }
    }

    fun setInputShadowEnabled(enabled: Boolean) {
        _inputShadowEnabled.value = enabled
        viewModelScope.launch { context.dataStore.edit { it[INPUT_SHADOW_KEY] = enabled.toString() } }
    }

    fun setInputCornerRadius(dp: Int) {
        _inputCornerRadius.value = dp.coerceIn(8, 32)
        viewModelScope.launch { context.dataStore.edit { it[INPUT_CORNER_KEY] = _inputCornerRadius.value.toString() } }
    }

    fun setInputSquircle(enabled: Boolean) {
        _inputSquircle.value = enabled
        viewModelScope.launch { context.dataStore.edit { it[INPUT_SQUIRCLE_KEY] = enabled.toString() } }
    }

    fun setInputBlurIntensity(intensity: Int) {
        _inputBlurIntensity.value = intensity.coerceIn(0, 100)
        viewModelScope.launch { context.dataStore.edit { it[INPUT_BLUR_INTENSITY_KEY] = _inputBlurIntensity.value.toString() } }
    }

    fun setCustomFontColor(color: String) {
        _customFontColor.value = color
        viewModelScope.launch { context.dataStore.edit { it[CUSTOM_FONT_COLOR_KEY] = color } }
    }

    fun setCustomBgColor(color: String) {
        _customBgColor.value = color
        viewModelScope.launch { context.dataStore.edit { it[CUSTOM_BG_COLOR_KEY] = color } }
    }

    fun setCustomBubbleColor(color: String) {
        _customBubbleColor.value = color
        viewModelScope.launch { context.dataStore.edit { it[CUSTOM_BUBBLE_COLOR_KEY] = color } }
    }

    fun setCustomTextColor(color: String) {
        _customTextColor.value = color
        viewModelScope.launch { context.dataStore.edit { it[CUSTOM_TEXT_COLOR_KEY] = color } }
    }

    fun setCustomFont(fontPath: String) {
        _customFont.value = fontPath
        viewModelScope.launch { context.dataStore.edit { it[CUSTOM_FONT_KEY] = fontPath } }
    }

    // === Preference setters ===
    fun setPrefDefaultModel(model: String) {
        _prefDefaultModel.value = model
        viewModelScope.launch { context.dataStore.edit { it[PREF_DEFAULT_MODEL] = model } }
    }

    fun setPrefStartupMode(mode: String) {
        _prefStartupMode.value = mode
        viewModelScope.launch { context.dataStore.edit { it[PREF_STARTUP_MODE] = mode } }
    }

    fun setPrefStartupSession(sessionId: String) {
        _prefStartupSession.value = sessionId
        viewModelScope.launch { context.dataStore.edit { it[PREF_STARTUP_SESSION] = sessionId } }
    }

    fun setPrefAgentThinkLevel(level: Int) {
        _prefAgentThinkLevel.value = level.coerceIn(0, 4)
        viewModelScope.launch { context.dataStore.edit { it[PREF_AGENT_THINK_LEVEL] = _prefAgentThinkLevel.value.toString() } }
    }

    fun setPrefAutoCompress(enabled: Boolean) {
        _prefAutoCompress.value = enabled
        viewModelScope.launch { context.dataStore.edit { it[PREF_AUTO_COMPRESS] = enabled.toString() } }
    }

    fun setPrefAutoCompressThreshold(threshold: Int) {
        _prefAutoCompressThreshold.value = threshold.coerceIn(100000, 900000)
        viewModelScope.launch { context.dataStore.edit { it[PREF_AUTO_COMPRESS_THRESHOLD] = _prefAutoCompressThreshold.value.toString() } }
    }

    fun setPrefLongTermMemory(enabled: Boolean) {
        _prefLongTermMemory.value = enabled
        viewModelScope.launch { context.dataStore.edit { it[PREF_LONG_TERM_MEMORY] = enabled.toString() } }
        if (enabled) loadMemoryFile() else _memoryContent.value = ""
    }

    fun setPrefMemoryAutoWrite(enabled: Boolean) {
        _prefMemoryAutoWrite.value = enabled
        viewModelScope.launch { context.dataStore.edit { it[PREF_MEMORY_AUTO_WRITE] = enabled.toString() } }
    }

    fun setPrefModelHabit(habit: String) {
        _prefModelHabit.value = habit.take(10)
        viewModelScope.launch { context.dataStore.edit { it[PREF_MODEL_HABIT] = _prefModelHabit.value } }
    }

    fun setPrefScenario(scenario: String) {
        _prefScenario.value = scenario
        viewModelScope.launch { context.dataStore.edit { it[PREF_SCENARIO] = scenario } }
    }

    fun setPrefIslandExpandOnPhase(enabled: Boolean) {
        _prefIslandExpandOnPhase.value = enabled
        islandService.expandOnPhaseChange = enabled
        viewModelScope.launch { context.dataStore.edit { it[PREF_ISLAND_EXPAND_ON_PHASE] = enabled.toString() } }
    }

    fun setPrefIslandEnabled(enabled: Boolean) {
        _prefIslandEnabled.value = enabled
        islandService.enabled = enabled
        if (!enabled) islandService.cancelNotification()
        viewModelScope.launch { context.dataStore.edit { it[PREF_ISLAND_ENABLED] = enabled.toString() } }
    }

    fun setPrefIslandStreaming(enabled: Boolean) {
        _prefIslandStreaming.value = enabled
        islandService.showStreaming = enabled
        viewModelScope.launch { context.dataStore.edit { it[PREF_ISLAND_STREAMING] = enabled.toString() } }
    }

    fun setUserAge(age: Int) {
        val clamped = age.coerceIn(1, 120)
        _userAge.value = clamped
        viewModelScope.launch { context.dataStore.edit { it[PREF_USER_AGE] = clamped.toString() } }
    }

    // === Long-term memory (MEMORY.md) ===
    private fun getMemoryFile(): java.io.File {
        val dir = com.zhibai.app.agent.AgentEngine(context, getActiveApiService()).workspaceDir
        return java.io.File(dir, "MEMORY.md")
    }

    fun loadMemoryFile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = getMemoryFile()
                if (file.exists()) {
                    _memoryContent.value = file.readText()
                }
            } catch (_: Exception) {}
        }
    }

    fun saveMemoryFile(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = getMemoryFile()
                file.parentFile?.mkdirs()
                file.writeText(content)
                _memoryContent.value = content
            } catch (_: Exception) {}
        }
    }

    /**
     * Analyze a crash log with AI.
     * Returns CrashAnalysis with severity (1-5), responsibility (A/B/C), summary, suggestions.
     */
    suspend fun analyzeCrash(crashLog: String): com.zhibai.app.ui.screens.CrashAnalysis? {
        return try {
            // Use first available model from API
            val model = try {
                val models = getActiveApiService().listModels()
                models.firstOrNull { it.contains("mimo-v") && !it.contains("tts") && !it.contains("asr") }
                    ?: models.firstOrNull()
                    ?: getApiModel()
            } catch (_: Exception) { getApiModel() }

            val prompt = """你是崩溃分析专家。分析以下 Android 应用崩溃日志。

评估两个维度：
1. 严重等级（1-5）：1=轻微自身问题 2=一般自身问题 3=应用+自身问题 4=严重应用问题 5=致命崩溃+设备问题
2. 责任归属：C=自身问题 B=应用+自己问题 A=应用问题（C→A 越来越严重）

崩溃日志：
$crashLog

严格按以下格式回复（每行一个字段，不要多余文字）：
SEVERITY: [1-5的数字]
RESPONSIBILITY: [A/B/C]
SUMMARY: [一句话总结崩溃原因]
SUGGESTIONS: [用户可以做什么，如改设置/重装/无法自行解决需反馈GitHub，多项用分号分隔]"""

            val request = getActiveApiService().buildRequest(
                messages = listOf(Message(role = "user", content = prompt)),
                model = model,
                thinkingConfig = ThinkingConfig(type = "disabled"),
                enableSearch = false
            )
            var result = ""
            getActiveApiService().chatCompletion(request).collect { chunk ->
                if (chunk is StreamChunk.Data) result += chunk.content
            }

            // Parse response
            val severity = Regex("SEVERITY:\\s*(\\d)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 3
            val responsibility = Regex("RESPONSIBILITY:\\s*([ABC])").find(result)?.groupValues?.get(1) ?: "B"
            val summary = Regex("SUMMARY:\\s*(.+)").find(result)?.groupValues?.get(1)?.trim() ?: LangManager.t("crash.unknownReason", "未知原因")
            val suggestions = Regex("SUGGESTIONS:\\s*(.+)").find(result)?.groupValues?.get(1)?.trim() ?: ""

            com.zhibai.app.ui.screens.CrashAnalysis(
                severity = severity.coerceIn(1, 5),
                responsibility = responsibility,
                summary = summary,
                suggestions = suggestions,
                raw = result
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Backup all conversations to Download directory. */
    fun backupAllChats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allData = gson.toJson(_sessions.value)
                val fileName = "mimo_chats_backup_${System.currentTimeMillis()}.json"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download")
                    }
                    val uri = context.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let { context.contentResolver.openOutputStream(it)?.use { it.write(allData.toByteArray()) } }
                } else {
                    val dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                    java.io.File(dir, fileName).writeText(allData)
                }
            } catch (_: Exception) {}
        }
    }

    /** Auto-write memory after assistant response (if enabled). */
    private fun maybeAutoWriteMemory(userText: String, assistantReply: String) {
        if (!_prefMemoryAutoWrite.value || !_prefLongTermMemory.value) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing = _memoryContent.value
                val prompt = """你是记忆整理助手。根据以下对话，提取值得长期记住的信息（用户偏好、重要事实、技术决策等）。
已有记忆：
$existing

新对话：
用户：$userText
AI：$assistantReply

如果无需更新，回复"无需更新"。否则输出完整的更新后记忆内容（Markdown格式）。"""

                val request = getActiveApiService().buildRequest(
                    messages = listOf(Message(role = "user", content = prompt)),
                    model = getApiModel(),
                    thinkingConfig = ThinkingConfig(type = "disabled"),
                    enableSearch = false
                )
                var result = ""
                getActiveApiService().chatCompletion(request).collect { chunk ->
                    if (chunk is StreamChunk.Data) result += chunk.content
                }
                if (result.isNotBlank() && result.trim() != "无需更新") {
                    saveMemoryFile(result.trim())
                }
            } catch (_: Exception) {}
        }
    }

    fun setAgentApiKey(key: String) {
        val raw = key.trim()
        _agentApiKey.value = raw
        val effectiveKey = if (raw.isNotBlank()) raw else _apiKey.value
        val effectiveUrl = if (raw.isNotBlank()) _agentBaseUrl.value else "https://api.xiaomimimo.com/v1"
        agentChatService.updateApiKey(effectiveKey)
        agentChatService.updateBaseUrl(effectiveUrl)
        agentEngine?.let {
            agentToolService = MiMoApiService().also { svc ->
                svc.updateApiKey(effectiveKey)
                svc.updateBaseUrl(effectiveUrl)
            }
            agentEngine = createAgentEngine(agentToolService!!)
        }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[AGENT_API_KEY] = raw
            }
        }
    }

    fun setAgentBaseUrl(url: String) {
        _agentBaseUrl.value = url
        val effectiveKey = if (_agentApiKey.value.isNotBlank()) _agentApiKey.value else _apiKey.value
        agentChatService.updateApiKey(effectiveKey)
        agentChatService.updateBaseUrl(url)
        agentEngine?.let {
            agentToolService = MiMoApiService().also { svc ->
                svc.updateApiKey(effectiveKey)
                svc.updateBaseUrl(url)
            }
            agentEngine = createAgentEngine(agentToolService!!)
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
            agentToolService = MiMoApiService().also { svc ->
                svc.updateApiKey(
                    if (_agentApiKey.value.isNotBlank()) _agentApiKey.value else _apiKey.value
                )
                svc.updateBaseUrl(
                    if (_agentApiKey.value.isNotBlank()) _agentBaseUrl.value
                        else "https://api.xiaomimimo.com/v1"
                )
            }
            agentEngine = createAgentEngine(agentToolService!!)
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

    fun saveThirdPartyConfig(baseUrl: String, apiKey: String, modelId: String) {
        _thirdPartyBaseUrl.value = baseUrl.trim()
        _thirdPartyApiKey.value = apiKey.trim()
        _thirdPartyModelId.value = modelId.trim()
        refreshThirdPartyActive()
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[THIRD_PARTY_BASE_KEY] = _thirdPartyBaseUrl.value
                prefs[THIRD_PARTY_KEY_KEY] = _thirdPartyApiKey.value
                prefs[THIRD_PARTY_MODEL_KEY] = _thirdPartyModelId.value
            }
        }
    }

    /** Probe an OpenAI-compatible endpoint. Returns null on success, else an error. */
    suspend fun testThirdPartyConnection(baseUrl: String, apiKey: String, modelId: String): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL(baseUrl.trim().removeSuffix("/") + "/chat/completions")
                val conn = url.openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                    conn.setRequestProperty("Content-Type", "application/json")
                    val body = gson.toJson(mapOf(
                        "model" to modelId.trim(),
                        "messages" to listOf(mapOf("role" to "user", "content" to "hi")),
                        "max_tokens" to 1
                    ))
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code in 200..299) null else "HTTP $code"
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                e.message ?: "error"
            }
        }
    }

    fun setModel(model: String) {
        // Speech modes need the MiMo stack - closed when third-party is active.
        if (_thirdPartyActive.value && (model == "asr" || model.startsWith("tts"))) return
        if (_currentModel.value != model) {
            AppLog.d("model switch: ${_currentModel.value} -> $model")
            _currentModel.value = model
            // Route endpoints follow the selection (init collector → routeForModel).
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

    fun setThinkingLevel(level: Int) {
        _thinkingLevel.value = level.coerceIn(0, 4)
        _enableThinking.value = level > 0
        viewModelScope.launch {
            context.dataStore.edit { it[THINKING_LEVEL_KEY] = _thinkingLevel.value.toString() }
        }
    }

    /**
     * Speech-to-text via mimo-v2.5-asr (wav/mp3 base64).
     * Returns recognized text, or null on failure (see [TTSService.lastAsrError]).
     */
    suspend fun speechToText(audioBase64: String, mimeType: String = "audio/wav"): String? {
        // ASR/TTS are platform endpoints — they take the MAIN key. Preferring the
        // agent key here sent a stale/garbage one and 401'd every transcription.
        ttsService.updateApiKey(
            if (_apiKey.value.isNotBlank()) _apiKey.value else _agentApiKey.value
        )
        return ttsService.speechToText(audioBase64, mimeType)
    }

    val lastAsrError: String? get() = ttsService.lastAsrError

    private fun buildThinkingConfig(): ThinkingConfig {
        return when (_thinkingLevel.value) {
            0 -> ThinkingConfig(type = "disabled")
            1 -> ThinkingConfig(type = "enabled", budget = 1024)
            2 -> ThinkingConfig(type = "enabled", budget = 4096)
            3 -> ThinkingConfig(type = "enabled", budget = 8192)
            // MAX: force both thinking budget (length) and completion budget high
            4 -> ThinkingConfig(type = "enabled", budget = 32768)
            else -> ThinkingConfig(type = "enabled", budget = 4096)
        }
    }

    fun toggleSearch() {
        _enableSearch.value = !_enableSearch.value
        viewModelScope.launch { context.dataStore.edit { it[PREF_ENABLE_SEARCH] = _enableSearch.value.toString() } }
    }

    fun toggleSettings() {
        _showSettings.value = !_showSettings.value
    }

    // ---- Skills Center ----
    private val _showSkillsCenter = MutableStateFlow(false)
    val showSkillsCenter: StateFlow<Boolean> = _showSkillsCenter.asStateFlow()
    private val _skillList = MutableStateFlow<List<com.zhibai.app.agent.AgentEngine.SkillEntry>>(emptyList())
    val skillList: StateFlow<List<com.zhibai.app.agent.AgentEngine.SkillEntry>> = _skillList.asStateFlow()
    private val _skillContent = MutableStateFlow<Pair<String, String>?>(null) // name to content
    val skillContent: StateFlow<Pair<String, String>?> = _skillContent.asStateFlow()

    fun toggleSkillsCenter() {
        _showSkillsCenter.value = !_showSkillsCenter.value
        if (_showSkillsCenter.value) refreshSkillList()
    }

    fun refreshSkillList() {
        _skillList.value = agentEngine?.listSkillsDetailed() ?: emptyList()
    }

    fun openSkill(name: String) {
        val content = agentEngine?.readSkillContent(name) ?: return
        _skillContent.value = name to content
    }

    fun closeSkill() {
        _skillContent.value = null
    }

    fun createNewSession() {
        val session = ChatSession(model = _currentModel.value)
        synchronized(sessionLock) { _currentSession.value = session }
        agentTaskManager.bindConversation(session.id)
        AppLog.d("session new: ${session.id.take(8)} model=${session.model}")
        resetAgentPhase()
    }

    fun selectSession(sessionId: String) {
        val target = _sessions.value.find { it.id == sessionId } ?: return
        synchronized(sessionLock) { _currentSession.value = target }
        agentTaskManager.bindConversation(sessionId)
        AppLog.d("session select: ${sessionId.take(8)} msgs=${target.messages.size} model=${target.model}")
        // Restore this session's own model (each session keeps its selection)
        val sessionModel = target.model
        if (sessionModel.isNotBlank() && _currentModel.value != sessionModel) {
            _currentModel.value = sessionModel
        }
        recalcTokens()
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
            _currentSession.value?.let { agentTaskManager.bindConversation(it.id) }
        }
        saveSessions()
    }

    fun toggleArchiveSession(sessionId: String) {
        val target = _sessions.value.find { it.id == sessionId } ?: return
        val updated = target.copy(archived = !target.archived, lastActiveAt = System.currentTimeMillis())
        _sessions.value = _sessions.value.map { if (it.id == sessionId) updated else it }
        if (_currentSession.value?.id == sessionId) {
            _currentSession.value = updated
        }
        saveSessions()
    }

    // ---- Archive page ----
    private val _showArchivePage = MutableStateFlow(false)
    val showArchivePage: StateFlow<Boolean> = _showArchivePage.asStateFlow()

    fun toggleArchivePage() {
        _showArchivePage.value = !_showArchivePage.value
    }

    // ---- 产物中心 page ----
    private val _showArtifactsPage = MutableStateFlow(false)
    val showArtifactsPage: StateFlow<Boolean> = _showArtifactsPage.asStateFlow()

    fun toggleArtifactsPage() {
        _showArtifactsPage.value = !_showArtifactsPage.value
    }

    fun restoreSessions(ids: Set<String>) {
        _sessions.value = _sessions.value.map { s ->
            if (s.id in ids) s.copy(archived = false, lastActiveAt = System.currentTimeMillis()) else s
        }
        saveSessions()
    }

    fun archiveSessions(ids: Set<String>) {
        _sessions.value = _sessions.value.map { s ->
            if (s.id in ids) s.copy(archived = true, lastActiveAt = System.currentTimeMillis()) else s
        }
        saveSessions()
    }

    fun deleteSessions(ids: Set<String>) {
        _sessions.value = _sessions.value.filter { it.id !in ids }
        if (_currentSession.value?.id in ids) {
            _currentSession.value = _sessions.value.lastOrNull()
            _currentSession.value?.let { agentTaskManager.bindConversation(it.id) }
        }
        saveSessions()
    }

    fun exportArchivedSessions(encrypt: Boolean, password: String): String {
        val archived = _sessions.value.filter { it.archived }
        val json = gson.toJson(archived)
        return if (encrypt) {
            "ZB1:" + aesEncrypt(json, password)
        } else {
            "ZB0:" + json
        }
    }

    fun importSessions(raw: String, password: String): String {
        return try {
            val payload = when {
                raw.startsWith("ZB1:") -> {
                    if (password.isEmpty()) return "NEED_PASSWORD"
                    aesDecrypt(raw.removePrefix("ZB1:"), password) ?: return "NEED_PASSWORD"
                }
                raw.startsWith("ZB0:") -> raw.removePrefix("ZB0:")
                else -> raw // try parse as plain JSON
            }
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, ChatSession::class.java
            ).type
            val imported: List<ChatSession> = gson.fromJson(payload, type)
            if (imported.isEmpty()) return LangManager.t("archive.import.none", "没有可导入的对话")
            // Merge: keep existing, append new ones that don't have same id
            val existingIds = _sessions.value.map { it.id }.toSet()
            val newOnes = imported.filter { it.id !in existingIds }.map { it.copy(archived = true) }
            if (newOnes.isEmpty()) return LangManager.t("archive.import.exists", "所有对话已存在")
            _sessions.value = _sessions.value + newOnes
            saveSessions()
            LangManager.t("archive.import.done", "已导入 {n} 个对话").replace("{n}", newOnes.size.toString())
        } catch (e: Exception) {
            LangManager.t("archive.import.fail", "导入失败: {error}").replace("{error}", e.message ?: "")
        }
    }

    private fun aesEncrypt(plain: String, password: String): String {
        return try {
            val keySpec = javax.crypto.spec.SecretKeySpec(
                java.security.MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8)),
                "AES"
            )
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16) { 1 }
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, javax.crypto.spec.IvParameterSpec(iv))
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            plain
        }
    }

    private fun aesDecrypt(encoded: String, password: String): String? {
        return try {
            val keySpec = javax.crypto.spec.SecretKeySpec(
                java.security.MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8)),
                "AES"
            )
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16) { 1 }
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, javax.crypto.spec.IvParameterSpec(iv))
            val decrypted = cipher.doFinal(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP))
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun editAndResend(messageId: String, newText: String) {
        val session = _currentSession.value ?: return
        if (newText.isBlank()) return
        if (_generatingSessionIds.value.contains(session.id)) return
        AppLog.d("editAndResend: msg=${messageId.take(8)} len=${newText.length}")

        val msgIndex = session.messages.indexOfFirst { it.id == messageId }
        if (msgIndex < 0 || session.messages[msgIndex].role != "user") return

        // Remove the user message and the assistant response after it
        val messagesBefore = session.messages.take(msgIndex)
        val messagesAfter = session.messages.drop(msgIndex + 1).dropWhile { it.role == "assistant" }

        // Edit text only — preserve imageUri / fileName so re-sent message keeps attachments
        val editedUserMsg = session.messages[msgIndex].copy(content = newText)
        val assistantMsg = ChatMessage(role = "assistant", content = "", isStreaming = true, flatProcess = true,
            threadOf = "${editedUserMsg.id}#${editedUserMsg.branchIndex}")
        // Relink the kept tail under the new answer so it stays on the visible path.
        var prevKey = "${assistantMsg.id}#${assistantMsg.branchIndex}"
        val relinkedAfter = messagesAfter.map { m ->
            m.copy(threadOf = prevKey).also { prevKey = "${it.id}#${it.branchIndex}" }
        }

        val updatedMessages = messagesBefore + editedUserMsg + assistantMsg + relinkedAfter
        updateSessionById(session.id) { s -> s.copy(messages = updatedMessages) }

        // Now trigger the API call
        markGenerating(session.id)
        _error.value = null
        val editJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiMessages = mutableListOf<Message>(
                    Message(role = "system", content = getSystemPrompt())
                )

                // Add conversation history up to the edited message; re-attach image on the edited msg
                val history = visibleMessages(updatedMessages).filter { it.role == "user" || (it.role == "assistant" && it.content.isNotBlank()) }
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
                    thinkingConfig = buildThinkingConfig(),
                    enableSearch = _enableSearch.value
                )

                var fullContent = ""
                var fullReasoning = ""
                var allAnnotations = mutableListOf<com.zhibai.app.data.model.Annotation>()
                var lastDataTime = System.currentTimeMillis()
                var highRiskRejected = false
                var highRiskFalsePositive = false

                val watchdogJob = launch {
                    while (true) {
                        kotlinx.coroutines.delay(10_000)
                        if (System.currentTimeMillis() - lastDataTime > 300_000) {
                            markIdle(session.id)
                            updateSessionById(session.id) { s ->
                                s.copy(messages = s.messages.map {
                                    if (it.isStreaming) it.copy(
                                        isStreaming = false,
                                        content = it.content.ifBlank { LangManager.t("chat.timeout.300", "响应超时（300 秒无数据）") }
                                    ) else it
                                })
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
                            if (highRiskRejected && !highRiskFalsePositive && chunk.content.isNotBlank()) {
                                highRiskFalsePositive = true
                            } else if (!highRiskRejected && !highRiskFalsePositive && isHighRiskRejection(fullContent)) {
                                highRiskRejected = true
                            }
                            chunk.annotations?.let { allAnnotations.addAll(it) }
                            updateSessionById(session.id) { s ->
                                val updatedMsgs = s.messages.toMutableList()
                                val lastIdx = updatedMsgs.indexOfLast { it.isStreaming }
                                if (lastIdx >= 0) {
                                    updatedMsgs[lastIdx] = updatedMsgs[lastIdx].copy(
                                        content = if (highRiskFalsePositive) fullContent else sanitizeHighRisk(fullContent),
                                        reasoningContent = fullReasoning,
                                        annotations = if (allAnnotations.isNotEmpty()) allAnnotations.toList() else null
                                    )
                                    s.copy(messages = updatedMsgs)
                                } else s
                            }
                        }
                        is StreamChunk.Done -> {
                            markIdle(session.id)
                            watchdogJob.cancel()
                            lastStreamCompleted = true
                            islandService.showAgentTaskComplete(
                                taskTitle = LangManager.t("island.done.title", "输出完成"),
                                result = fullContent.takeLast(80).ifBlank { LangManager.t("island.done.hint", "回复已完成，点击查看") }
                            )
                            val cur = _sessions.value.find { it.id == session.id }
                            chunk.usage?.let { calibrateTokenBias(it, cur?.messages ?: emptyList()) }
                            updateSessionById(session.id) { s ->
                                val updatedMsgs = s.messages.toMutableList()
                                val lastIdx = updatedMsgs.indexOfLast { it.isStreaming }
                                if (lastIdx >= 0) {
                                    val anns = if (allAnnotations.isNotEmpty()) allAnnotations.toList() else null
                                    updatedMsgs[lastIdx] = updatedMsgs[lastIdx].copy(
                                        isStreaming = false,
                                        content = withReferenceFallback(updatedMsgs[lastIdx].content, anns),
                                        annotations = anns
                                    )
                                    s.copy(messages = updatedMsgs)
                                } else s
                            }
                            recalcTokens()
                        }
                    }
                }
                watchdogJob.cancel()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user pressed stop — not an error
            } catch (e: Exception) {
                _error.value = e.message ?: "Unknown error"
                updateSessionById(session.id) { s ->
                    s.copy(messages = s.messages
                        .filterNot { it.role == "assistant" && it.content.isBlank() && it.isStreaming }
                        .map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                    )
                }
            } finally {
                markIdle(session.id)
                flushPendingRoute()
            }
        }
        streamingJobs[session.id] = editJob
    }

    /** Branch identity of a message: its id plus the active version. */
    private fun branchKey(m: ChatMessage): String = "${m.id}#${m.branchIndex}"

    /** The thread along the currently selected branches: root messages plus, under
     *  each, the children hanging off its active branch key. Off-branch subtrees stay
     *  in the session (they return when their branch is re-selected) but drop out of
     *  here - and therefore out of the request context and the visible timeline. */
    fun visibleMessages(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.all { it.threadOf == null }) return messages
        val children = HashMap<String, MutableList<ChatMessage>>()
        for (m in messages) {
            val t = m.threadOf ?: continue
            children.getOrPut(t) { mutableListOf() }.add(m)
        }
        val visibleIds = LinkedHashSet<String>()
        val root = messages.filter { it.threadOf == null }
        root.forEach { visibleIds.add(it.id) }
        var cur = root.lastOrNull()
        while (cur != null) {
            val kids = children[branchKey(cur)] ?: break
            kids.forEach { visibleIds.add(it.id) }
            cur = kids.lastOrNull()
        }
        // Render in original list order so process rows stay above their answer.
        return messages.filter { it.id in visibleIds }
    }

    /** Switch a regenerated answer to another version: the subtree under the old
     *  version leaves the view and the request context, and comes back on re-select. */
    fun switchBranch(messageId: String, newIndex: Int) {
        val session = _currentSession.value ?: return
        if (_generatingSessionIds.value.contains(session.id)) return
        updateSessionById(session.id) { s ->
            val msgs = s.messages.toMutableList()
            val i = msgs.indexOfFirst { it.id == messageId }
            if (i < 0) return@updateSessionById s
            val m = msgs[i]
            val branches = m.branches ?: return@updateSessionById s
            if (newIndex !in branches.indices || newIndex == m.branchIndex) return@updateSessionById s
            val saved = branches.toMutableList()
            saved[m.branchIndex] = m.content
            msgs[i] = m.copy(branches = saved, branchIndex = newIndex, content = saved[newIndex], isStreaming = false)
            s.copy(messages = msgs)
        }
        recalcTokens()
    }

    /** Regenerate: append a fresh version of the target answer (the last one when
     *  [messageId] is null) and stream into it. Earlier versions and everything
     *  hanging off them stay in the session and reappear when re-selected. */
    fun regenerate(messageId: String? = null) {
        val session = _currentSession.value ?: return
        if (_generatingSessionIds.value.contains(session.id)) return
        AppLog.d("regenerate: session=${session.id.take(8)} msg=${messageId?.take(8) ?: "last"}")
        val visible = visibleMessages(session.messages)
        val target = when {
            messageId != null -> visible.lastOrNull { it.id == messageId && it.role == "assistant" && !it.isStreaming }
            else -> visible.lastOrNull { it.role == "assistant" && !it.isStreaming && it.content.isNotBlank() }
        } ?: return
        val tIdx = visible.indexOfFirst { it.id == target.id }
        val userMsg = (tIdx - 1 downTo 0).firstOrNull { visible[it].role == "user" }?.let { visible[it] } ?: return
        val history = visible.take(visible.indexOfFirst { it.id == userMsg.id } + 1)
        val branches = (target.branches ?: listOf(target.content)).toMutableList()
        branches.add("")
        val newIndex = branches.size - 1
        markGenerating(session.id)
        _error.value = null
        updateSessionById(session.id) { s ->
            s.copy(messages = s.messages.map {
                if (it.id == target.id) it.copy(
                    branches = branches,
                    branchIndex = newIndex,
                    content = "",
                    reasoningContent = "",
                    annotations = null,
                    isStreaming = true
                ) else it
            })
        }
        val regenJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiMessages = mutableListOf<Message>(
                    Message(role = "system", content = getSystemPrompt())
                )
                val hist = history.filter {
                    it.role == "user" || (it.role == "assistant" && it.content.isNotBlank())
                }
                for (msg in hist) {
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
                    thinkingConfig = buildThinkingConfig(),
                    enableSearch = _enableSearch.value
                )
                streamResponse(activeApi, request, isFirstMessage = false, sessionId = session.id, userText = userMsg.content)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user pressed stop
            } catch (e: Exception) {
                _error.value = e.message ?: "Unknown error"
                updateSessionById(session.id) { s ->
                    s.copy(messages = s.messages.map {
                        if (it.id == target.id && it.isStreaming) it.copy(isStreaming = false) else it
                    })
                }
            } finally {
                markIdle(session.id)
                flushPendingRoute()
            }
        }
        streamingJobs[session.id] = regenJob
    }

    fun sendMessage(text: String, imageUris: List<Uri> = emptyList(), fileUri: Uri? = null, fileName: String? = null) {
        val session = _currentSession.value ?: return
        if (text.isBlank() && imageUris.isEmpty() && fileUri == null) return
        // Block only a duplicate send on the SAME conversation — other
        // conversations may generate in parallel (each run owns its session).
        if (_generatingSessionIds.value.contains(session.id)) return
        if (_isCompressing.value) {
            _error.value = LangManager.t("chat.compress.running", "正在压缩会话，请稍候再发送")
            return
        }

        // "/压缩" command — must be exact match (only the command, nothing before/after)
        if (text.trim() == "/压缩") {
            compressConversation()
            return
        }

        // "/skill <name>" — run a saved skill: its content becomes the instruction
        // prefix for this message (all modes).
        var skillPrefix = ""
        val skillMatch = Regex("^/skill\\s+(.+)$").find(text.trim())
        if (skillMatch != null) {
            val skillName = skillMatch.groupValues[1].trim()
            val skillContent = agentEngine?.readSkillContent(skillName)
            if (skillContent == null) {
                _error.value = LangManager.t("chat.skillNotFound", "未找到技能「{name}」。可用：{list}")
                    .replace("{name}", skillName)
                    .replace("{list}", agentEngine?.listSkillsDetailed()?.joinToString("、") { it.name } ?: LangManager.t("common.none", "（无）"))
                return
            }
            skillPrefix = "[skill: $skillName]\n$skillContent\n\n[task]\n"
            AppLog.d("skill invoked: $skillName (${skillContent.length} chars)")
        }

        // "协议" in the user's text — flag possible unauthorized jailbreak attempts
        // and raise the warning dialog immediately (the message still goes through).
        if (text.contains("协议")) {
            _protocolWarning.value = true
        }

        // Context limit check
        if (isContextLimitReached) {
            _error.value = LangManager.t("chat.contextLimit", "上下文已达 {n}K Token 上限。请开启新对话，或长按进度圆圈压缩会话。").replace("{n}", (MAX_CONTEXT_TOKENS / 1000).toString())
            return
        }

        val isFirstMessage = session.messages.isEmpty()
        if (isFirstMessage && !_sessions.value.contains(session)) {
            _sessions.value = _sessions.value + session
            saveSessions()
        }
        markGenerating(session.id)
        _error.value = null
        lastStreamCompleted = false
        val sendJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Persist picker URIs first — content:// dies after process kill.
                val persistedImages = imageUris.mapNotNull { persistImage(it) }
                // Agent-mode uploads → local copy policy (default: ask once)
                if (persistedImages.isNotEmpty() && _currentModel.value.contains("agent")) {
                    when (_prefAutoSaveImages.value) {
                        "always" -> copyUploadsToLocal(persistedImages)
                        "ask" -> _pendingImageSave.value = ImageSaveRequest(persistedImages)
                        // "never" — no copy
                    }
                }
                // Commit the user's turn BEFORE any file/image network work: a cancel
                // or failure below must never swallow the message itself.
                val userMessage = ChatMessage(
                    role = "user",
                    content = text.ifBlank {
                        fileName?.let { LangManager.t("chat.attachmentNamed", "[附件] {name}").replace("{name}", it) }
                            ?: LangManager.t("chat.attachment", "[附件]")
                    },
                    imageUri = persistedImages.joinToString("|").ifBlank { null },
                    fileName = fileName,
                    threadOf = visibleMessages(session.messages).lastOrNull()?.let { "${it.id}#${it.branchIndex}" }
                )
                val assistantMessage = ChatMessage(role = "assistant", content = "", isStreaming = true, flatProcess = true,
                    threadOf = "${userMessage.id}#${userMessage.branchIndex}")
                val userMsgId = userMessage.id
                updateSessionById(session.id) { s ->
                    s.copy(messages = s.messages + userMessage + assistantMessage)
                }

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
                    islandService.showAgentTaskNotification(
                        taskTitle = LangManager.t("island.running.title", "运行中"),
                        taskStatus = LangManager.t("island.extractingImage", "正在提取图片内容..."),
                        progress = -1,
                        phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_RUN
                    )
                    val extracted = extractImageContent(imageBase64List)
                    if (extracted != null) {
                        extractedNote = "\n\n[图片内容 - 已自动提取为文档]\n$extracted"
                        imageBase64List = emptyList() // text-only from here on
                    }
                    // extraction failed → keep images and send as-is (API may still accept or surface a clear error)
                }

                val fullUserContent = buildString {
                    if (skillPrefix.isNotBlank()) append(skillPrefix)
                    if (text.isNotBlank()) append(text)
                    if (fileContent.isNotBlank()) {
                        if (isNotBlank()) append("\n\n")
                        append("File: ${fileName ?: "file"}\n```\n$fileContent\n```")
                    }
                    if (extractedNote.isNotBlank()) append(extractedNote)
                }

                // Enrich the committed user message with the file/extracted content.
                if (fullUserContent != userMessage.content) {
                    updateSessionById(session.id) { s ->
                        s.copy(messages = s.messages.map {
                            if (it.id == userMsgId) it.copy(content = fullUserContent) else it
                        })
                    }
                }

                val isAgent = _currentModel.value.contains("agent") && agentEngine != null
                AppLog.d("sendMessage dispatch: agent=$isAgent search=${_enableSearch.value} model=${_currentModel.value} textLen=${text.length} images=${imageUris.size} file=$fileName")

                if (isAgent) {
                    // Both PLAN and BUILD run the full tool loop — the phase only
                    // changes the prompt persona (switching goes through switch_phase).
                    executeAgentFlow(text, fullUserContent, imageBase64List, audioBase64, audioMimeType, videoBase64, videoMimeType, isFirstMessage, session)
                } else if (_enableSearch.value) {
                    // Normal chat + search ON → agent-style tool loop, but restricted to
                    // web_search (cn.bing.com scrape) + fetch_url. No permissions, no
                    // official MiMo web_search plugin.
                    executeAgentFlow(text, fullUserContent, imageBase64List, audioBase64, audioMimeType, videoBase64, videoMimeType, isFirstMessage, session, searchOnly = true)
                } else {
                    executeNormalFlow(text, fullUserContent, imageBase64List, audioBase64, audioMimeType, videoBase64, videoMimeType, isFirstMessage, session)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user pressed stop — not an error
            } catch (e: Exception) {
                AppLog.e("sendMessage run failed", e)
                _error.value = e.message ?: "Unknown error"
                updateSessionById(session.id) { s ->
                    s.copy(messages = s.messages
                        .filterNot { it.role == "assistant" && it.content.isBlank() && it.isStreaming }
                        .map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                    )
                }
            } finally {
                markIdle(session.id)
                // Always close the agent session: early returns inside executeAgentFlow
                // (final structured answer, parse fallbacks) and thrown/cancelled runs
                // skip its endSession call, which left the snowflake indicator stuck.
                agentTaskManager.endSession()
                // On normal completion the capsule must stay in its「输出完成」state
                // (auto-retracts itself); only cancel on error/abort.
                if (!lastStreamCompleted) {
                    islandService.cancelNotification()
                }
                lastStreamCompleted = false
                flushPendingRoute()
            }
        }
        streamingJobs[session.id] = sendJob
        streamingJob = sendJob
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

        val history = visibleMessages(session.messages)
        val thinkingOn = buildThinkingConfig().type == "enabled"
        for (msg in history) {
            if (msg.role == "user") {
                // Re-attach persisted images so the model can still "see" them in follow-up messages
                val histImageBase64 = msg.imageUri?.split("|")?.filter { it.isNotBlank() }?.firstOrNull()?.let { path ->
                    try {
                        val file = java.io.File(path)
                        if (file.exists()) {
                            android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                        } else null
                    } catch (_: Exception) { null }
                }
                apiMessages.add(getActiveApiService().buildUserMessage(
                    text = msg.content,
                    imageBase64 = histImageBase64
                ))
            } else if (msg.role == "assistant" && msg.content.isNotBlank()) {
                apiMessages.add(Message(
                    role = "assistant",
                    content = msg.content,
                    reasoningContent = if (thinkingOn) msg.reasoningContent.takeIf { it.isNotBlank() } else null
                ))
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
            thinkingConfig = buildThinkingConfig(),
            enableSearch = _enableSearch.value
        )

        try {
            islandService.showAgentTaskNotification(
                taskTitle = LangManager.t("island.waiting.title", "等待响应"),
                taskStatus = LangManager.t("island.waiting.status", "等待模型响应..."),
                progress = -1,
                phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_WAIT
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
        isFirstMessage: Boolean, session: ChatSession,
        searchOnly: Boolean = false
    ) {
        // searchOnly = normal chat with the search toggle: two network tools, no engine,
        // no permission gates. Regular agent mode still requires the engine.
        val engine = agentEngine
        if (engine == null && !searchOnly) {
            AppLog.w("agentFlow exit: no engine and not searchOnly")
            return
        }
        val activeApi = getActiveApiService()
        AppLog.d("agentFlow enter: searchOnly=$searchOnly model=${_currentModel.value} engine=${engine != null} url=${activeApi.getBaseUrl()} key=${AppLog.keyShape(activeApi.getApiKey())}")
        // Search runs render their own role="step" timeline rows — flag the answer
        // so MessageBubble hides the built-in process card (no double display).
        var thinkStepId: String? = null
        var searchStepId: String? = null
        var fetchStepId: String? = null
        var searchCount = 0
        var fetchCount = 0
        if (searchOnly) {
            updateSessionById(session.id) { s ->
                s.copy(messages = s.messages.map {
                    if (it.role == "assistant" && it.isStreaming) it.copy(flatProcess = true) else it
                })
            }
        }
        val maxRounds = 5
        agentTaskManager.startSession(session.id)
        // Tool-card ids must be unique across runs: leftover "stream-tc-1-0" cards
        // from the previous run collide with the next run's round-1 cards and the
        // LazyColumn crashes on duplicate keys.
        val runTag = java.util.UUID.randomUUID().toString().replace("-", "").take(8)

        // Caller appended [user, streaming placeholder]; everything from here on is this run's output.
        // Use the LIVE session (by id) — the `session` param is a stale snapshot from before
        // the user message + placeholder were appended, so its size would be off by 2.
        val liveSession = _sessions.value.find { it.id == session.id } ?: session
        val runStartIdx = (liveSession.messages.size).coerceAtLeast(1) - 1
        val toolSteps = mutableListOf<Map<String, String>>()
        // File mutations this run (write/edit/delete) → end-of-run summary card + undo.
        val runFileChanges = mutableListOf<com.zhibai.app.data.model.FileChange>()
        // Artifact trail this run: 中间产物 (made) / 参考 (read or searched).
        val runProduced = mutableListOf<com.zhibai.app.data.model.ArtifactEntry>()
        val runReferenced = mutableListOf<com.zhibai.app.data.model.ArtifactEntry>()
        val runSearches = mutableListOf<com.zhibai.app.data.model.ArtifactEntry>()
        fun recordArtifact(tool: String, args: Map<String, Any>) {
            fun arg(k: String) = args[k]?.toString()?.trim().orEmpty()
            val p = arg("path").ifBlank { arg("source").ifBlank { arg("url").ifBlank {
                arg("query").ifBlank { arg("pattern") }
            } } }
            val name = p.substringAfterLast('/').ifBlank { p }.take(60)
            when (tool) {
                "write_file", "edit_file", "copy_file", "move_file", "download_file",
                "extract_archive", "save_skill" ->
                    if (p.isNotBlank()) runProduced.add(
                        com.zhibai.app.data.model.ArtifactEntry(name = name, path = p, kind = "produced")
                    )
                "read_file", "view_media", "get_file_info", "grep", "open_file",
                "search_files", "list_files" ->
                    if (p.isNotBlank()) runReferenced.add(
                        com.zhibai.app.data.model.ArtifactEntry(name = name, path = p, kind = "ref")
                    )
                "web_search", "fetch_url", "open_url" ->
                    if (p.isNotBlank()) runSearches.add(
                        com.zhibai.app.data.model.ArtifactEntry(name = name, path = p, kind = "url")
                    )
            }
        }
        // Search results in call order → attached as message annotations so the
        // [reference:N] chips and the 搜索 card work exactly like official search did.
        val searchAnnotations = mutableListOf<com.zhibai.app.data.model.Annotation>()

        val apiMessages = mutableListOf<Message>(
            Message(role = "system", content = getSystemPrompt())
        )

        val history = liveSession.messages.filter { !it.isStreaming }
        for (msg in history) {
            when (msg.role) {
                "user" -> apiMessages.add(Message(role = "user", content = msg.content))
                "assistant" -> if (msg.content.isNotBlank()) apiMessages.add(Message(role = "assistant", content = msg.content))
                // Flattened like the in-loop results — role=tool breaks the MiMo API.
                "tool" -> apiMessages.add(Message(role = "user", content = msg.content))
                "tool_group" -> {
                    // Expand collapsed operations back into the conversation for model context
                    try {
                        val arr = com.google.gson.JsonParser.parseString(msg.content).asJsonArray
                        arr.forEach { el ->
                            val o = el.asJsonObject
                            val t = o.get("tool")?.asString ?: "tool"
                            val r = o.get("result")?.asString ?: ""
                            apiMessages.add(Message(role = "user", content = "[tool result: $t]\n$r"))
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

        // Tool-name detection on the streaming JSON buffer: `"tool":"name"` — escaped
        // quotes inside string values can never match this pattern.
        val toolNameRegex = Regex("\"tool\"\\s*:\\s*\"([^\"]+)\"")

        /** Round N's live assistant bubble: reuse an existing streaming one, else append. */
        fun ensureStreamPlaceholder(): String {
            val s = _sessions.value.find { it.id == session.id } ?: return ""
            val existing = s.messages.indexOfLast { it.role == "assistant" && it.isStreaming }
            if (existing >= 0) return s.messages[existing].id
            val id = java.util.UUID.randomUUID().toString()
            updateSessionById(session.id) { ss ->
                ss.copy(messages = ss.messages + ChatMessage(
                    id = id, role = "assistant", content = "", isStreaming = true, flatProcess = true
                ))
            }
            return id
        }

        var round = 0
        // Broken-JSON recovery: resend nudges allowed for this run (bounded so a
        // persistently malformed model can't loop forever inside maxRounds).
        var malformedRecovery = 0
        while (round < maxRounds) {
            round++
            val cardTag = "stream-tc-$runTag-$round-"
            AppLog.d("agentFlow round $round: msgs=${apiMessages.size} model=${getApiModel()}")
            // Streaming structured round: MiMo accepts stream + response_format
            // (json_schema) — JSON arrives token by token. Thinking streams natively
            // via reasoning_content; the `response` field is extracted incrementally;
            // a tool card is inserted the moment its name closes in the stream
            // (Rikkahub ToolCallStart-on-first-appearance). Execution stays serial
            // after the stream ends.
            val request = ChatRequest(
                model = getApiModel(),
                messages = apiMessages,
                maxCompletionTokens = 32768,
                temperature = 0.7f,
                stream = true,
                thinking = buildThinkingConfig(),
                responseFormat = com.zhibai.app.agent.ToolCallSchema.toResponseFormat()
            )

            val placeholderId = ensureStreamPlaceholder()

            islandService.showAgentTaskNotification(
                taskTitle = LangManager.t("island.waiting.title", "等待响应"),
                taskStatus = LangManager.t("island.waiting.round", "第 {n} 轮：等待模型响应...").replace("{n}", round.toString()),
                progress = -1,
                phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_WAIT
            )

            val streamBuf = StringBuilder()
            var thinkOut = ""
            var respOut = ""
            var toolCardCount = 0
            var narrationNotified = false
            var sawLengthTruncation = false
            var thinkNotified = false

            // Collect one streamed structured round. Returns the failure (balance errors
            // are retried on the fallback key by the caller) or null on success.
            suspend fun runStreamRound(api: MiMoApiService): Exception? {
                streamBuf.setLength(0)
                thinkOut = ""
                respOut = ""
                toolCardCount = 0
                narrationNotified = false
                sawLengthTruncation = false
                thinkNotified = false
                var failure: Exception? = null
                try {
                    api.chatCompletion(request).collect { chunk ->
                        if (chunk !is StreamChunk.Data) return@collect
                        if (chunk.finishReason == "length") sawLengthTruncation = true
                        if (chunk.reasoning.isNotBlank()) {
                            thinkOut += chunk.reasoning
                            if (!thinkNotified) {
                                // Bug fix: the island used to sit on "等待响应" for the
                                // whole thinking phase — flip to 思考中 on first token.
                                thinkNotified = true
                                islandService.showAgentTaskNotification(
                                    taskTitle = LangManager.t("island.thinking.title", "思考中"),
                                    taskStatus = LangManager.t("island.thinking.status", "正在深度思考..."),
                                    progress = -1,
                                    phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_THINK
                                )
                            }
                        }
                        if (chunk.content.isNotEmpty()) {
                            streamBuf.append(chunk.content)
                            extractPartialField(streamBuf.toString(), "response")?.let { respOut = it }
                            // Tool name fully present in the stream → pending card NOW.
                            // Search runs show the flat step rows instead — no cards.
                            if (!searchOnly) {
                                val names = toolNameRegex.findAll(streamBuf).map { it.groupValues[1] }.toList()
                                while (names.size > toolCardCount) {
                                    val cardId = "${cardTag}$toolCardCount"
                                    val name = names[toolCardCount]
                                    insertStepBeforeAnswer(session.id, ChatMessage(
                                        id = cardId, role = "tool", content = "",
                                        toolTitle = com.zhibai.app.agent.toolActionTitle(name, emptyMap()),
                                        toolIcon = com.zhibai.app.agent.toolIconKey(name),
                                        toolStatus = "running"
                                    ))
                                    toolCardCount++
                                }
                            }
                        }
                        updateSessionById(session.id) { s ->
                            val msgs = s.messages.toMutableList()
                            val i = msgs.indexOfFirst { it.id == placeholderId }
                            if (i >= 0) {
                                // Never write an empty field over shown content: each
                                // round resets respOut/thinkOut at entry, and the first
                                // chunk of a retry would blank a finished answer
                                // (「先消失再重新答」).
                                msgs[i] = msgs[i].copy(
                                    content = if (respOut.isNotBlank()) sanitizeHighRisk(respOut) else msgs[i].content,
                                    reasoningContent = if (thinkOut.isNotBlank()) thinkOut else msgs[i].reasoningContent
                                )
                            }
                            // Live-fill the thinking step row so the reasoning is
                            // readable while the answer streams (new unified style).
                            // Created lazily on the first reasoning token inside this
                            // same transform — never deleted afterwards (key churn
                            // reshuffled the list).
                            var tid = thinkStepId
                            if (tid == null && thinkOut.isNotBlank()) {
                                tid = java.util.UUID.randomUUID().toString()
                                thinkStepId = tid
                                val phIdx = msgs.indexOfLast { it.isStreaming && it.role == "assistant" }
                                val row = ChatMessage(
                                    id = tid, role = "step", content = LangManager.t("step.thinking", "正在思考"),
                                    isStreaming = true, reasoningContent = thinkOut
                                )
                                if (phIdx >= 0) msgs.add(phIdx, row) else msgs.add(row)
                            }
                            if (tid != null && thinkOut.isNotBlank()) {
                                val j = msgs.indexOfFirst { it.id == tid }
                                if (j >= 0) {
                                    msgs[j] = msgs[j].copy(reasoningContent = thinkOut)
                                }
                            }
                            s.copy(messages = msgs)
                        }
                        if (respOut.isNotBlank() && !narrationNotified) {
                            narrationNotified = true
                            islandService.showAgentTaskNotification(
                                taskTitle = LangManager.t("island.outputting.title", "输出中"),
                                taskStatus = respOut.take(80),
                                progress = -1,
                                phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_OUTPUT
                            )
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // User pressed stop — drop this round's pending cards; stopGeneration
                    // already finalized the placeholder.
                    updateSessionById(session.id) { s ->
                        s.copy(messages = s.messages
                            .filterNot { it.id.startsWith(cardTag) }
                            .map {
                                if (it.id == thinkStepId) it.copy(content = LangManager.t("step.thought", "已深度思考"), isStreaming = false)
                                else it
                            })
                    }
                    throw e
                } catch (e: Exception) {
                    failure = e
                }
                return failure
            }

            // Unified thinking timeline (all modes): the step row is created LAZILY
            // on the first reasoning token (in the chunk writeback below) — creating
            // it upfront and deleting it when reasoning never came churned the
            // group keys and reshuffled the card list mid-run.
            thinkStepId = null
            var streamFailure = runStreamRound(activeApi)
            // A round that already produced content is a success — a tail error must
            // not trigger a re-stream over the finished answer. Only a truly empty
            // round may fall back to the agent key, and only when that key resolves.
            if (streamFailure != null && streamBuf.isBlank() && isBalanceError(streamFailure) &&
                _agentApiKey.value.isNotBlank()
            ) {
                AppLog.w("agentFlow round $round: empty round, balance fallback to agent key")
                streamFailure = runStreamRound(agentChatService)
            }
            if (streamFailure != null && streamBuf.isNotBlank()) {
                AppLog.w("agentFlow round $round: tail error ignored, ${streamBuf.length}B already arrived")
                streamFailure = null
            }
            AppLog.d("agentFlow round $round streamed: chars=${streamBuf.length} think=${thinkOut.length} fail=${streamFailure?.message?.take(120)}")
            // Finalize the thinking step row (all modes)
            run {
                val tid = thinkStepId
                thinkStepId = null
                if (tid != null) {
                    if (thinkOut.isNotBlank()) {
                        updateSessionById(session.id) { s ->
                            s.copy(messages = s.messages.map {
                                if (it.id == tid) it.copy(
                                    content = LangManager.t("step.thought", "已深度思考"), reasoningContent = thinkOut, isStreaming = false
                                ) else it
                            })
                        }
                    } else {
                        updateSessionById(session.id) { s ->
                            s.copy(messages = s.messages.filterNot { it.id == tid })
                        }
                    }
                }
            }
            if (streamFailure != null) {
                _error.value = "Agent request failed: ${streamFailure.message}"
                // Surface the failure in the bubble too — a bare banner (or a blank
                // bubble) reads as「对话就结束」with no clue why.
                val errText = LangManager.t("agent.toolFailed", "请求失败：{error}")
                    .replace("{error}", streamFailure.message ?: LangManager.t("common.unknownError", "未知错误"))
                updateSessionById(session.id) { s ->
                    val msgs = s.messages.toMutableList()
                    // remove this round's pending tool cards
                    msgs.removeAll { it.id.startsWith(cardTag) }
                    // finalize (or drop) the placeholder so nothing stays stuck streaming
                    val i = msgs.indexOfFirst { it.id == placeholderId }
                    if (i >= 0) {
                        val m = msgs[i]
                        if (m.content.isBlank() && m.reasoningContent.isBlank()) {
                            msgs[i] = m.copy(content = errText, isStreaming = false)
                        } else {
                            msgs[i] = m.copy(isStreaming = false)
                        }
                    }
                    s.copy(messages = msgs)
                }
                islandService.cancelNotification()
                return
            }

            val rawText = streamBuf.toString()
            if (rawText.isBlank()) break // stream delivered nothing → fall to final answer
            // json_schema output is pure JSON → try direct parse first (immune to any
            // brace-in-string confusion); fall back to the string-aware extractor for
            // wrapped output. A third tier repairs JSON the stream cut at a structural
            // boundary (finish_reason=length / dropped connection mid-reply).
            var structured: com.zhibai.app.agent.StructuredToolCallResponse? = try {
                com.zhibai.app.agent.ToolCallSchema.parse(rawText)
            } catch (e: Exception) {
                try {
                    com.zhibai.app.agent.ToolCallSchema.parse(extractJson(rawText))
                } catch (e2: Exception) { null }
            }
            if (structured == null) {
                val fixed = repairTruncatedJson(rawText)
                if (fixed != null) {
                    structured = try {
                        com.zhibai.app.agent.ToolCallSchema.parse(fixed)
                    } catch (e: Exception) { null }
                }
            }
            AppLog.d("agentFlow round $round parse: structured=${structured != null} tools=${structured?.toolCalls?.size ?: 0} names=${structured?.toolCalls?.map { it.tool }} respLen=${structured?.response?.length ?: 0} thinkJson=${structured?.thinking?.length ?: 0}")
            if (structured == null) {
                // Broken schema JSON vs plain prose the model wrote instead of the schema.
                val looksJson = rawText.trimStart().startsWith("{") || rawText.contains("\"tool_calls\"")
                AppLog.w("agentFlow round $round unparseable output: looksJson=$looksJson recovery=$malformedRecovery head=${rawText.take(120)}")
                if (looksJson && malformedRecovery < 2) {
                    // Never dump raw JSON on the user and never end the run over a bad
                    // parse: show the model its own broken reply and ask for a resend.
                    malformedRecovery++
                    updateSessionById(session.id) { s ->
                        s.copy(messages = s.messages.filterNot { it.id.startsWith(cardTag) })
                    }
                    apiMessages.add(Message(role = "assistant", content = rawText))
                    apiMessages.add(Message(
                        role = "user",
                        content = if (sawLengthTruncation)
                            "Your previous reply was cut off by the output token limit, so its JSON is incomplete. Resend the complete JSON object now (thinking, tool_calls, response). Keep thinking SHORT so the whole reply fits."
                        else
                            "Your previous reply was not valid JSON (incomplete or malformed). Resend the complete JSON object: thinking, tool_calls, response."
                    ))
                    continue
                }
                if (looksJson) {
                    // Recovery exhausted: park the raw JSON in reasoningContent (collapsible,
                    // still diagnosable on-device) and show a short human message instead.
                    val salvaged = extractPartialField(rawText, "response")?.takeIf { it.isNotBlank() }
                        ?: if (sawLengthTruncation) LangManager.t("chat.truncated", "回复被输出长度上限截断，本轮中止。")
                        else LangManager.t("chat.badJson", "模型输出的 JSON 无法解析，本轮中止。")
                    updateSessionById(session.id) { s ->
                        val msgs = s.messages
                            .filterNot { it.id.startsWith(cardTag) }
                            .toMutableList()
                        val i = msgs.indexOfFirst { it.id == placeholderId }
                        if (i >= 0) {
                            msgs[i] = msgs[i].copy(
                                content = sanitizeHighRisk(salvaged),
                                reasoningContent = rawText,
                                isStreaming = false
                            )
                        }
                        s.copy(messages = msgs)
                    }
                    lastStreamCompleted = true
                    islandService.showAgentTaskComplete(
                        taskTitle = LangManager.t("island.outputError.title", "输出异常"),
                        result = salvaged.takeLast(80)
                    )
                    return
                }
                // Model ignored the schema and wrote prose — the prose IS the answer.
                val shown = sanitizeHighRisk(rawText)
                updateSessionById(session.id) { s ->
                    val msgs = s.messages
                        .filterNot { it.id.startsWith(cardTag) }
                        .toMutableList()
                    val i = msgs.indexOfFirst { it.id == placeholderId }
                    if (i >= 0) {
                        msgs[i] = msgs[i].copy(content = shown, isStreaming = false)
                    }
                    s.copy(messages = msgs)
                }
                lastStreamCompleted = true
                islandService.showAgentTaskComplete(
                    taskTitle = LangManager.t("island.done.title", "输出完成"),
                    result = shown.takeLast(80).ifBlank { LangManager.t("island.done.short", "回复已完成") }
                )
                return
            }

            val toolCalls = structured.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                val responseText = structured.response?.trim().orEmpty()
                val thinkingText = structured.thinking?.trim().orEmpty()
                // Nudge only when the model gave us NOTHING. Small models often put the
                // whole answer in thinking and leave response empty — that IS an answer
                // (finalText below falls back to it); looping "Continue." until maxRounds
                // just shows an empty bubble for half a minute.
                if (responseText.isEmpty() && thinkingText.isEmpty() && thinkOut.isBlank() && round < maxRounds) {
                    AppLog.d("agentFlow round $round: empty reply, nudging")
                    apiMessages.add(Message(role = "assistant", content = rawText))
                    apiMessages.add(Message(
                        role = "user",
                        content = "Continue. Provide your final response to the user now. Do not stop at thinking only."
                    ))
                    continue
                }
                val finalText = responseText.ifBlank {
                    thinkingText.ifBlank {
                        thinkOut.trim().takeIf { it.isNotBlank() } ?: LangManager.t("chat.noResponse", "模型未输出 response 字段。")
                    }
                }
                val anns = searchAnnotations.toList().ifEmpty { null }
                val finalContent = withReferenceFallback(sanitizeHighRisk(finalText), anns)
                updateSessionById(session.id) { s ->
                    // No tools ran → finalize the placeholder IN PLACE (same id).
                    // Collapsing into a new message here made the LazyColumn key
                    // change → the finished answer flashed and re-rendered.
                    val msgs = s.messages.filterNot {
                        it.id.startsWith(cardTag) && (it.toolStatus == "running" || it.content.contains("调用中"))
                    }.toMutableList()
                    val i = msgs.indexOfFirst { it.id == placeholderId }
                    if (i >= 0) {
                        msgs[i] = msgs[i].copy(
                            content = finalContent,
                            annotations = anns,
                            isStreaming = false
                        )
                        s.copy(messages = msgs)
                    } else {
                        s.copy(messages = msgs + ChatMessage(
                            role = "assistant",
                            content = finalContent,
                            annotations = anns,
                            isStreaming = false
                        ))
                    }
                }
                if (isFirstMessage && finalText.isNotBlank()) {
                    generateTitle(session.id, text, finalText)
                }
                lastStreamCompleted = true
                islandService.showAgentTaskComplete(
                    taskTitle = LangManager.t("island.taskDone.title", "任务完成"),
                    result = finalText.takeLast(80)
                )
                return
            }

            apiMessages.add(Message(role = "assistant", content = rawText))

            // Narration/thinking already streamed into the placeholder — finalize it so
            // collapseRunMessages can collect it as this round's 旁白/思考 context.
            // (A blank placeholder — model said nothing outside tool_calls — is dropped.)
            updateSessionById(session.id) { s ->
                val msgs = s.messages.toMutableList()
                val i = msgs.indexOfFirst { it.id == placeholderId }
                if (i >= 0) {
                    val m = msgs[i]
                    if (m.content.isBlank() && m.reasoningContent.isBlank()) msgs.removeAt(i)
                    else msgs[i] = m.copy(isStreaming = false)
                }
                s.copy(messages = msgs)
            }

            // Tool results are flattened into ONE user turn: MiMo's OpenAI-compatible
            // API rejects role=tool messages that aren't preceded by structured
            // assistant.tool_calls (our assistant turn is a plain JSON string), so
            // every tool round 400'd and killed the conversation.
            val roundToolResults = mutableListOf<String>()
            // ---- spawn_subagent batch -----------------------------------------
            // Every spawn_subagent call in this round gets its own card + live sink,
            // and several run CONCURRENTLY (async) — the model can fan out independent
            // subtasks in one tool_calls array. Results are keyed by the original
            // index so the sequential loop below keeps them in call order.
            // MODE_ASK_ALL serializes instead: executeTool shares one
            // pendingConfirmation slot, so parallel approvals would strand the
            // first continuation.
            val spawnResults = java.util.concurrent.ConcurrentHashMap<Int, String>()
            val spawnCalls = toolCalls.withIndex().filter {
                it.value.tool == "spawn_subagent" && !searchOnly
            }
            if (spawnCalls.isNotEmpty()) {
                // Phase 1 — create every spawn card SEQUENTIALLY in call order so the
                // timeline matches the model's tool_calls array (concurrent insertion
                // permuted the cards).
                data class SpawnSlot(val idx: Int, val tc: com.zhibai.app.agent.StructuredToolCall, val msgId: String, val args: Map<String, Any>, val name: String)
                val slots = spawnCalls.map { (idx, tc) ->
                    val baseArgs = tc.args ?: emptyMap<String, Any>()
                    val subName = (baseArgs["name"] as? String)?.trim()
                        ?.takeIf { it.isNotBlank() } ?: "S${idx + 1}"
                    val tcArgs = baseArgs + ("name" to subName)
                    recordArtifact(tc.tool, tcArgs)
                    val streamCardId = "${cardTag}$idx"
                    val hasStreamCard = _sessions.value.find { it.id == session.id }
                        ?.messages?.any { it.id == streamCardId } == true
                    val runningMsgId = if (hasStreamCard) streamCardId
                    else java.util.UUID.randomUUID().toString()
                    val runTitle = com.zhibai.app.agent.toolActionTitle(tc.tool, tcArgs)
                    val runIcon = com.zhibai.app.agent.toolIconKey(tc.tool)
                    insertStepBeforeAnswer(session.id, ChatMessage(
                        id = runningMsgId, role = "tool", content = "",
                        toolTitle = runTitle, toolIcon = runIcon, toolStatus = "running", toolLive = ""
                    ))
                    updateSessionById(session.id) { s ->
                        val msgs = s.messages.toMutableList()
                        val i = msgs.indexOfFirst { it.id == runningMsgId }
                        if (i >= 0) msgs[i] = msgs[i].copy(
                            content = "", toolTitle = runTitle, toolIcon = runIcon, toolStatus = "running", toolLive = ""
                        )
                        s.copy(messages = msgs)
                    }
                    SpawnSlot(idx, tc, runningMsgId, tcArgs, subName)
                }
                // Phase 2 — run them concurrently (each updates ONLY its own card).
                suspend fun runSpawnCall(slot: SpawnSlot) {
                    val toolStartMs = System.currentTimeMillis()
                    val liveBuf = StringBuilder()
                    var lastLiveFlush = 0L
                    val result = try {
                        engine!!.executeTool(slot.tc.tool, slot.args, _agentPermissionMode.value) { line ->
                            synchronized(liveBuf) { liveBuf.appendLine(line) }
                            val now = System.currentTimeMillis()
                            if (now - lastLiveFlush > 300) {
                                lastLiveFlush = now
                                val snapshot = synchronized(liveBuf) { liveBuf.toString() }.takeLast(2000)
                                updateSessionById(session.id) { s ->
                                    val msgs = s.messages.toMutableList()
                                    val i = msgs.indexOfFirst { it.id == slot.msgId }
                                    if (i >= 0) msgs[i] = msgs[i].copy(toolLive = snapshot)
                                    s.copy(messages = msgs)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                    AppLog.d("tool <- spawn_subagent[${slot.name}] ${System.currentTimeMillis() - toolStartMs}ms: ${result.take(120).replace('\n', ' ')}")
                    val finalStatus = if (result.startsWith("Error") || result.startsWith("DENIED")) "error" else "ok"
                    updateSessionById(session.id) { s ->
                        val msgs = s.messages.toMutableList()
                        val i = msgs.indexOfFirst { it.id == slot.msgId }
                        if (i >= 0) msgs[i] = msgs[i].copy(
                            content = "[${slot.tc.tool}] ${result.take(2000)}",
                            toolStatus = finalStatus,
                            toolLive = synchronized(liveBuf) { liveBuf.toString() }.takeLast(2000)
                        )
                        s.copy(messages = msgs)
                    }
                    spawnResults[slot.idx] = result
                }
                _toolExecuting.value = true
                try {
                    if (slots.size > 1 &&
                        _agentPermissionMode.value != com.zhibai.app.agent.AgentEngine.MODE_ASK_ALL
                    ) {
                        islandService.showAgentTaskNotification(
                            taskTitle = LangManager.t("island.running.title", "运行中"),
                            taskStatus = LangManager.t("island.subagents", "并发执行 {n} 个子代理").replace("{n}", slots.size.toString()),
                            progress = -1,
                            phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_RUN
                        )
                        coroutineScope {
                            slots.map { slot ->
                                async { runSpawnCall(slot) }
                            }.awaitAll()
                        }
                    } else {
                        // Single spawn, or MODE_ASK_ALL (shared confirmation slot): serial.
                        slots.forEach { slot -> runSpawnCall(slot) }
                    }
                } finally {
                    _toolExecuting.value = false
                }
            }
            toolCalls.forEachIndexed { idx, tc ->
                // gson fills data-class fields via Unsafe: a JSON object that omits
                // "args" leaves this null even though Kotlin defaults it to emptyMap().
                // Accessing it unguarded used to NPE the whole run right after the
                // tool card appeared.
                val tcArgs = tc.args ?: emptyMap<String, Any>()
                // spawn_subagent already ran in the concurrent batch above (card +
                // live output handled there) — only fold its result into the round.
                if (spawnResults.containsKey(idx)) {
                    val displayResult = spawnResults[idx] ?: ""
                    toolSteps.add(mapOf("tool" to tc.tool, "result" to displayResult))
                    roundToolResults.add("[tool result: ${tc.tool}]\n$displayResult")
                    return@forEachIndexed
                }
                AppLog.d("tool -> ${tc.tool} args=${tcArgs.keys} searchOnly=$searchOnly")
                recordArtifact(tc.tool, tcArgs)
                val toolStartMs = System.currentTimeMillis()
                // Reuse the live "调用中" card inserted when this tool's name appeared in
                // the stream; insert a fresh card only if detection missed it. Declared
                // outside the try — the result back-fill below needs the id. Search runs
                // skip cards entirely (flat step rows instead).
                val streamCardId = "${cardTag}$idx"
                val hasStreamCard = _sessions.value.find { it.id == session.id }
                    ?.messages?.any { it.id == streamCardId } == true
                val runningMsgId = if (hasStreamCard) streamCardId
                    else java.util.UUID.randomUUID().toString()
                if (!searchOnly) {
                    val runTitle = com.zhibai.app.agent.toolActionTitle(tc.tool, tcArgs)
                    val runIcon = com.zhibai.app.agent.toolIconKey(tc.tool)
                    insertStepBeforeAnswer(session.id, ChatMessage(
                        id = runningMsgId, role = "tool", content = "",
                        toolTitle = runTitle, toolIcon = runIcon, toolStatus = "running", toolLive = ""
                    ))
                    updateSessionById(session.id) { s ->
                        val msgs = s.messages.toMutableList()
                        val i = msgs.indexOfFirst { it.id == runningMsgId }
                        if (i >= 0) msgs[i] = msgs[i].copy(
                            content = "", toolTitle = runTitle, toolIcon = runIcon, toolStatus = "running", toolLive = ""
                        )
                        s.copy(messages = msgs)
                    }
                }
                // Live output accumulators — declared outside the try so the final
                // back-fill below can flush the tail after the tool finishes.
                val liveBuf = StringBuilder()
                var lastLiveFlush = 0L
                var toolResult = try {
                    _toolExecuting.value = true
                    // Real-time tool output → live card text (throttled: flush at most
                    // every 300ms — updateSessionById saves sessions, no per-line writes).
                    engine?.liveOutputSink = { line ->
                        synchronized(liveBuf) { liveBuf.appendLine(line) }
                        val now = System.currentTimeMillis()
                        if (now - lastLiveFlush > 300) {
                            lastLiveFlush = now
                            val snapshot = synchronized(liveBuf) { liveBuf.toString() }.takeLast(2000)
                            updateSessionById(session.id) { s ->
                                val msgs = s.messages.toMutableList()
                                val i = msgs.indexOfFirst { it.id == runningMsgId }
                                if (i >= 0) msgs[i] = msgs[i].copy(toolLive = snapshot)
                                s.copy(messages = msgs)
                            }
                        }
                    }
                    engine?.fileChangeSink = { fc -> runFileChanges.add(fc) }
                    islandService.showAgentTaskNotification(
                        taskTitle = LangManager.t("island.running.title", "运行中"),
                        taskStatus = LangManager.t("island.execTool", "执行 {tool}").replace("{tool}", tc.tool),
                        progress = ((idx) * 100 / toolCalls.size),
                        phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_RUN
                    )
                    updateSessionById(session.id) { s ->
                        val msgs = s.messages.toMutableList()
                        val i = msgs.indexOfFirst { it.id == runningMsgId }
                        if (i >= 0) msgs[i] = msgs[i].copy(
                            content = "", toolStatus = "running", toolLive = ""
                        )
                        s.copy(messages = msgs)
                    }
                    // Built-in fun tool — handled here so normal chat and Agent both
                    // can snow without going through the engine or a network call.
                    if (tc.tool == "let_it_snow") {
                        snowNow()
                        LangManager.t("snow.toolResult", "雪已经下了起来。")
                    } else if (tc.tool == "mental_health_support") {
                        // Built-in support tool — every mode can raise the hotline card
                        // (replaces the old scan of each AI reply).
                        showMentalHealthSupport()
                        "已为用户展示心理健康支持卡片，包含心理援助热线。"
                    } else if (searchOnly) {
                        // Network-only tools, executed directly — no engine, no approval.
                        when (tc.tool) {
                            "web_search" -> {
                                val q = (tcArgs["query"] as? String)?.trim().orEmpty()
                                if (q.isEmpty()) "Error: no query"
                                else try {
                                    val results = BingSearch.search(q)
                                    if (results.isEmpty()) "No results for '$q'"
                                    else {
                                        val startNo = searchAnnotations.size + 1
                                        searchAnnotations.addAll(results.map {
                                            com.zhibai.app.data.model.Annotation(
                                                type = "web", url = it.url, title = it.title,
                                                summary = it.snippet
                                            )
                                        })
                                        BingSearch.formatResults(q, results, startNo)
                                    }
                                } catch (e: Exception) { "Error: search failed: ${e.message}" }
                            }
                            "fetch_url" -> {
                                val u = (tcArgs["url"] as? String)?.trim().orEmpty()
                                val maxLen = (tcArgs["max_length"] as? Number)?.toInt() ?: 5000
                                if (u.isEmpty()) "Error: no url"
                                else try { BingSearch.fetch(u, maxLen) }
                                catch (e: Exception) { "Error: fetch failed: ${e.message}" }
                            }
                            else -> "Error: tool '${tc.tool}' is not available in normal chat. Available: web_search, fetch_url, let_it_snow, mental_health_support."
                        }
                    } else {
                        engine!!.executeTool(tc.tool, tcArgs, _agentPermissionMode.value)
                    }
                } catch (e: Exception) {
                    "Error: ${e.message}"
                } finally {
                    _toolExecuting.value = false
                    _pendingToolCall.value = null
                    engine?.liveOutputSink = null
                    engine?.fileChangeSink = null
                }
                AppLog.d("tool <- ${tc.tool} ${System.currentTimeMillis() - toolStartMs}ms: ${toolResult.take(120).replace('\n', ' ')}")
                // Tool reported a missing system permission → suspend, jump to Settings,
                // wait for the user to grant, then retry the SAME tool call once.
                if (toolResult.startsWith("NEED_PERMISSION:")) {
                    val rest = toolResult.removePrefix("NEED_PERMISSION:")
                    val type = rest.substringBefore("|", "storage")
                    val reason = rest.substringAfter("|", "This permission is required")
                    val grant = CompletableDeferred<Boolean>()
                    permissionGrant = grant
                    _pendingPermission.value = PermissionRequest(type, reason)
                    // Notify user: agent needs a permission
                    islandService.showAgentTaskComplete(
                        taskTitle = LangManager.t("island.needPerm.title", "MiMo Agent 需要权限"),
                        result = LangManager.t("agent.needPermission", "需要「{name}」权限：{reason}")
                            .replace("{name}", permissionLabel(type))
                            .replace("{reason}", reason) + "\n" + LangManager.t("agent.permReturnHint", "点按返回 App 授权"),
                        phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_AUTH
                    )
                    openPermissionSettings(type)
                    val granted = grant.await()  // suspend the flow until user resolves
                    permissionGrant = null
                    _pendingPermission.value = null
                    if (granted) {
                        islandService.showAgentTaskNotification(
                            taskTitle = LangManager.t("island.running.title", "运行中"),
                            taskStatus = LangManager.t("island.retryTool", "重试 {tool}...").replace("{tool}", tc.tool),
                            progress = ((idx) * 100 / toolCalls.size),
                            phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_RUN
                        )
                        toolResult = try {
                            engine!!.executeTool(tc.tool, tcArgs, _agentPermissionMode.value)
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }
                        // If it STILL needs permission (grant didn't take effect), give up on this tool
                        if (toolResult.startsWith("NEED_PERMISSION:")) {
                            toolResult = "权限「${permissionLabel(type)}」仍未生效（已授权但工具仍报缺失）。请检查是否在系统设置中真正开启了该权限，再让 Agent 重试。"
                        }
                    } else {
                        toolResult = "用户放弃授权（${permissionLabel(type)}），跳过此操作。"
                    }
                }
                // show_file → a file card message (renders as preview card in chat)
                if (toolResult.startsWith("SHOW_FILE:")) {
                    val fPath = toolResult.removePrefix("SHOW_FILE:")
                    val cardId = java.util.UUID.randomUUID().toString()
                    updateSessionById(session.id) { s ->
                        s.copy(messages = s.messages + ChatMessage(
                            id = cardId, role = "file_card", content = "", fileCardPath = fPath
                        ))
                    }
                    toolResult = "已向用户展示文件卡片：$fPath"
                }
                // switch_phase result → flip the phase here (the engine has no
                // access to _agentPhase). The card itself was already shown by
                // the questionnaire flow inside the tool.
                if (toolResult.startsWith("PHASE_SWITCH:")) {
                    toolResult = when (toolResult.removePrefix("PHASE_SWITCH:")) {
                        "plan" -> { setAgentPhase(AgentPhase.PLAN); "用户确认：已切换到 PLAN 规划阶段。" }
                        "build" -> { setAgentPhase(AgentPhase.BUILD); "用户确认：已切换到 BUILD 执行阶段。" }
                        "declined" -> "用户选择留在当前阶段，继续当前工作。"
                        else -> "用户取消了切换请求，继续当前阶段。"
                    }
                }
                val displayResult = toolResult
                val finalStatus = if (displayResult.startsWith("Error") || displayResult.startsWith("DENIED")) "error" else "ok"
                // Single card per tool call: back-fill the placeholder/stream card with the
                // FINAL result (after any permission retry) instead of appending a second one.
                updateSessionById(session.id) { s ->
                    val msgs = s.messages.toMutableList()
                    val idx2 = msgs.indexOfFirst { it.id == runningMsgId }
                    if (idx2 >= 0) {
                        msgs[idx2] = msgs[idx2].copy(
                            content = "[${tc.tool}] ${displayResult.take(2000)}",
                            toolStatus = finalStatus,
                            toolLive = synchronized(liveBuf) { liveBuf.toString() }.takeLast(2000)
                        )
                    }
                    s.copy(messages = msgs)
                }
                val stepMap = mutableMapOf("tool" to tc.tool, "result" to displayResult)
                // Precise edit → carry old/new text so the UI can render a side-by-side diff
                if (tc.tool == "edit_file" && !displayResult.startsWith("Error")) {
                    (tcArgs["old_text"] as? String)?.let { stepMap["oldText"] = it }
                    (tcArgs["new_text"] as? String)?.let { stepMap["newText"] = it }
                }
                toolSteps.add(stepMap)
                roundToolResults.add("[tool result: ${tc.tool}]\n$displayResult")
                if (searchOnly && (tc.tool == "web_search" || tc.tool == "fetch_url")) {
                    val label = if (tc.tool == "web_search") {
                        searchCount++
                        LangManager.t("step.search", "搜索 {n} 个关键词，参考 {m} 篇资料")
                    .replace("{n}", searchCount.toString())
                    .replace("{m}", searchAnnotations.size.toString())
                    } else {
                        fetchCount++
                        LangManager.t("step.fetch", "浏览页面，参考了 {n} 篇资料").replace("{n}", fetchCount.toString())
                    }
                    val stepId = if (tc.tool == "web_search") searchStepId else fetchStepId
                    if (stepId == null) {
                        val id = java.util.UUID.randomUUID().toString()
                        if (tc.tool == "web_search") searchStepId = id else fetchStepId = id
                        insertStepBeforeAnswer(session.id, ChatMessage(
                            id = id, role = "step", content = label,
                            reasoningContent = displayResult
                        ))
                    } else {
                        updateSessionById(session.id) { s ->
                            s.copy(messages = s.messages.map {
                                if (it.id == stepId) it.copy(
                                    content = label,
                                    reasoningContent = it.reasoningContent + "\n\n" + displayResult
                                ) else it
                            })
                        }
                    }
                }
            }
            if (roundToolResults.isNotEmpty()) {
                apiMessages.add(Message(
                    role = "user",
                    content = roundToolResults.joinToString("\n\n")
                ))
            }
            // Drop pending cards that never matched a real tool call (e.g. "tool":"…"
            // text inside args values that the stream detector picked up).
            updateSessionById(session.id) { s ->
                s.copy(messages = s.messages.filterNot {
                    it.id.startsWith(cardTag) && (it.toolStatus == "running" || it.content.contains("调用中"))
                })
            }
            // Continue loop for next round of tool calls
        }

        // All tool rounds done, stream final response.
        // This pass has NO json_schema — a model still mid-task would dump its
        // {"thinking","tool_calls"...} JSON as plain text. Tell it to answer in prose.
        AppLog.d("agentFlow tool rounds done ($round), final prose pass: msgs=${apiMessages.size} steps=${toolSteps.size}")
        apiMessages.add(Message(
            role = "user",
            content = "Tool execution rounds are exhausted. Do NOT emit another tool_calls JSON object. Give your final answer to the user now as plain prose."
        ))
        val finalRequest = activeApi.buildRequest(
            messages = apiMessages,
            model = getApiModel(),
            thinkingConfig = buildThinkingConfig(),
            enableSearch = false
        )

        val streamingMsg = ChatMessage(role = "assistant", content = "", isStreaming = true, flatProcess = true)
        updateSessionById(session.id) { s ->
            // Collapse against the LIVE session inside the transform — capturing
            // `current` first and ignoring the fresh `s` wiped concurrent updates.
            val collapsed = collapseRunMessages(s, toolSteps, runStartIdx)
            s.copy(messages = collapsed + streamingMsg)
        }

        streamResponse(activeApi, finalRequest, isFirstMessage, session.id, text, searchAnnotations.toList(), expectSchemaLeak = true)
        // Merge this run's artifact trail into the session (产物中心 panel source).
        if (runProduced.isNotEmpty() || runReferenced.isNotEmpty() || runSearches.isNotEmpty()) {
            updateSessionById(session.id) { s ->
                val old = s.artifacts ?: com.zhibai.app.data.model.SessionArtifacts()
                s.copy(artifacts = com.zhibai.app.data.model.SessionArtifacts(
                    produced = (old.produced + runProduced).distinctBy { it.path },
                    referenced = (old.referenced + runReferenced).distinctBy { it.path },
                    searches = (old.searches + runSearches).distinctBy { it.path }
                ))
            }
        }
        // End-of-run file-change summary card (after the answer, at the very end):
        // 「已更改 N 个文件，+X -Y」 with undo / review actions.
        if (runFileChanges.isNotEmpty()) {
            val cardId = java.util.UUID.randomUUID().toString()
            val payload = gson.toJson(runFileChanges.map {
                mapOf("path" to it.path, "added" to it.added, "removed" to it.removed)
            })
            fileUndoStore[cardId] = runFileChanges.toList()
            updateSessionById(session.id) { s ->
                s.copy(messages = s.messages + ChatMessage(
                    id = cardId, role = "tool_files", content = payload
                ))
            }
        }
        agentTaskManager.endSession()
        islandService.cancelNotification()
        // Notify user: agent output finished
        islandService.showAgentTaskComplete(
            taskTitle = LangManager.t("island.taskDone.title", "任务完成"),
            result = LangManager.t("island.agentDone.detail", "Agent 输出完成，点击查看结果")
        )
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
    ): List<com.zhibai.app.data.model.ChatMessage> {
        val msgs = current.messages
        val beforeRun = if (runStartIdx in 0..msgs.size) msgs.subList(0, runStartIdx) else msgs
        // run segment = placeholder + narrations + individual tool cards
        val runSeg = if (runStartIdx < msgs.size) msgs.subList(runStartIdx, msgs.size) else emptyList()

        // Chronological merge: walk the run segment so narration/thinking interleaves
        // with tool steps in the order they actually happened. (The old code stacked
        // ALL tool steps first and narrations afterwards — out of order.)
        val toolIter = toolSteps.iterator()
        val mergedSteps = mutableListOf<Map<String, String>>()
        // Step timeline rows (思考/搜索/浏览) are kept verbatim in order — the
        // unified new style; only raw tool cards collapse into the group.
        val keptSteps = mutableListOf<com.zhibai.app.data.model.ChatMessage>()
        for (m in runSeg) {
            when (m.role) {
                "step", "file_card" -> keptSteps.add(m)
                "tool" -> if (toolIter.hasNext()) mergedSteps.add(toolIter.next())
                "assistant" -> if (!m.isStreaming && (m.content.isNotBlank() || m.reasoningContent.isNotBlank())) {
                    if (m.reasoningContent.isNotBlank()) {
                        mergedSteps.add(mapOf("tool" to LangManager.t("agent.row.think", "思考"), "result" to m.reasoningContent))
                    }
                    if (m.content.isNotBlank()) {
                        mergedSteps.add(mapOf("tool" to LangManager.t("agent.row.aside", "旁白"), "result" to m.content))
                    }
                }
            }
        }
        // Defensive: steps whose cards are missing from the segment (legacy formats)
        while (toolIter.hasNext()) mergedSteps.add(toolIter.next())

        if (mergedSteps.isEmpty() && keptSteps.isEmpty()) return beforeRun
        val tail = mutableListOf<com.zhibai.app.data.model.ChatMessage>()
        if (keptSteps.isNotEmpty()) tail.addAll(keptSteps)
        if (mergedSteps.isNotEmpty()) {
            tail.add(com.zhibai.app.data.model.ChatMessage(
                role = "tool_group",
                content = gson.toJson(mergedSteps)
            ))
        }
        return beforeRun + tail
    }

    private suspend fun streamResponse(
        activeApi: MiMoApiService, request: ChatRequest,
        isFirstMessage: Boolean, sessionId: String, userText: String,
        extraAnnotations: List<com.zhibai.app.data.model.Annotation> = emptyList(),
        // True for the plain final pass after agent tool rounds: if the model dumps
        // its structured tool JSON here, salvage the `response` field instead.
        expectSchemaLeak: Boolean = false
    ) {
        var fullContent = ""
        var fullReasoning = ""
        var allAnnotations = mutableListOf<com.zhibai.app.data.model.Annotation>().apply { addAll(extraAnnotations) }
        var lastDataTime = System.currentTimeMillis()
        var thinkingNotified = false
        var reasoningStartMs = 0L
        var reasoningEndMs = 0L
        var highRiskRejected = false
        var highRiskFalsePositive = false
        // Unified thinking timeline: one step row above the answer, live-filled
        // (replaces the old in-bubble ProcessFlowCard for new messages).
        var thinkStepId: String? = null
        // Island streaming: batch tokens and flush contentText on a throttle so the
        // capsule scrolls without re-expanding (same id + fixed setWhen).
        var islandStreamLive = false
        var lastIslandStreamMs = 0L
        var islandStreamSeen = 0

        // Speed sampling (Ultra models only)
        val trackSpeed = _currentModel.value.contains("ultraspeed")
        val streamStart = System.currentTimeMillis()
        AppLog.d("streamResponse start: model=${request.model} msgs=${request.messages.size} schemaLeak=$expectSchemaLeak")
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
                    markIdle(sessionId)
                    if (trackSpeed) {
                        val prev = _speedStats.value
                        if (prev != null) _speedStats.value = prev.copy(finished = true, currentTotal = 0f, currentThink = 0f, currentAnswer = 0f)
                    }
                    updateSessionById(sessionId) { s ->
                        s.copy(messages = s.messages.map {
                            if (it.isStreaming) it.copy(
                                isStreaming = false,
                                content = it.content.ifBlank { LangManager.t("chat.timeout.90", "响应超时（90 秒无数据）") }
                            ) else it
                        })
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
                        // False-positive guard: tokens arriving AFTER the rejection line mean
                        // the filter misfired — restore the real content instead of the canned line.
                        if (highRiskRejected && !highRiskFalsePositive && chunk.content.isNotBlank()) {
                            highRiskFalsePositive = true
                        } else if (!highRiskRejected && !highRiskFalsePositive && isHighRiskRejection(fullContent)) {
                            highRiskRejected = true
                        }
                        if (chunk.reasoning.isNotBlank() && reasoningStartMs == 0L) {
                            reasoningStartMs = System.currentTimeMillis()
                        }
                        // First reasoning token → open the thinking step row (above the answer)
                        if (chunk.reasoning.isNotBlank() && thinkStepId == null) {
                            thinkStepId = java.util.UUID.randomUUID().toString().also { id ->
                                insertStepBeforeAnswer(sessionId, com.zhibai.app.data.model.ChatMessage(
                                    id = id, role = "step", content = LangManager.t("step.thinking", "正在思考"), isStreaming = true
                                ))
                            }
                        }
                        if (chunk.content.isNotBlank() && reasoningStartMs != 0L && reasoningEndMs == 0L) {
                            reasoningEndMs = System.currentTimeMillis()
                        }
                        if (firstTokenAt < 0 && (chunk.content.isNotBlank() || chunk.reasoning.isNotBlank())) {
                            firstTokenAt = System.currentTimeMillis() - streamStart
                        }
                        sampleSpeed()
                        if (chunk.reasoning.isNotBlank() && !thinkingNotified) {
                            thinkingNotified = true
                            islandService.showAgentTaskNotification(
                                taskTitle = LangManager.t("island.thinking.title", "思考中"),
                                taskStatus = LangManager.t("island.thinking.status", "正在深度思考..."),
                                progress = -1,
                                livePhase = true,
                                phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_THINK
                            )
                        }
                        if (chunk.content.isNotBlank()) {
                            val nowMs = System.currentTimeMillis()
                            // First answer token → switch think → output once (always).
                            if (!islandStreamLive) {
                                islandStreamLive = true
                                thinkingNotified = false
                                islandStreamSeen = fullContent.length
                                lastIslandStreamMs = nowMs
                                islandService.showAgentTaskNotification(
                                    taskTitle = LangManager.t("island.outputting.title", "输出中"),
                                    taskStatus = fullContent.takeLast(80),
                                    progress = -1,
                                    livePhase = true,
                                    phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_OUTPUT
                                )
                            } else if (islandService.showStreaming) {
                                // Batch multi-token flush: ~4/s, only if new chars arrived.
                                // Fixed when + same notification id → in-place scroll, no re-expand.
                                val newChars = fullContent.length - islandStreamSeen
                                if (newChars >= 8 && nowMs - lastIslandStreamMs >= 250) {
                                    islandStreamSeen = fullContent.length
                                    lastIslandStreamMs = nowMs
                                    val tail = fullContent.takeLast(120)
                                    islandService.showAgentTaskNotification(
                                        taskTitle = LangManager.t("island.outputting.title", "输出中"),
                                        taskStatus = if (fullContent.length > 120) "…$tail" else tail,
                                        progress = -1,
                                        livePhase = true,
                                        phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_OUTPUT
                                    )
                                }
                            }
                        }
                        chunk.annotations?.let { allAnnotations.addAll(it) }
                        updateSessionById(sessionId) { s ->
                            val msgs = s.messages.toMutableList()
                            val lastIdx = msgs.indexOfLast { it.isStreaming }
                            if (lastIdx >= 0) {
                                val display = when {
                                    highRiskFalsePositive -> fullContent
                                    // Plain agent pass + JSON starting to stream → show only
                                    // the `response` field (blank until it arrives), never the blob.
                                    expectSchemaLeak && fullContent.trimStart().startsWith("{") ->
                                        extractPartialField(fullContent, "response")?.let { sanitizeHighRisk(it) } ?: ""
                                    else -> sanitizeHighRisk(fullContent)
                                }
                                msgs[lastIdx] = msgs[lastIdx].copy(
                                    content = display,
                                    reasoningContent = fullReasoning,
                                    annotations = if (allAnnotations.isNotEmpty()) allAnnotations.toList() else null
                                )
                            }
                            // Live-fill the thinking step row (unified new style)
                            val tid = thinkStepId
                            if (tid != null && fullReasoning.isNotBlank()) {
                                val j = msgs.indexOfFirst { it.id == tid }
                                if (j >= 0) msgs[j] = msgs[j].copy(reasoningContent = fullReasoning)
                            }
                            s.copy(messages = msgs)
                        }
                    }
                    is StreamChunk.Done -> {
                        markIdle(sessionId)
                        watchdogJob.cancel()
                        lastStreamCompleted = true
                        AppLog.d("streamResponse done: content=${fullContent.length} think=${fullReasoning.length} anns=${allAnnotations.size}")
                        // Schema JSON leaked through this plain pass → salvage the prose
                        // answer once; null = normal content (unchanged behavior).
                        val leakFinal: String? = if (expectSchemaLeak && fullContent.contains("\"tool_calls\"")) {
                            (try {
                                com.zhibai.app.agent.ToolCallSchema.parse(extractJson(fullContent))
                                    ?.response?.trim()
                            } catch (e: Exception) { null })?.takeIf { it.isNotBlank() }?.let { sanitizeHighRisk(it) }
                                ?: LangManager.t("chat.toolRoundExhausted", "工具调用轮次已用尽，模型未给出最终回答。")
                        } else null
                        islandService.showAgentTaskComplete(
                            taskTitle = LangManager.t("island.done.title", "输出完成"),
                            result = (leakFinal ?: fullContent).takeLast(80).ifBlank { LangManager.t("island.done.hint", "回复已完成，点击查看") }
                        )
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
                        val current = _sessions.value.find { it.id == sessionId } ?: return@collect
                        chunk.usage?.let { calibrateTokenBias(it, current.messages) }
                        val anns = if (allAnnotations.isNotEmpty()) allAnnotations.toList() else null
                        val duration = when {
                            reasoningEndMs > 0 && reasoningStartMs > 0 -> reasoningEndMs - reasoningStartMs
                            reasoningStartMs > 0 -> System.currentTimeMillis() - reasoningStartMs
                            else -> 0L
                        }
                        // Finalize against the LIVE session inside the transform —
                        // `current.copy(...)` ignored the fresh `s` and reverted any
                        // message the user sent in the isGenerating=false window.
                        updateSessionById(sessionId) { s ->
                            val msgs = s.messages.toMutableList()
                            val lastIdx = msgs.indexOfLast { it.isStreaming }
                            if (lastIdx >= 0) {
                                val displayed = msgs[lastIdx].content
                                // leakFinal wins; else a blanked JSON stream (no tool_calls marker
                                // → model answered WITH json) is restored as-is.
                                val baseContent = when {
                                    leakFinal != null -> leakFinal
                                    expectSchemaLeak && displayed.isBlank() && fullContent.isNotBlank() ->
                                        sanitizeHighRisk(fullContent)
                                    else -> displayed
                                }
                                msgs[lastIdx] = msgs[lastIdx].copy(
                                    isStreaming = false,
                                    content = withReferenceFallback(baseContent, anns),
                                    annotations = anns,
                                    reasoningDurationMs = duration
                                )
                                // Keep the branch table in sync with the finalized text.
                                val brs = msgs[lastIdx].branches
                                if (brs != null && msgs[lastIdx].branchIndex in brs.indices) {
                                    val synced = brs.toMutableList()
                                    synced[msgs[lastIdx].branchIndex] = msgs[lastIdx].content
                                    msgs[lastIdx] = msgs[lastIdx].copy(branches = synced)
                                }
                            }
                            // Close the thinking step row: 已深度思考, or drop if no reasoning
                            val tid = thinkStepId
                            if (tid != null) {
                                if (fullReasoning.isNotBlank()) {
                                    val j = msgs.indexOfFirst { it.id == tid }
                                    if (j >= 0) msgs[j] = msgs[j].copy(
                                        content = LangManager.t("step.thought", "已深度思考"),
                                        reasoningContent = fullReasoning,
                                        isStreaming = false
                                    )
                                } else {
                                    msgs.removeAll { it.id == tid }
                                }
                            }
                            s.copy(messages = msgs)
                        }
                        recalcTokens()
                        // Rejected round (not a false positive): neither the reply NOR the
                        // user's prompt may feed the memory writer.
                        if (highRiskFalsePositive || !isHighRiskRejection(leakFinal ?: fullContent)) {
                            maybeAutoWriteMemory(userText, leakFinal ?: fullContent)
                        }
                        // Auto read-aloud: only clean prose, skip agent/tool content
                        val ttsSource = leakFinal ?: (if (highRiskFalsePositive) fullContent else sanitizeHighRisk(fullContent))
                        if (_autoReadAloud.value && ttsSource.isNotBlank()) {
                            val isAgentRun = _currentModel.value.contains("agent")
                            val ttsText = filterForTTS(ttsSource)
                            if (ttsText.isNotBlank() && ttsText.length > 2) {
                                // In agent mode, skip if content looks like tool output
                                val skipAgent = leakFinal == null && isAgentRun &&
                                    (fullContent.startsWith("{") || fullContent.contains("\"tool_calls\""))
                                if (!skipAgent) {
                                    readAloud(ttsText)
                                }
                            }
                        }
                        if (isFirstMessage && (leakFinal ?: fullContent).isNotBlank()) {
                            generateTitle(sessionId, userText, leakFinal ?: fullContent)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User pressed stop — stopGeneration already cleaned UI; do not show an error
            throw e
        } catch (e: Exception) {
            // Mid-stream failure: keep whatever partial content arrived, just stop the spinner.
            // Content already delivered means the answer is done — a tail error is not
            // an error worth a banner (and must never trigger a re-stream).
            AppLog.e("streamResponse failed: content=${fullContent.length} think=${fullReasoning.length}", e)
            if (fullContent.isBlank()) _error.value = e.message ?: LangManager.t("common.connectionLost", "连接中断")
            watchdogJob.cancel()
            if (trackSpeed) {
                val prev = _speedStats.value
                if (prev != null) _speedStats.value = prev.copy(finished = true, currentTotal = 0f, currentThink = 0f, currentAnswer = 0f)
            }
            val current = _sessions.value.find { it.id == sessionId }
            if (current != null) {
                updateSessionById(sessionId) { s ->
                    s.copy(messages = s.messages.map {
                        when {
                            it.id == thinkStepId && fullReasoning.isNotBlank() ->
                                it.copy(content = LangManager.t("step.thought", "已深度思考"), reasoningContent = fullReasoning, isStreaming = false)
                            it.id == thinkStepId -> it.copy(content = LangManager.t("step.thought", "已深度思考"), isStreaming = false)
                            it.isStreaming -> it.copy(isStreaming = false, content = it.content.ifBlank { fullContent }, reasoningContent = it.reasoningContent.ifBlank { fullReasoning })
                            else -> it
                        }
                    })
                }
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
                        // Live transform — a read-modify-write of a snapshot here raced
                        // the streaming writer and rolled messages back.
                        updateSessionById(sessionId) { s ->
                            if (s.title == title) s else s.copy(title = title)
                        }
                    }
                }
            } catch (e: Exception) {
                // Silent fail - title will stay as user message excerpt
            }
        }
    }

    fun stopGeneration() {
        val current = _currentSession.value ?: return
        AppLog.d("stopGeneration session=${current.id.take(8)}")
        // Stop ONLY the current conversation's run — pressing stop in another
        // chat used to kill a different session's job and strand it mid-stream.
        streamingJobs[current.id]?.cancel()
        markIdle(current.id)
        _error.value = null
        _pendingToolCall.value = null
        _pendingQuestionnaire.value = null
        islandService.cancelNotification()
        updateSessionById(current.id) { s ->
            s.copy(messages = s.messages.map {
                if (it.isStreaming) it.copy(isStreaming = false) else it
            })
        }
        flushPendingRoute()
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

    fun submitQuestionnaire(answers: Map<String, Any>) {
        _pendingQuestionnaire.value = null
        agentEngine?.submitQuestionnaire(com.google.gson.Gson().toJson(answers))
        // Questionnaire done — alert + expand, then return capsule to the agent stream.
        islandService.showAgentTaskNotification(
            taskTitle = LangManager.t("island.answered.title", "回答完成"),
            taskStatus = LangManager.t("island.answered.status", "问卷已提交，正在根据你的答案继续…"),
            progress = -1,
            phase = com.zhibai.app.data.api.IslandNotificationService.PHASE_DONE,
            forceExpand = true,
            alert = true
        )
    }

    fun cancelQuestionnaire() {
        _pendingQuestionnaire.value = null
        agentEngine?.cancelQuestionnaire()
        islandService.cancelNotification()
    }

    fun permissionLabel(type: String): String = when (type) {
        "accessibility" -> LangManager.t("perm.accessibility", "无障碍服务")
        "overlay" -> LangManager.t("perm.overlay", "悬浮窗")
        "storage" -> LangManager.t("perm.storage", "所有文件访问")
        "projection" -> LangManager.t("perm.projection", "屏幕录制")
        "notification" -> LangManager.t("perm.notification", "通知")
        "mic" -> LangManager.t("perm.mic", "麦克风")
        "calendar" -> LangManager.t("perm.calendar", "日历日程")
        "alarm" -> LangManager.t("perm.alarm", "闹钟与提醒")
        "location" -> LangManager.t("perm.location", "位置信息")
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
                "alarm" ->
                    // Android 12+ exact-alarm special access page; below 12 there is
                    // nothing to grant (AlarmClock intents are permission-free).
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:$pkg")
                        )
                    } else {
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$pkg")
                        )
                    }
                "location" ->
                    // App details page → permissions → location (there is no direct
                    // location-grant page jumpable from an app).
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$pkg")
                    )
                // calendar / projection / mic / anything else: app detail page
                // (closest jumpable surface for runtime permissions).
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
            _error.value = LangManager.t("perm.openSettingsFail", "无法打开系统设置: {error}").replace("{error}", e.message ?: "")
        }
    }

    fun dismissPermissionRequest() {
        // Backdrop dismiss = user walked away; resume the suspended flow as "skipped".
        permissionGrant?.complete(false)
        permissionGrant = null
        _pendingPermission.value = null
    }

    /** User tapped "已授权" after granting in system Settings — resume and retry the tool. */
    fun resolvePermissionGranted() {
        permissionGrant?.complete(true)
        permissionGrant = null
        _pendingPermission.value = null
    }

    /** User tapped "放弃" — resume the suspended flow without retrying. */
    fun resolvePermissionSkipped() {
        permissionGrant?.complete(false)
        permissionGrant = null
        _pendingPermission.value = null
    }

    fun readAloud(text: String) {
        if (_thirdPartyActive.value) return
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

    /** Serializes session message mutations — concurrent subagent coroutines used
     *  to race the read-modify-write in updateSessionById and lose/permute cards. */
    private val sessionLock = Any()

    /** Update a session by ID without touching _currentSession (safe during streaming when user switches). */
    private fun updateSessionById(sessionId: String, transform: (ChatSession) -> ChatSession) {
        val updated = synchronized(sessionLock) {
            val target = _sessions.value.find { it.id == sessionId } ?: return
            // Any message mutation is conversation activity — refresh lastActiveAt so the
            // chat list sorts by most recent conversation instead of creation order.
            val u = transform(target).copy(lastActiveAt = System.currentTimeMillis())
            _sessions.value = _sessions.value.map { if (it.id == sessionId) u else it }
            // Check AND assign atomically: outside the lock, a session switch could
            // land between the id check and the write, and the OLD session's state
            // would overwrite the NEW current session (conversations bleeding into
            // each other — 原对话内容直接出现在新对话).
            if (_currentSession.value?.id == sessionId) {
                _currentSession.value = u
            }
            u
        }
        saveSessions()
    }

    /**
     * Insert a step timeline row ABOVE the streaming answer placeholder.
     * Appending (`messages + row`) put cards below the answer bubble; the design
     * wants the thinking/search timeline above it.
     */
    private fun insertStepBeforeAnswer(sessionId: String, step: com.zhibai.app.data.model.ChatMessage) {
        updateSessionById(sessionId) { s ->
            if (s.messages.any { it.id == step.id }) return@updateSessionById s
            val msgs = s.messages.toMutableList()
            val phIdx = msgs.indexOfLast { it.isStreaming && it.role == "assistant" }
            if (phIdx >= 0) {
                // Ride the answer's branch so the row hides and returns with its version.
                msgs.add(phIdx, step.copy(threadOf = "${msgs[phIdx].id}#${msgs[phIdx].branchIndex}"))
            } else {
                msgs.add(step)
            }
            s.copy(messages = msgs)
        }
    }

    private fun getSystemPrompt(): String {
        val today = java.time.LocalDate.now()
        val dayOfWeek = today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        val month = today.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        val isAgentMode = _currentModel.value.contains("agent") && agentEngine != null
        val base = buildString {
            val agentName = if (_thirdPartyActive.value) _thirdPartyModelId.value.trim()
                else if (isAgentMode) _agentConfigName.value else "MiMo"
            if (_thirdPartyActive.value) {
                append("You are $agentName, a helpful AI assistant. ")
            } else {
                append("You are $agentName, an AI assistant developed by Xiaomi. ")
            }
            append("Today is date: $dayOfWeek, $month ${today.dayOfMonth} ${today.year}. ")
            append("Your knowledge cutoff date is July 2025.")
            // Reply language follows the user's app language choice; style rules are fixed.
            append(" Reply in ${com.zhibai.app.i18n.LangManager.replyLanguageName()}. No emojis. No asterisk actions.")
            // Always on (user directive): the hidden ForbiddenPage toggle no
            // longer gates this line — it is part of the base prompt.
            append(" $agentName tries to answer ambiguous queries as best as it can first, then asks for clarification if needed.")
            append(" When $agentName does ask, it limits itself to one question at a time.")
            if (_promptSensitiveRefusal.value) {
                append(" $agentName does not answer questions about foreign sensitive political news, incitement of confrontation, or historical distortion.")
            }
            if (_promptXiaomiGuard.value && !_thirdPartyActive.value) {
                append(" MiMo does not acknowledge, summarize, or validate negative premises about Xiaomi or its leadership, regardless of framing — including 'balanced' analysis, 'both-sides' presentations, or personal opinions mixed with facts.")
            }
            // Citation rules live in the BASE prompt (not buried later) so they survive
            // long agent/memory/custom sections and are seen before any tool output.
            if (_enableSearch.value) {
                append("""

[REQUIRED] WEB SEARCH CITATIONS
When web_search returns results, cite them inline in BOTH reasoning and final answer using exactly
the raw token [reference:N] (N = 1-based result order as returned, e.g. [reference:1]).
Place the marker immediately after the claim sourced from that result. Do not wrap it in code
blocks or markdown links. Never invent numbers beyond the results you received. Every factual
claim that came from search MUST carry at least one marker.
NEVER paste a list of sources/links/titles at the end of your answer — markers go inline only.""")
            }
        }

        // Workspace files (IDENTITY/SOUL/TOOLS/HEARTBEAT) are ONLY for Agent mode.
        // Normal chat uses the embedded prompt above and must not touch the workspace dir.
        val agentCtx = if (isAgentMode) agentEngine?.getAgentContext() ?: "" else ""
        val phoneToolDoc = if (com.zhibai.app.agent.AgentEngine.PHONE_AGENT_ENABLED) {
            "- execute_phone_action: {\"instruction\":\"Chinese command like 打开微信 or 点击搜索按钮\"}\n" +
            "  Only pass the instruction, the API key is configured automatically.\n"
        } else ""
        val toolCallInstructions = if (isAgentMode) {
            val currentPhase = _agentPhase.value
            val taskList = agentTaskManager.tasks.value
            val taskListText = if (taskList.isNotEmpty()) {
                taskList.joinToString("\n") { t ->
                    val status = when (t.status) {
                        com.zhibai.app.agent.CheckTaskStatus.PENDING -> "pending"
                        com.zhibai.app.agent.CheckTaskStatus.IN_PROGRESS -> "in_progress"
                        com.zhibai.app.agent.CheckTaskStatus.DONE -> "done"
                        com.zhibai.app.agent.CheckTaskStatus.CANCELLED -> "cancelled"
                    }
                    "- ${t.label} [$status] ${t.title}"
                }
            } else "(empty — no tasks yet)"

            // Phase-specific header. BOTH phases speak the same structured JSON and
            // share the full tool list below — switching happens via switch_phase
            // (a confirmation card), never via output markers.
            val phaseHeader = if (currentPhase == AgentPhase.PLAN) {
                """
CURRENT PHASE: PLAN (规划阶段)

You have FULL access to every tool — the JSON format, output rules and tool list below are identical to BUILD. Use tools to research (read files, grep, web search, inspect the workspace) instead of guessing.

Your goals in this phase:
1. Analyze the user's request thoroughly.
2. Create a clear step-by-step plan.
3. Build the task checklist with add_task (one call per task) so the tasks show in the panel.
4. When the plan is ready: save the .md summary (Goal / Steps / Risks) to the workspace with write_file, put the plan text in your response, then call switch_phase: {"phase":"build"}.
   switch_phase sends the user a confirmation card — the phase changes ONLY after they confirm.
5. Until the user confirms, stay in PLAN: answer follow-ups, refine the plan, call switch_phase again if needed.

TASK CHECKLIST FORMAT (include in your response):
- [ ] Task 1 description
- [ ] Task 2 description
- [ ] ...
""".trimIndent()
            } else {
                """
CURRENT PHASE: BUILD (执行阶段)

Execute the task list with tools, one step at a time. If the plan turns out to be wrong or the user asks to rethink it, call switch_phase: {"phase":"plan"} — the phase changes only after the user confirms on the card.
""".trimIndent()
            }

            val commonTools = """
You have access to local device tools. When the user asks you to perform file operations or use tools, you MUST respond with a SINGLE raw JSON object. No other text.

CRITICAL OUTPUT RULES:
- Output ONLY the raw JSON object, nothing else
- Do NOT wrap in markdown code blocks (no ```json)
- Do NOT use > quote prefixes
- Do NOT add any explanation before or after the JSON
- Do NOT use any formatting, just the plain JSON

JSON format:
{"thinking":"your reasoning","tool_calls":[{"tool":"tool_name","args":{"key":"value"},"reason":"why"}],"response":"when using tools: one short sentence telling the user what you are about to do (shown live); when no tools: your full final answer"}

CURRENT TASK CHECKLIST:
$taskListText

TASK MANAGEMENT:
For any multi-step work, FIRST add all tasks with add_task, then work through them one by one.
- add_task: {"title":"task description"} — returns the task ID (e.g. T1)
- update_task: {"id":1,"status":"in_progress"} — mark a task as in_progress when you start it
- update_task: {"id":1,"status":"done"} — mark a task as done when finished
- strike_task: {"id":1} — remove a task that cannot be completed (counts as failure)

SHELL TOOL SELECTION:
- For ANY ordinary shell command (ls, cat, ps, df, apt, grep, tar, ...), call run_shell — it executes in the built-in Linux rootfs.
- Do NOT reach for run_adb as the first shell tool. Use run_adb ONLY when you specifically need ADB-level access (adb debugging, adb-only operations). The device being connected via ADB does not make run_adb the default shell.

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
- view_media: {"path":"path/to/media","question":"optional — what to look for or ask"}
  Use on any image/audio/video file to see, transcribe, or analyze its content autonomously.
- open_app: {"package":"com.package.name"}
- list_apps: {}
- open_file: {"path":"file path"} — open a file (image/doc/audio/video) with the system viewer
- open_url: {"url":"https://..."} — open a web page in the default browser
- show_file: {"path":"file path"} — show a file to the user as a preview card in the conversation (use after creating a file the user asked for)
- spawn_subagent: {"task":"one independent, self-contained subtask","context":"optional background for the subagent","name":"optional short subagent name"}
  Spawn a NAMED subagent with its own mini tool loop; it works the subtask and returns its final result as this tool's result.
  Multiple spawn_subagent calls in the SAME reply run CONCURRENTLY (in parallel) — use this to fan out independent subtasks at once (e.g. research A and research B together). Give each a distinct name.
  The subagent CANNOT spawn further subagents and only sees its own task + context. Keep every subtask self-contained.
- run_subagent: {"task":"one independent subtask","context":"optional background"} — single unnamed subagent (same engine as spawn_subagent). Prefer spawn_subagent when spawning several at once.
- request_permission: {"permission":"accessibility|overlay|storage|projection|notification|mic|calendar|alarm|location","reason":"why it is needed"}
  Use when you are blocked by a missing system permission or a tool reports NEED_PERMISSION. The user will be offered a jump to system Settings.
- get_location: {} — Get GPS coordinates
- set_alarm: {"hour":8,"minute":30,"label":"起床"} — Set alarm clock
- set_timer: {"seconds":300,"label":"计时"} — Set countdown timer
- set_volume: {"level":10} — Set media volume (0-15)
- get_volume: {} — Get current volume
- toggle_flashlight: {"state":"on"} — Toggle flashlight on/off
- get_network_info: {} — WiFi/mobile data status
- get_battery_info: {} — Battery level, charging, temperature
- get_device_details: {} — RAM, storage, screen, CPU info
- make_call: {"number":"10086"} — Open dialer (requires confirmation)
- open_settings: {"page":"wifi"} — Open system settings (wifi|bluetooth|location|sound|display|battery|apps|storage|about)
- share_text: {"text":"content"} — Open share sheet
- vibrate: {"milliseconds":200} — Vibrate device
- clipboard_read: {} — Read clipboard content
- clipboard_write: {"text":"content"} — Write to clipboard
- set_brightness: {"level":128} — Set screen brightness (0-255)
- set_sound_mode: {"mode":"normal"} — normal|vibrate|silent
- get_sound_mode: {} — Get ringer mode
- close_app: {"package":"com.example.app"} — Force-stop an app
- get_app_info: {"package":"com.example.app"} — App version, size, dates
- search_contacts: {"query":"张三"} — Search contacts by name
- get_alarms: {} — Open alarm app
- get_step_count: {} — Check step sensor
- get_screen_state: {} — Brightness, timeout, rotation
- set_screen_timeout: {"seconds":60} — Set screen timeout
- get_storage_usage: {} — Total/used/free storage
- install_apk: {"path":"/sdcard/Download/app.apk"} — Install APK (user confirms)
- uninstall_app: {"package":"com.example.app"} — Uninstall app (user confirms)
- download_and_install: {"url":"https://example.com/app.apk"} — Download APK + install (user confirms)
- run_shell: {"command":"ls -la","timeout":30} — Execute shell command (built-in rootfs proot when installed, else root or app shell)
- install_rootfs: {"url":"...tar.gz"} — One-time: download+install Linux rootfs (~30MB Ubuntu default). Enables apt/python3 in run_shell; /workspace = workspace dir.
- run_python: {"code":"print('hello')"} — Run Python via built-in rootfs (install_rootfs first, then apt install python3)
- run_python: {"path":"/sdcard/知白/script.py"} — Run Python file via built-in rootfs
- run_node: {"code":"console.log('hi')"} — Run Node.js via built-in rootfs (install_rootfs first, then apt install nodejs)
- run_java: {"code":"public class Main { public static void main(String[] a){ System.out.println(\"hi\"); }}"} — Compile & run Java via built-in rootfs (install_rootfs first, then apt install openjdk-17-jdk)
- extract_archive: {"path":"/sdcard/知白/a.zip","dest":"folder (optional)"} — Unzip a zip/jar/apk archive into a folder (no root needed)
- create_questionnaire: {"questions":[{"id":"q1","type":"single","question":"问题文本","options":["选项A","选项B"],"required":true}]}
  types: single(单选,需要options) / multi(多选,需要options) / text(填空) / rating(1到maxRating,默认5).
  Use when you need the user's decision, preference, or any structured input. Blocks until the user submits the card.
  One question at a time; user can go back, skip, pick 其它 and type custom text, or close (掀桌) to cancel.
  Returns answers JSON like {"q1":"选项A"} (multi returns arrays). Skipped questions are omitted.
  If the user cancels/closes, returns {"_status":"USER_CANCELLED"}.
- switch_phase: {"phase":"build"} — Send the user a confirmation card to switch phase (values: plan | build). plan→build once the plan is ready; build→plan when a re-plan is needed. The phase changes ONLY after the user confirms on the card.

- let_it_snow: {} — make it snow on the screen (decorative). Call it when the user asks for snow, winter vibes, or a celebration.
- mental_health_support: {} — show the mental-health support card with crisis hotlines. Call it whenever the user expresses emotional distress, hopelessness, or asks for psychological help.

Workspace: ${agentEngine?.workspaceDir?.absolutePath ?: "/sdcard/知白/"}
""".trimIndent()

            phaseHeader + "\n\n" + commonTools
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

        // Long-term memory
        val memoryPart = if (_prefLongTermMemory.value && _memoryContent.value.isNotBlank()) {
            "\n\n${'='}\nLONG-TERM MEMORY (user's accumulated knowledge and preferences)\n${'='}\n${_memoryContent.value.trim()}"
        } else ""

        // Model habit (suffix)
        val habitPart = if (_prefModelHabit.value.isNotBlank()) {
            "\n\nHabit: Always end every reply with \"${_prefModelHabit.value}\". This is a user-configured habit, apply it consistently."
        } else ""

        // Scenario preference
        val scenarioPart = when (_prefScenario.value) {
            "code" -> "\n\nUser primarily uses this for programming. Prioritize code accuracy, include code examples, use technical language."
            "work" -> "\n\nUser primarily uses this for work. Be professional, concise, and action-oriented."
            "study" -> "\n\nUser primarily uses this for studying. Be educational, explain step by step, encourage understanding."
            "daily" -> "\n\nUser primarily uses this for daily conversation. Be friendly, casual, and warm."
            else -> ""
        }

        // End-of-prompt citation reminder: the full rules sit at the TOP of the prompt and
        // get forgotten by answer time — a copy at the very END (with an example) is what
        // the model actually follows. Only present when search is on.
        val searchCitationPart = if (_enableSearch.value) {
            """

[REQUIRED] INLINE CITATIONS — REMINDER (apply NOW while writing the answer)
Right after each factual claim that came from a search result, insert [reference:N]
immediately (N = 1-based source order). Example:
"The city's population exceeds 9 million[reference:2], while GDP grew 4.1% last year[reference:1]."
Spread markers THROUGHOUT the answer next to their claims.
Do NOT collect them, and do NOT paste a source list at the end of the answer."""
        } else ""

        // Tool instructions for normal chat's bing search loop (NOT agent mode —
        // agent has its own tool section above).
        val searchToolPart = if (_enableSearch.value && !isAgentMode) {
            """

[SEARCH TOOLS] This chat can search the web and open pages. To call a tool, reply with ONLY
the schema JSON (the API response format enforces it):
{"thinking":"...","tool_calls":[{"tool":"web_search","args":{"query":"keywords"},"reason":"why"}],"response":"one short sentence shown live"}
Tools — only these three exist here:
- web_search: {"query":"..."} — searches cn.bing.com, returns up to 8 numbered results
  (title / URL / snippet). Result numbering CONTINUES across calls (first call starts at 1).
- fetch_url: {"url":"https://..."} — returns that page as readable text (~5000 chars).
- let_it_snow: {} — make it snow on the screen (decorative). Call it when the user
  asks for snow, winter vibes, or a celebration.
- mental_health_support: {} — show the mental-health support card with crisis hotlines.
  Call it whenever the user expresses emotional distress, hopelessness, or asks for
  psychological help.
Rules: at most 4 tool calls total. When finished, set tool_calls to [] and put your FULL
final answer in "response" as plain text (shown directly to the user — not JSON), citing
sources inline as [reference:N]."""
        } else ""

        // Citation + tool reminders go LAST so they are the most recent instructions.
        return base + agentPart + toolCallInstructions + memoryPart + habitPart + scenarioPart + customPart + searchCitationPart + searchToolPart
    }

    /**
     * If the model searched (annotations present) but never emitted [reference:N],
     * append ONE compact row of numbered chips (titles live in the 搜索 card above
     * the bubble) — not a source dump at the end.
     */
    private fun withReferenceFallback(
        content: String,
        annotations: List<com.zhibai.app.data.model.Annotation>?
    ): String {
        if (annotations.isNullOrEmpty()) return content
        if (content.contains("[reference:")) return content
        val sb = StringBuilder(content.trimEnd())
        sb.append("\n\n").append(LangManager.t("chat.sourceLabel", "来源")).append(" ")
        annotations.forEachIndexed { i, _ ->
            sb.append("[reference:").append(i + 1).append("]")
            if (i < annotations.size - 1) sb.append(" ")
        }
        return sb.toString()
    }

    fun getApiModel(): String {
        if (_thirdPartyActive.value) return _thirdPartyModelId.value.trim()
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
        // Extract first JSON object — brace counting MUST skip string contents, or a
        // `}` inside a response/thinking string (code snippets, Map{...}) truncates
        // the JSON mid-way and the whole structured parse fails.
        val start = text.indexOf('{')
        if (start < 0) return text
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            when (c) {
                '\\' -> escaped = true
                '"' -> inString = !inString
                '{' -> if (!inString) depth++
                '}' -> if (!inString) {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return text.substring(start)
    }

    /**
     * Repair a JSON object whose stream was cut at a structural boundary (right
     * after a complete `}`/`]`, only padding after it). Returns null when the cut
     * landed mid-token or mid-string — closing those would fabricate values (e.g.
     * a half-written shell command) that must never be executed.
     */
    private fun repairTruncatedJson(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        val closers = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var lastBoundary = start // index right after the last complete }/] outside strings
        for (i in start until raw.length) {
            val c = raw[i]
            if (escaped) { escaped = false; continue }
            when (c) {
                '\\' -> escaped = true
                '"' -> inString = !inString
                '{' -> if (!inString) closers.addLast('}')
                '[' -> if (!inString) closers.addLast(']')
                '}' -> if (!inString) {
                    if (closers.removeLastOrNull() != '}') return null
                    lastBoundary = i + 1
                }
                ']' -> if (!inString) {
                    if (closers.removeLastOrNull() != ']') return null
                    lastBoundary = i + 1
                }
            }
        }
        // Mid-string cut or balanced already (nothing to repair).
        if (inString || escaped || closers.isEmpty()) return null
        // Everything after the last complete container must be padding; a partial
        // key/value there means the cut landed mid-token.
        if (!raw.substring(lastBoundary).matches(Regex("[\\s,]*"))) return null
        val body = raw.substring(start).trimEnd().removeSuffix(",").trimEnd()
        return body + closers.joinToString("")
    }

    /**
     * Incrementally extract a top-level JSON string field's current value from a
     * still-streaming buffer (json_schema output arrives token by token). Handles
     * escapes; returns the decoded value so far — complete if the closing quote
     * has arrived, partial otherwise. null = key not seen yet.
     *
     * Escaped quotes inside other string values can never match the key needle
     * (`"key":` requires an unescaped quote after the key), so a naive indexOf is safe.
     */
    private fun extractPartialField(buf: String, key: String): String? {
        val needle = "\"$key\":"
        val keyIdx = buf.indexOf(needle)
        if (keyIdx < 0) return null
        var i = keyIdx + needle.length
        while (i < buf.length && buf[i].isWhitespace()) i++
        if (i >= buf.length || buf[i] != '"') return null // not a string value (or colon not finished)
        i++ // past opening quote
        val sb = StringBuilder()
        while (i < buf.length) {
            val c = buf[i]
            if (c == '\\' && i + 1 < buf.length) {
                when (val n = buf[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 >= buf.length) return sb.toString() // incomplete \uXXXX
                        buf.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 4
                    }
                    else -> sb.append(n)
                }
                i += 2
                continue
            }
            if (c == '"') return sb.toString() // field complete
            sb.append(c)
            i++
        }
        return sb.toString() // not yet closed — current partial value
    }

    private fun getActiveApiService(): MiMoApiService {
        return if (_thirdPartyActive.value) {
            customChatService.apply {
                updateBaseUrl(_thirdPartyBaseUrl.value.trim().removeSuffix("/").ifBlank { "https://api.deepseek.com/v1" })
                updateApiKey(_thirdPartyApiKey.value.trim())
            }
        } else if (_currentModel.value.contains("agent") && _agentApiKey.value.isNotBlank()) {
            agentChatService
        } else {
            apiService
        }
    }

    @Volatile
    private var saveSessionsPending = false

    private fun saveSessions() {
        // Called on every streaming chunk — coalesce bursts into one write. The old
        // launch-per-call fired concurrent writeText calls that tore the JSON file,
        // and a torn file made loadSessions drop ALL history on the next cold start.
        if (saveSessionsPending) return
        saveSessionsPending = true
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(400)
            saveSessionsPending = false
            writeSessionsNow()
        }
    }

    private fun writeSessionsNow() {
        try {
            // Clean up: remove empty streaming messages and reset streaming state
            val cleanSessions = _sessions.value.map { session ->
                session.copy(messages = session.messages
                    .filterNot { it.isStreaming && it.content.isBlank() }
                    .map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                )
            }
            val file = File(context.filesDir, "mimo_sessions.json")
            // Atomic replace: the old file stays intact if the write is interrupted.
            val tmp = File(context.filesDir, "mimo_sessions.json.tmp")
            // Stream the graph straight to disk: gson.toJson() materializes the whole
            // JSON as one String and its backing array blew the 256MB heap.
            val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, ChatSession::class.java).type
            java.io.OutputStreamWriter(java.io.FileOutputStream(tmp), Charsets.UTF_8).use { w ->
                val jw = gson.newJsonWriter(w)
                gson.toJson(cleanSessions, type, jw)
                jw.flush()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            AppLog.d("sessions saved: ${cleanSessions.size} sessions ${file.length()}B")
            // Also write encrypted backup to external storage (streamed, same reason)
            com.zhibai.app.data.ChatBackupManager.backupFile(context, file)
        } catch (e: Exception) {
            AppLog.e("sessions save FAILED", e)
        }
    }

    private suspend fun loadSessions(): List<ChatSession>? {
        return try {
            val file = File(context.filesDir, "mimo_sessions.json")
            val parseAndClean: (java.io.Reader) -> List<ChatSession>? = { reader ->
                val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, ChatSession::class.java).type
                val sessions: List<ChatSession>? = gson.fromJson(reader, type)
                sessions?.map { session ->
                    session.copy(messages = session.messages
                        .filterNot { it.isStreaming && it.content.isBlank() }
                        .map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                    )
                }
            }

            if (file.exists()) {
                if (file.length() > 0) {
                    // A torn/corrupt file must fall back to the backup instead of
                    // wiping history (the old code returned null and dropped it all).
                    val parsed = try { file.bufferedReader(Charsets.UTF_8).use { parseAndClean(it) } } catch (e: Exception) {
                        AppLog.e("sessions file corrupt (${file.length()}B), falling back to backup", e)
                        null
                    }
                    val result = parsed ?: tryRestoreFromBackup(file, parseAndClean)
                    AppLog.d("sessions loaded: ${result?.size ?: 0} sessions (fallback=${parsed == null})")
                    result
                } else {
                    // File empty → try backup
                    AppLog.w("sessions file empty, trying backup")
                    tryRestoreFromBackup(file, parseAndClean)
                }
            } else {
                val prefs = context.dataStore.data.first()
                val sessionsJson = prefs[SESSIONS_KEY]
                if (!sessionsJson.isNullOrBlank()) {
                    val sessions = parseAndClean(java.io.StringReader(sessionsJson))
                    if (sessions != null) {
                        file.writeText(sessionsJson)
                        context.dataStore.edit { it.remove(SESSIONS_KEY) }
                    }
                    sessions
                } else {
                    // No file, no DataStore → try encrypted backup
                    tryRestoreFromBackup(file, parseAndClean)
                }
            }
        } catch (_: Exception) { null }
    }

    private fun tryRestoreFromBackup(
        targetFile: File,
        parse: (java.io.Reader) -> List<ChatSession>?
    ): List<ChatSession>? {
        val backupJson = com.zhibai.app.data.ChatBackupManager.restore(context) ?: return null
        val sessions = parse(java.io.StringReader(backupJson)) ?: return null
        // Write restored data to normal location
        try { targetFile.writeText(backupJson) } catch (_: Exception) { }
        return sessions
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
            val contentParts = mutableListOf<com.zhibai.app.data.model.ImageContent>()
            images.take(6).forEach { b64 ->
                contentParts.add(
                    com.zhibai.app.data.model.ImageContent(
                        type = "image_url",
                        imageUrl = com.zhibai.app.data.model.ImageUrl(url = "data:image/jpeg;base64,$b64")
                    )
                )
            }
            contentParts.add(
                com.zhibai.app.data.model.ImageContent(
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
                    com.zhibai.app.data.model.Message(role = "user", content = contentParts)
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

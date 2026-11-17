package com.zhibai.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.zhibai.app.adb.AdbManager
import com.zhibai.app.data.api.AgentIslandBridge
import com.zhibai.app.data.api.IslandNotificationService
import com.zhibai.app.i18n.LangManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

data class PhoneAgentStep(
    val action: AutoGLMAction?,
    val screenshot: Bitmap?,
    val description: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class PhoneAgentState(
    val isRunning: Boolean = false,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val currentInstruction: String = "",
    val steps: List<PhoneAgentStep> = emptyList(),
    val error: String? = null,
    val isWaitingForUser: Boolean = false,
    val isPaused: Boolean = false,
    val adbConnected: Boolean? = null
)

/**
 * AutoGLM phone agent: screenshot (MediaProjection) → autoglm-phone API → ADB shell input.
 * Live control (终止 / 暂时接管) rides the Super Island capsule, not a floating bar.
 */
class PhoneAgentEngine(
    private val context: Context
) {
    private val adb = AdbExecutor()
    private val autoGLM = AutoGLMService()
    private val island = IslandNotificationService(context)
    private val history = mutableListOf<Map<String, String>>()
    private var maxSteps = 20
    private var pausedContinuation: kotlin.coroutines.Continuation<Boolean>? = null

    var onStateChange: ((PhoneAgentState) -> Unit)? = null
    var onScreenshot: ((Bitmap) -> Unit)? = null
    var onTaskComplete: ((String) -> Unit)? = null

    private var state = PhoneAgentState()
        set(value) {
            field = value
            onStateChange?.invoke(value)
        }

    /** Load API key / base URL / max steps from settings prefs. */
    fun loadConfig() {
        val prefs = context.getSharedPreferences("mimo_settings", Context.MODE_PRIVATE)
        val key = prefs.getString("zhipu_api_key", "") ?: ""
        val baseUrl = prefs.getString("zhipu_base_url", "https://open.bigmodel.cn/api/paas/v4")
            ?: "https://open.bigmodel.cn/api/paas/v4"
        maxSteps = prefs.getInt("phone_agent_max_steps", 20).coerceIn(1, 50)
        autoGLM.updateApiKey(key)
        autoGLM.updateBaseUrl(baseUrl)
        state = state.copy(totalSteps = maxSteps)
    }

    fun init(apiKey: String) {
        if (apiKey.isNotBlank()) {
            context.getSharedPreferences("mimo_settings", Context.MODE_PRIVATE)
                .edit().putString("zhipu_api_key", apiKey).apply()
        }
        loadConfig()
    }

    fun hasApiKey(): Boolean {
        val prefs = context.getSharedPreferences("mimo_settings", Context.MODE_PRIVATE)
        return !(prefs.getString("zhipu_api_key", "") ?: "").isNullOrBlank()
    }

    suspend fun isAdbConnected(): Boolean = adb.isConnected()

    fun cancelNotification() {
        island.cancelNotification()
        AgentIslandBridge.phoneAgentStop = null
        AgentIslandBridge.phoneAgentTakeOver = null
    }

    fun pause() {
        state = state.copy(isPaused = true)
        island.showPhoneAgentTakenOver(state.currentInstruction)
    }

    fun resume() {
        state = state.copy(isPaused = false, isWaitingForUser = false)
        pausedContinuation?.resume(true)
        pausedContinuation = null
    }

    fun stop() {
        state = state.copy(isRunning = false, isPaused = false, isWaitingForUser = false)
        pausedContinuation?.resume(false)
        pausedContinuation = null
        cancelNotification()
    }

    suspend fun executeInstruction(instruction: String) {
        if (state.isRunning) return

        loadConfig()

        if (!adb.isConnected()) {
            state = state.copy(error = LangManager.t("phone.err.adbNotConnected", "无线 ADB 未连接 — 请先在设置 → AutoGLM → 无线调试 ADB 完成配对"), isRunning = false)
            return
        }
        if (!hasApiKey()) {
            state = state.copy(error = LangManager.t("phone.err.noApiKey", "未配置智谱 API Key — 请在设置 → AutoGLM 中填写"), isRunning = false)
            return
        }

        state = PhoneAgentState(
            isRunning = true,
            currentInstruction = instruction,
            currentStep = 0,
            totalSteps = maxSteps,
            steps = emptyList(),
            adbConnected = true
        )

        AgentIslandBridge.phoneAgentStop = { stop() }
        AgentIslandBridge.phoneAgentTakeOver = { pause() }

        history.clear()
        var round = 0

        try {
            while (round < maxSteps && state.isRunning) {
                if (state.isPaused) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        pausedContinuation = cont
                        cont.invokeOnCancellation { pausedContinuation = null }
                    }
                    if (!state.isRunning) break
                    // resume: refresh capsule
                    island.showPhoneAgentRunning(instruction, round, maxSteps)
                }

                round++

                val screenshot = adb.captureScreen()
                if (screenshot == null) {
                    state = state.copy(error = LangManager.t("phone.err.captureFail", "ADB 截屏失败 — 确认无线调试仍在线"), isRunning = false)
                    return
                }

                onScreenshot?.invoke(screenshot)
                saveScreenshot(screenshot, round)

                island.showPhoneAgentRunning(instruction, round, maxSteps)

                val action = autoGLM.execute(screenshot, instruction, history)

                val step = PhoneAgentStep(
                    action = action,
                    screenshot = screenshot,
                    description = formatAction(action)
                )

                state = state.copy(
                    currentStep = round,
                    steps = state.steps + step
                )

                if (action == null) {
                    state = state.copy(error = LangManager.t("phone.err.noAction", "AI 未返回有效操作"), isRunning = false)
                    return
                }

                when (action.type) {
                    "done" -> {
                        val result = LangManager.t("phone.result.done", "任务完成，共执行 {n} 步").replace("{n}", round.toString())
                        onTaskComplete?.invoke(result)
                        island.showAgentTaskComplete(LangManager.t("phone.assistant.name", "手机助手"), result)
                        state = state.copy(isRunning = false)
                        return
                    }
                    "take_over" -> {
                        state = state.copy(isWaitingForUser = true, isRunning = false)
                        island.showPhoneAgentTakenOver(instruction)
                        return
                    }
                    "wait" -> {
                        delay(action.text?.toLongOrNull() ?: 1000)
                    }
                    else -> {
                        executeAction(action)
                        delay(400)
                    }
                }

                history.add(mapOf(
                    "role" to "assistant",
                    "content" to toDoLine(action)
                ))
                history.add(mapOf(
                    "role" to "user",
                    "content" to "操作已执行，请继续"
                ))
            }

            if (round >= maxSteps) {
                val result = LangManager.t("phone.result.maxSteps", "达到最大步数限制 ({n})").replace("{n}", maxSteps.toString())
                onTaskComplete?.invoke(result)
                island.showAgentTaskComplete(LangManager.t("phone.assistant.name", "手机助手"), result)
                state = state.copy(error = result, isRunning = false)
            }
        } catch (e: Exception) {
            state = state.copy(error = e.message, isRunning = false)
            Log.e("PhoneAgent", "execute failed", e)
        } finally {
            AgentIslandBridge.phoneAgentStop = null
            AgentIslandBridge.phoneAgentTakeOver = null
            if (state.isRunning) {
                // unexpected exit — clear island actions
                island.cancelNotification()
            }
        }
    }

    fun continueExecution() {
        resume()
    }

    private suspend fun executeAction(action: AutoGLMAction) = withContext(Dispatchers.IO) {
        when (action.type) {
            "tap" -> adb.tap(action.x ?: 0, action.y ?: 0)
            "swipe" -> adb.swipe(action.x1 ?: 0, action.y1 ?: 0, action.x2 ?: 0, action.y2 ?: 0)
            "type" -> adb.inputText(action.text ?: "")
            "back" -> adb.pressBack()
            "home" -> adb.pressHome()
            "launch" -> adb.launchApp(action.app ?: action.package_name ?: "")
            "long_press" -> adb.longPress(action.x ?: 0, action.y ?: 0)
            "double_tap" -> adb.doubleTap(action.x ?: 0, action.y ?: 0)
        }
    }

    /** Official Open-AutoGLM history line: do(action="Tap", element=[x, y]) */
    private fun toDoLine(action: AutoGLMAction): String = when (action.type) {
        "tap" -> """do(action="Tap", element=[${action.x}, ${action.y}])"""
        "swipe" -> """do(action="Swipe", start=[${action.x1}, ${action.y1}], end=[${action.x2}, ${action.y2}])"""
        "type" -> """do(action="Type", text="${action.text}")"""
        "back" -> """do(action="Back")"""
        "home" -> """do(action="Home")"""
        "launch" -> """do(action="Launch", app="${action.app ?: action.package_name}")"""
        "long_press" -> """do(action="Long Press", element=[${action.x}, ${action.y}])"""
        "double_tap" -> """do(action="Double Tap", element=[${action.x}, ${action.y}])"""
        "wait" -> """do(action="Wait", duration=${action.text ?: 1000})"""
        "done", "take_over" -> """do(action="Take_over")"""
        else -> action.type
    }

    private fun formatAction(action: AutoGLMAction?): String {
        if (action == null) return "Unknown action"
        return when (action.type) {
            "tap" -> "Tap (${action.x}, ${action.y})"
            "swipe" -> "Swipe (${action.x1},${action.y1}) -> (${action.x2},${action.y2})"
            "type" -> "Type: ${action.text}"
            "back" -> "Press Back"
            "home" -> "Press Home"
            "launch" -> "Launch ${action.package_name}"
            "long_press" -> "Long Press (${action.x}, ${action.y})"
            "double_tap" -> "Double Tap (${action.x}, ${action.y})"
            "wait" -> "Wait ${action.text}ms"
            "done" -> "Task Complete"
            "take_over" -> "Waiting for user"
            else -> action.type
        }
    }

    private fun saveScreenshot(bitmap: Bitmap, step: Int) {
        try {
            val dir = File(context.filesDir, "phone_agent_screenshots")
            dir.mkdirs()
            val file = File(dir, "step_${step}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            Log.e("PhoneAgent", "Failed to save screenshot", e)
        }
    }

    fun getState(): PhoneAgentState = state
}

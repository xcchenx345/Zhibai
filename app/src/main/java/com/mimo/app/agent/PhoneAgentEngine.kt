package com.mimo.app.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import com.mimo.app.data.api.FloatingControlService
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
    val isPaused: Boolean = false
)

class PhoneAgentEngine(
    private val context: Context
) {
    private val adb = AdbExecutor()
    private val autoGLM = AutoGLMService()
    private val history = mutableListOf<Map<String, String>>()
    private var maxSteps = 20
    private var floatingService: FloatingControlService? = null
    private var pausedContinuation: kotlin.coroutines.Continuation<Boolean>? = null

    var onStateChange: ((PhoneAgentState) -> Unit)? = null
    var onScreenshot: ((Bitmap) -> Unit)? = null
    var onTaskComplete: ((String) -> Unit)? = null

    private var state = PhoneAgentState()
        set(value) {
            field = value
            onStateChange?.invoke(value)
        }

    fun init(apiKey: String) {
        autoGLM.updateApiKey(apiKey)
        if (apiKey.isNotBlank()) {
            context.getSharedPreferences("mimo_settings", android.content.Context.MODE_PRIVATE)
                .edit().putString("zhipu_api_key", apiKey).apply()
        }
    }

    fun isAdbConnected(): Boolean = adb.isConnected()

    suspend fun pairDevice(host: String, port: Int, code: String): Boolean {
        return false
    }

    suspend fun connectDevice(host: String, port: Int): Boolean {
        return false
    }

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun startFloatingControl() {
        if (canDrawOverlay()) {
            val intent = Intent(context, FloatingControlService::class.java)
            context.startForegroundService(intent)
        }
    }

    fun stopFloatingControl() {
        context.stopService(Intent(context, FloatingControlService::class.java))
    }

    fun pause() {
        state = state.copy(isPaused = true)
    }

    fun resume() {
        state = state.copy(isPaused = false)
        pausedContinuation?.resume(true)
        pausedContinuation = null
    }

    fun stop() {
        state = state.copy(isRunning = false, isPaused = false)
        pausedContinuation?.resume(false)
        pausedContinuation = null
    }

    suspend fun executeInstruction(instruction: String) {
        if (state.isRunning) return

        state = PhoneAgentState(
            isRunning = true,
            currentInstruction = instruction,
            currentStep = 0,
            steps = emptyList()
        )

        startFloatingControl()
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
                }

                round++

                val screenshot = adb.captureScreen(context)
                if (screenshot == null) {
                    state = state.copy(error = "Failed to capture screen", isRunning = false)
                    return
                }

                onScreenshot?.invoke(screenshot)
                saveScreenshot(screenshot, round)

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
                    state = state.copy(error = "Failed to get action from AI", isRunning = false)
                    return
                }

                when (action.type) {
                    "done" -> {
                        val result = "任务完成，共执行 $round 步"
                        onTaskComplete?.invoke(result)
                        state = state.copy(isRunning = false)
                        return
                    }
                    "take_over" -> {
                        state = state.copy(isWaitingForUser = true, isRunning = false)
                        return
                    }
                    "wait" -> {
                        delay(action.text?.toLongOrNull() ?: 1000)
                    }
                    else -> {
                        executeAction(action)
                        delay(500)
                    }
                }

                history.add(mapOf(
                    "role" to "assistant",
                    "content" to """{"type":"${action.type}","description":"${formatAction(action)}"}"""
                ))
                history.add(mapOf(
                    "role" to "user",
                    "content" to "操作已执行，请继续"
                ))
            }

            if (round >= maxSteps) {
                val result = "达到最大步数限制 ($maxSteps)"
                onTaskComplete?.invoke(result)
                state = state.copy(error = result, isRunning = false)
            }
        } catch (e: Exception) {
            state = state.copy(error = e.message, isRunning = false)
        } finally {
            stopFloatingControl()
        }
    }

    fun continueExecution() {
        state = state.copy(isWaitingForUser = false)
    }

    private suspend fun executeAction(action: AutoGLMAction) = withContext(Dispatchers.IO) {
        when (action.type) {
            "tap" -> adb.tap(action.x ?: 0, action.y ?: 0)
            "swipe" -> adb.swipe(action.x1 ?: 0, action.y1 ?: 0, action.x2 ?: 0, action.y2 ?: 0)
            "type" -> adb.inputText(action.text ?: "")
            "back" -> adb.pressBack()
            "home" -> adb.pressHome()
            "launch" -> adb.launchApp(action.package_name ?: "")
            "long_press" -> adb.longPress(action.x ?: 0, action.y ?: 0)
            "double_tap" -> adb.doubleTap(action.x ?: 0, action.y ?: 0)
        }
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


package com.mimo.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

class AdbExecutor {

    fun getAccessibilityService(): MiMoAccessibilityService? {
        return MiMoAccessibilityService.instance
    }

    fun isAccessibilityEnabled(): Boolean {
        return MiMoAccessibilityService.isRunning
    }

    fun captureScreen(context: android.content.Context): Bitmap? {
        return ScreenCaptureService.captureScreen(context)
    }

    fun tap(x: Int, y: Int): Boolean {
        return getAccessibilityService()?.tap(x, y) ?: false
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        return getAccessibilityService()?.swipe(x1, y1, x2, y2, durationMs) ?: false
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 1000): Boolean {
        return getAccessibilityService()?.longPress(x, y, durationMs) ?: false
    }

    fun doubleTap(x: Int, y: Int): Boolean {
        val result = tap(x, y)
        Thread.sleep(50)
        return result && tap(x, y)
    }

    fun inputText(text: String): Boolean {
        return getAccessibilityService()?.inputText(text) ?: false
    }

    fun pressBack(): Boolean {
        return getAccessibilityService()?.pressBack() ?: false
    }

    fun pressHome(): Boolean {
        return getAccessibilityService()?.pressHome() ?: false
    }

    fun launchApp(packageName: String): Boolean {
        return getAccessibilityService()?.launchApp(packageName) ?: false
    }

    fun getScreenSize(): Pair<Int, Int> {
        return getAccessibilityService()?.getScreenSize() ?: (1080 to 2340)
    }

    fun getDeviceIp(): String? {
        return try {
            val process = ProcessBuilder("sh", "-c", "ip addr show wlan0")
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val match = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(output)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    fun isConnected(): Boolean {
        return isAccessibilityEnabled()
    }
}

package com.mimo.app.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent

class MiMoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MiMoAccessibilityService? = null
            private set
        var isRunning: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        isRunning = false
        super.onDestroy()
    }

    fun tap(x: Int, y: Int): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 1000): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun inputText(text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val focusedNode = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        return focusedNode?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) ?: false
    }

    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } else {
            false
        }
    }

    fun getScreenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }
}

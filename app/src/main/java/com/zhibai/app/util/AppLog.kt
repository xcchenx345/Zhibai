package com.zhibai.app.util

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Diagnostics log. Two sinks:
 *
 * 1. logcat under the "MiMoApp" tag — the Settings「导出日志」button filters
 *    `logcat -d -s MiMoApp`, and nothing used to log with that tag, so the
 *    export was always empty.
 * 2. an in-memory ring buffer, so the export still has content when logcat is
 *    unreadable (some ROMs restrict Runtime.exec("logcat")).
 *
 * Never log key material — use [keyShape].
 */
object AppLog {
    private const val TAG = "MiMoApp"
    private const val CAPACITY = 4000
    private val buffer = ArrayDeque<String>(CAPACITY)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun push(level: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$msg"
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
        }
        try {
            when (level) {
                "E" -> android.util.Log.e(TAG, msg)
                "W" -> android.util.Log.w(TAG, msg)
                else -> android.util.Log.d(TAG, msg)
            }
        } catch (_: Throwable) {
            // unit tests / non-Android host
        }
    }

    fun d(msg: String) = push("D", msg)

    fun w(msg: String) = push("W", msg)

    fun e(msg: String, t: Throwable? = null) = push(
        "E",
        if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg
    )

    /** Safe key fingerprint: kind + length only, never the value. */
    fun keyShape(raw: String?): String = when {
        raw.isNullOrBlank() -> "none"
        else -> "${raw.trim().take(3)}*${raw.trim().length}"
    }

    fun dump(): String = synchronized(buffer) {
        if (buffer.isEmpty()) "(buffer empty)" else buffer.joinToString("\n")
    }
}

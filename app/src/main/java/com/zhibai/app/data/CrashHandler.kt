package com.zhibai.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Global uncaught exception handler.
 * Writes crash log to internal storage; checked on next app launch.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    init {
        BuildConfigCompat.init(context)
    }

    companion object {
        private const val TAG = "CrashHandler"
        private const val FILE_NAME = "last_crash.log"

        fun getCrashFile(context: Context): File =
            File(context.filesDir, FILE_NAME)

        /** Read the last crash log without deleting. Returns null if no crash. */
        fun readCrashLog(context: Context): String? {
            val file = getCrashFile(context)
            if (!file.exists()) return null
            return try { file.readText() } catch (_: Exception) { null }
        }

        /** Delete the crash log file (call after user acknowledges). */
        fun clearCrashLog(context: Context) {
            try { getCrashFile(context).delete() } catch (_: Exception) {}
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val crashLog = buildString {
                appendLine("=== CRASH REPORT ===")
                appendLine("Time: ${java.time.LocalDateTime.now()}")
                appendLine("Thread: ${thread.name}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("App version: ${BuildConfigCompat.versionName} (${BuildConfigCompat.versionCode})")
                appendLine("Exception:")
                appendLine(sw.toString())
            }
            getCrashFile(context).writeText(crashLog)
            Log.e(TAG, "Crash saved", throwable)
        } catch (_: Exception) {
            // Last resort — don't loop
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

/** App version info, filled from PackageManager at crash-handler init (AGP 8
 *  does not generate a BuildConfig class in this module, so reflection on it
 *  silently reported "unknown (0)"). */
object BuildConfigCompat {
    @Volatile var versionName: String = "unknown"
    @Volatile var versionCode: Int = 0

    fun init(context: Context) {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            versionName = pi.versionName ?: "unknown"
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") pi.versionCode
            }
        } catch (_: Exception) { }
    }
}

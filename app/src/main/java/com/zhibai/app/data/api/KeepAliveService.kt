package com.zhibai.app.data.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.zhibai.app.MainActivity
import com.zhibai.app.i18n.LangManager

/**
 * Foreground keep-alive held while a model generation runs. Without it the app
 * is just a cached process: leaving it (e.g. via the Super Island) lets Android
 * kill it mid-run, which aborts the stream and wipes the in-memory agent and
 * terminal state - the "came back to a fully restarted app" bug.
 */
class KeepAliveService : Service() {

    companion object {
        private const val ID = 30002
        private const val CHANNEL = "generation_keepalive"

        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) { }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (_: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "Generation", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        startForeground(ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("MiMo")
            .setContentText(LangManager.t("notif.generating", "生成中…"))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

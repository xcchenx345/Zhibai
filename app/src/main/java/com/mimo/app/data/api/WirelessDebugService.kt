package com.mimo.app.data.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.mimo.app.MainActivity

class WirelessDebugService(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "wireless_debug"
    private val notificationId = 20001

    var onCodeEntered: ((String) -> Unit)? = null

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Wireless Debug",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Enter wireless debug pairing code"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showPairingNotification(pairingInfo: String, host: String, port: Int) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("action", "wireless_debug_pair")
            putExtra("host", host)
            putExtra("port", port)
            putExtra("pairing_info", pairingInfo)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("无线调试配对")
            .setContentText("点击输入配对码连接设备")
            .setStyle(NotificationCompat.BigTextStyle().bigText(pairingInfo))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "输入配对码", pendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    fun showConnectionStatus(status: String, success: Boolean) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(if (success) android.R.drawable.ic_dialog_info else android.R.drawable.ic_dialog_alert)
            .setContentTitle("ADB 连接状态")
            .setContentText(status)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId + 1, notification)
    }

    fun cancelNotifications() {
        notificationManager.cancel(notificationId)
        notificationManager.cancel(notificationId + 1)
    }
}

package com.mimo.app.data.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import com.mimo.app.MainActivity

class IslandNotificationService(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "live_activity"
        private const val CHANNEL_NAME = "Live Activity"
        private const val NOTIFICATION_ID = 10001

        fun isIslandSupported(): Boolean {
            return try {
                val clazz = Class.forName("android.os.SystemProperties")
                val method = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                val result = method.invoke(null, "persist.sys.feature.island", false) as? Boolean ?: false
                result
            } catch (e: Exception) {
                false
            }
        }

        fun getFocusProtocolVersion(context: Context): Int {
            return try {
                Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
            } catch (e: Exception) {
                0
            }
        }

        fun hasFocusPermission(context: Context): Boolean {
            return try {
                val uri = android.net.Uri.parse("content://miui.statusbar.notification.public")
                val extras = Bundle().apply {
                    putString("package", context.packageName)
                }
                val bundle = context.contentResolver.call(uri, "canShowFocus", null, extras)
                bundle?.getBoolean("canShowFocus", false) ?: false
            } catch (e: Exception) {
                false
            }
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        notificationManager.deleteNotificationChannel(CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "MiMo Agent task notifications"
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun showAgentTaskNotification(
        taskTitle: String,
        taskStatus: String,
        progress: Int = -1,
        isIndeterminate: Boolean = true,
        actions: List<Pair<String, PendingIntent>> = emptyList()
    ) {
        ensureChannel()

        val focusEnabled = hasFocusPermission(context)
        val islandSupported = isIslandSupported()
        val protocolVersion = getFocusProtocolVersion(context)
        android.util.Log.d("IslandNotification", "focus=$focusEnabled, island=$islandSupported, protocol=$protocolVersion")

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(taskTitle)
            .setContentText(taskStatus)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (progress >= 0) {
            builder.setProgress(100, progress, isIndeterminate)
        }

        for ((label, pendingIntent) in actions) {
            val action = Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_media_pause),
                label,
                pendingIntent
            ).build()
            builder.addAction(action)
        }

        val notification = builder.build()
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun showAgentTaskComplete(taskTitle: String, result: String) {
        ensureChannel()

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(taskTitle)
            .setContentText(result)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openPendingIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        val notification = builder.build()
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

}

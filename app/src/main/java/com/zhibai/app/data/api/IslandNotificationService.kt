package com.zhibai.app.data.api

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.zhibai.app.MainActivity

/** One Super Island permission row for the settings checklist. */
data class IslandPermissionStatus(
    val id: String,
    val title: String,
    val detail: String,
    val granted: Boolean
)

class IslandNotificationService(private val context: Context) {

    companion object {
        private const val TAG = "IslandNotification"
        private const val CHANNEL_ID = "live_activity"
        private const val CHANNEL_NAME = "Live Activity"
        /** Heads-up + sound channel for 需要授权 / 需要答题 / 回答完成. */
        private const val ALERT_CHANNEL_ID = "live_activity_alert"
        private const val ALERT_CHANNEL_NAME = "Live Activity Alert"
        private const val NOTIFICATION_ID = 10001
        private const val NOTIFICATION_ID_ALERT = 10002

        // Capsule phases — each maps to its own monochrome icon.
        const val PHASE_WAIT = "wait"
        const val PHASE_THINK = "think"
        const val PHASE_OUTPUT = "output"
        const val PHASE_RUN = "run"
        const val PHASE_AUTH = "auth"
        const val PHASE_DONE = "done"

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

        /** Jump to the HyperOS notification-promotion (超级岛实时动态) toggle for this app. */
        fun openPromotionSettings(context: Context) {
            try {
                val action = if (Build.VERSION.SDK_INT >= 36) {
                    "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"
                } else {
                    "android.settings.APP_NOTIFICATION_SETTINGS"
                }
                val intent = Intent(action).apply {
                    putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "openPromotionSettings failed", e)
                try {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        /** Android 16+ (SDK 36): check whether promoted (island-capable) notifications are allowed. */
        fun canPostPromoted(context: Context): Boolean {
            return try {
                val nm = context.getSystemService(NotificationManager::class.java)
                val r = nm.javaClass.getMethod("canPostPromotedNotifications").invoke(nm)
                r as? Boolean ?: false
            } catch (e: Exception) {
                false
            }
        }

        /** Battery optimization exemption — HyperOS kills island updates without it. */
        fun isBatteryUnrestricted(context: Context): Boolean {
            return try {
                val pm = context.getSystemService(PowerManager::class.java)
                pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            } catch (e: Exception) {
                false
            }
        }

        fun hasPostNotificationsPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.areNotificationsEnabled() ?: false
            }
        }

        fun hasLiveActivityChannel(context: Context): Boolean {
            return try {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return false
                if (!nm.areNotificationsEnabled()) return false
                // Channel is created lazily; treat "not yet + can create high" as ok for pre-check.
                val ch = nm.getNotificationChannel(CHANNEL_ID)
                if (ch == null) return true
                ch.importance >= NotificationManager.IMPORTANCE_DEFAULT
            } catch (e: Exception) {
                false
            }
        }

        /** All four checklist items — island only posts when this is true. */
        fun collectPermissionStatus(context: Context): List<IslandPermissionStatus> {
            val focus = hasFocusPermission(context) && getFocusProtocolVersion(context) > 0
            // Older HyperOS may lack canPostPromotedNotifications; focus notification is still island-capable.
            val promotedOk = canPostPromoted(context) || focus
            val live = hasPostNotificationsPermission(context) &&
                promotedOk &&
                hasLiveActivityChannel(context)
            return listOf(
                IslandPermissionStatus(
                    id = "battery",
                    title = "省电策略 · 无限制",
                    detail = if (isBatteryUnrestricted(context)) "已允许后台运行" else "点此去系统设置关闭省电限制",
                    granted = isBatteryUnrestricted(context)
                ),
                IslandPermissionStatus(
                    id = "focus",
                    title = "允许通知 · 焦点通知",
                    detail = if (focus) "焦点通知已开启" else "点此检查焦点通知 / 实时动态开关",
                    granted = focus
                ),
                IslandPermissionStatus(
                    id = "ongoing",
                    title = "允许通知 · 实时动态常驻",
                    detail = if (live) "常驻通知与 Live Activity 权限正常" else "点此允许通知并开启实时动态",
                    granted = live
                ),
                IslandPermissionStatus(
                    id = "promoted",
                    title = "允许通知 · Live Activity",
                    detail = if (promotedOk) {
                        if (canPostPromoted(context)) "可发布提升通知（上岛）" else "焦点通知通道可用（上岛）"
                    } else {
                        "点此开启「实时动态 / 提升通知」"
                    },
                    granted = promotedOk
                )
            )
        }

        fun areAllPermissionsGranted(context: Context): Boolean =
            collectPermissionStatus(context).all { it.granted }

        fun openBatterySettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    openPromotionSettings(context)
                }
            }
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Serializes notify/cancel — concurrent tool coroutines raced cancel+repost
     *  and broke the capsule (blank island mid-run). */
    private val notifyLock = Any()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var completeRunnable: Runnable? = null
    private var channelReady = false
    private var alertChannelReady = false
    private var lastPhase: String? = null
    private var liveStartWhen = 0L

    /**
     * When true, a phase change cancels+reposts so HyperOS plays the expand animation again.
     * When false (default), the capsule is updated in place and stays collapsed.
     */
    @Volatile
    var expandOnPhaseChange: Boolean = false

    /** Master switch — no notifications at all when false. */
    @Volatile
    var enabled: Boolean = true

    /** Throttled multi-token content scroll on the capsule. */
    @Volatile
    var showStreaming: Boolean = true

    /** Completion state rides the same ongoing capsule, then retracts after a few seconds. */
    private fun scheduleIslandRetract(delayMs: Long = 20000) {
        completeRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            notificationManager.cancel(NOTIFICATION_ID)
            lastPhase = null
            liveStartWhen = 0L
            completeRunnable = null
        }
        completeRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelIslandRetract() {
        completeRunnable?.let { mainHandler.removeCallbacks(it) }
        completeRunnable = null
    }

    fun ensureChannel() {
        // Never delete+recreate mid-session: HyperOS cancels the promoted capsule
        // and re-expands it on the next notify. Create once, then reuse.
        if (channelReady && notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "MiMo Agent task notifications"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
        channelReady = true
    }

    private fun ensureAlertChannel() {
        if (alertChannelReady && notificationManager.getNotificationChannel(ALERT_CHANNEL_ID) != null) return
        if (notificationManager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                ALERT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Needs attention: auth / questionnaire / answers done"
                setShowBadge(false)
                enableVibration(true)
                enableLights(false)
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
        alertChannelReady = true
    }

    private fun actionPendingIntent(approve: Boolean): PendingIntent {
        val intent = Intent(context, AgentNotificationActionReceiver::class.java).apply {
            action = if (approve) AgentNotificationActionReceiver.ACTION_APPROVE
                     else AgentNotificationActionReceiver.ACTION_DENY
        }
        return PendingIntent.getBroadcast(
            context,
            if (approve) AgentNotificationActionReceiver.REQ_APPROVE else AgentNotificationActionReceiver.REQ_DENY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun phoneAgentPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AgentNotificationActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * AutoGLM running capsule: pull-down / expand shows 终止 and 暂时接管.
     * Replaces the old floating control bar.
     */
    fun showPhoneAgentRunning(instruction: String, step: Int, totalSteps: Int) {
        showAgentTaskNotification(
            taskTitle = "手机助手",
            taskStatus = "第 $step 步 · $instruction",
            livePhase = true,
            phase = PHASE_RUN,
            forceExpand = true,
            actions = listOf(
                "终止" to phoneAgentPendingIntent(
                    AgentNotificationActionReceiver.ACTION_PHONE_AGENT_STOP,
                    AgentNotificationActionReceiver.REQ_PHONE_AGENT_STOP
                ),
                "暂时接管" to phoneAgentPendingIntent(
                    AgentNotificationActionReceiver.ACTION_PHONE_AGENT_TAKEOVER,
                    AgentNotificationActionReceiver.REQ_PHONE_AGENT_TAKEOVER
                )
            )
        )
    }

    fun showPhoneAgentTakenOver(instruction: String) {
        showAgentTaskNotification(
            taskTitle = "手机助手 · 等待接管",
            taskStatus = "已暂停，可手动操作：$instruction",
            phase = PHASE_AUTH,
            forceExpand = true,
            actions = listOf(
                "终止" to phoneAgentPendingIntent(
                    AgentNotificationActionReceiver.ACTION_PHONE_AGENT_STOP,
                    AgentNotificationActionReceiver.REQ_PHONE_AGENT_STOP
                )
            )
        )
        scheduleIslandRetract(30_000)
    }

    /** Phase → monochrome icon so the capsule / expanded card reads state at a glance. */
    fun iconForPhase(phase: String): Int = when (phase) {
        PHASE_THINK -> com.zhibai.app.R.drawable.ic_island_think
        PHASE_OUTPUT -> com.zhibai.app.R.drawable.ic_island_output
        PHASE_RUN -> com.zhibai.app.R.drawable.ic_island_run
        PHASE_AUTH -> com.zhibai.app.R.drawable.ic_island_auth
        PHASE_DONE -> com.zhibai.app.R.drawable.ic_island_done
        else -> com.zhibai.app.R.drawable.ic_island_wait
    }

    fun showAgentTaskNotification(
        taskTitle: String,
        taskStatus: String,
        progress: Int = -1,
        isIndeterminate: Boolean = true,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
        showConfirmActions: Boolean = false,
        livePhase: Boolean = false,
        phase: String = PHASE_WAIT,
        /** Always cancel+repost so HyperOS re-expands the capsule (ignores expandOnPhaseChange). */
        forceExpand: Boolean = false,
        /** Heads-up + sound via the alert channel (需要授权 / 需要答题 / 回答完成). */
        alert: Boolean = false
    ) {
        synchronized(notifyLock) {
            showAgentTaskNotificationLocked(
                taskTitle, taskStatus, progress, isIndeterminate, actions,
                showConfirmActions, livePhase, phase, forceExpand, alert
            )
        }
    }

    private fun showAgentTaskNotificationLocked(
        taskTitle: String,
        taskStatus: String,
        progress: Int,
        isIndeterminate: Boolean,
        actions: List<Pair<String, PendingIntent>>,
        showConfirmActions: Boolean,
        livePhase: Boolean,
        phase: String,
        forceExpand: Boolean,
        alert: Boolean
    ) {
        if (!enabled) {
            Log.d(TAG, "island disabled by user — skip notify")
            return
        }
        if (!areAllPermissionsGranted(context)) {
            Log.d(TAG, "island permissions incomplete — skip notify")
            return
        }
        ensureChannel()
        if (alert) ensureAlertChannel()
        cancelIslandRetract()

        val focusEnabled = hasFocusPermission(context)
        val islandSupported = isIslandSupported()
        val protocolVersion = getFocusProtocolVersion(context)
        Log.d(TAG, "focus=$focusEnabled, island=$islandSupported, protocol=$protocolVersion")

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Attention states always expand; other phases respect expandOnPhaseChange.
        val prevPhase = lastPhase
        val phaseChanged = prevPhase != null && prevPhase != phase
        val shouldExpand = forceExpand || alert || expandOnPhaseChange
        if (phaseChanged || forceExpand || alert) {
            Log.d(TAG, "phase $prevPhase -> $phase expand=$shouldExpand alert=$alert")
            if (shouldExpand) {
                notificationManager.cancel(NOTIFICATION_ID)
                liveStartWhen = System.currentTimeMillis()
            }
        } else if (!livePhase || liveStartWhen == 0L || prevPhase == null) {
            liveStartWhen = System.currentTimeMillis()
        }
        lastPhase = phase

        // Heads-up + sound on a short-lived side notification (does not replace the island capsule).
        if (alert) {
            try {
                val headsUp = Notification.Builder(context, ALERT_CHANNEL_ID)
                    .setContentTitle(taskTitle)
                    .setContentText(taskStatus)
                    .setStyle(Notification.BigTextStyle().bigText(taskStatus))
                    .setSmallIcon(iconForPhase(phase))
                    .setContentIntent(openPendingIntent)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build()
                notificationManager.notify(NOTIFICATION_ID_ALERT, headsUp)
                mainHandler.postDelayed(
                    { notificationManager.cancel(NOTIFICATION_ID_ALERT) },
                    4000L
                )
            } catch (e: Exception) {
                Log.w(TAG, "heads-up alert failed: $e")
            }
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(taskTitle)
            .setContentText(taskStatus)
            .setStyle(Notification.BigTextStyle().bigText(taskStatus))
            .setSmallIcon(iconForPhase(phase))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)

        // Proven island path: standard BigTextStyle only. Any custom RemoteViews
        // (content OR big-content) or setColorized makes HyperOS drop promotion
        // and the capsule disappears. Black expanded card is not possible this way.
        if (livePhase) {
            // Keep one fixed epoch for the whole run so the capsule updates in place.
            builder.setWhen(liveStartWhen)
            builder.setUsesChronometer(true)
            builder.setProgress(0, 0, true)
        } else if (progress in 0..100) {
            builder.setProgress(100, progress, isIndeterminate)
        }

        if (showConfirmActions) {
            // Standard actions — these survive island promotion and render on pull-down.
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_revert),
                    "拒绝",
                    actionPendingIntent(approve = false)
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_send),
                    "允许",
                    actionPendingIntent(approve = true)
                ).build()
            )
        }

        for ((label, pendingIntent) in actions) {
            val action = Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_media_pause),
                label,
                pendingIntent
            ).build()
            builder.addAction(action)
        }

        // Android 16 (SDK 36): short capsule text + promoted-ongoing request —
        // the combination HyperOS turns into a Super Island capsule. (Copied from 玄戒工具箱.)
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                builder.javaClass
                    .getMethod("setShortCriticalText", String::class.java)
                    .invoke(builder, taskTitle.take(16))
            } catch (e: Exception) {
                Log.w(TAG, "setShortCriticalText unavailable")
            }
            builder.addExtras(Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
            })
            val promoted = canPostPromoted(context)
            Log.d(TAG, "canPostPromotedNotifications=$promoted")
        } else {
            builder.addExtras(Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
            })
        }

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val promotable = notification.javaClass.getMethod("hasPromotableCharacteristics").invoke(notification)
                Log.d(TAG, "hasPromotableCharacteristics=$promotable")
            } catch (e: Exception) {
                Log.d(TAG, "hasPromotableCharacteristics call failed: $e")
            }
        }
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun showAgentTaskComplete(taskTitle: String, result: String, phase: String = PHASE_DONE) {
        // Reuse the ongoing capsule so the completion state also lands on the island,
        // then auto-retract after a few seconds (big-vendor behavior).
        showAgentTaskNotification(
            taskTitle = taskTitle,
            taskStatus = result,
            progress = -1,
            phase = phase
        )
        scheduleIslandRetract()
    }

    fun cancelNotification() {
        synchronized(notifyLock) {
            notificationManager.cancel(NOTIFICATION_ID)
            notificationManager.cancel(NOTIFICATION_ID_ALERT)
            lastPhase = null
            liveStartWhen = 0L
        }
    }
}

package com.mimo.app.data.api

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import com.mimo.app.MainActivity
import com.mimo.app.R

class FloatingControlService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null

    var onAction: ((String) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(30001, buildNotification("Agent 执行中..."))
        createFloatingView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "UPDATE_STATUS" -> {
                val status = intent.getStringExtra("status") ?: ""
                updateStatus(status)
            }
            "STOP" -> stopSelf()
        }
        return START_STICKY
    }

    private fun createFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.floating_control, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        setupFloatingView()
        windowManager?.addView(floatingView, params)
    }

    private fun setupFloatingView() {
        val statusText = floatingView?.findViewById<TextView>(R.id.tvStatus)
        val btnPause = floatingView?.findViewById<Button>(R.id.btnPause)
        val btnResume = floatingView?.findViewById<Button>(R.id.btnResume)
        val btnStop = floatingView?.findViewById<Button>(R.id.btnStop)

        btnPause?.setOnClickListener {
            onAction?.invoke("pause")
            btnPause?.visibility = View.GONE
            btnResume?.visibility = View.VISIBLE
        }

        btnResume?.setOnClickListener {
            onAction?.invoke("resume")
            btnPause?.visibility = View.VISIBLE
            btnResume?.visibility = View.GONE
        }

        btnStop?.setOnClickListener {
            onAction?.invoke("stop")
            stopSelf()
        }
    }

    fun updateStatus(status: String) {
        floatingView?.findViewById<TextView>(R.id.tvStatus)?.text = status
        updateNotification(status)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_control",
                "Agent Control",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingControlService::class.java).apply {
            action = "STOP"
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, "floating_control")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MiMo Agent")
            .setContentText(status)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = buildNotification(status)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(30001, notification)
    }

    override fun onDestroy() {
        floatingView?.let {
            windowManager?.removeView(it)
        }
        super.onDestroy()
    }
}

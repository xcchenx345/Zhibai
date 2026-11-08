package com.zhibai.app.agent

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Screen capture via MediaProjection.
 * Android 10+ must run a FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION service
 * before getMediaProjection(); Android 14+ enforces this hard.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 30002
        const val REQUEST_CODE = 1001

        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null

        @Volatile private var pendingResultCode = Activity.RESULT_CANCELED
        @Volatile private var pendingData: Intent? = null
        @Volatile private var wantProjection = false

        fun hasPermission(): Boolean = mediaProjection != null

        suspend fun requestPermission(activity: Activity): Boolean {
            return suspendCancellableCoroutine { cont ->
                val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                activity.startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
                cont.resume(true)
            }
        }

        /** Called from MainActivity.onActivityResult. Starts FGS first, then projection. */
        fun onPermissionResult(resultCode: Int, data: Intent?, context: Context): Boolean {
            if (resultCode != Activity.RESULT_OK || data == null) return false
            pendingResultCode = resultCode
            pendingData = data
            wantProjection = true
            return try {
                val intent = Intent(context, ScreenCaptureService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
                wantProjection = false
                false
            }
        }

        fun captureScreen(context: Context): Bitmap? {
            if (mediaProjection == null) return null
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi

                imageReader?.close()
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtualDisplay?.release()
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null, null
                )

                Thread.sleep(200)

                val image = imageReader?.acquireLatestImage() ?: return null
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                if (rowPadding > 0) {
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()
                    return cropped
                }
                return bitmap
            } catch (e: Exception) {
                Log.w(TAG, "captureScreen failed", e)
                return null
            }
        }

        fun release() {
            virtualDisplay?.release()
            imageReader?.close()
            try { mediaProjection?.stop() } catch (_: Exception) {}
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification = buildNotification()
        // Must promote to FGS *before* getMediaProjection on API 29+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (wantProjection) {
            wantProjection = false
            val data = pendingData
            val resultCode = pendingResultCode
            pendingData = null
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mgr.getMediaProjection(resultCode, data)
                    Log.i(TAG, "MediaProjection started")
                } catch (e: Exception) {
                    Log.e(TAG, "getMediaProjection failed", e)
                    release()
                    stopSelf()
                }
            } else {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "屏幕录制",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, com.zhibai.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("屏幕录制进行中")
            .setContentText("手机助手需要读取屏幕内容")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}

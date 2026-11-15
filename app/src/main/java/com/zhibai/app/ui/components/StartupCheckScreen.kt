package com.zhibai.app.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.core.content.ContextCompat
import com.zhibai.app.R

/**
 * Startup dependency check screen:
 * logo + spinning ring + "正在检查xxx (xx%)".
 * Progress is cosmetic (心理安慰) — real work is the sequential checks in MainActivity.
 * Below it: interactive permission checklist (all-files access + notifications).
 */
@Composable
fun StartupCheckScreen(
    label: String,
    progress: Float, // 0f..1f
    modifier: Modifier = Modifier
) {
    val pct = (progress * 100).toInt().coerceIn(0, 100)
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.mimo_logo_foreground),
                contentDescription = null,
                modifier = Modifier.size(120.dp)
            )
            Spacer(modifier = Modifier.height(36.dp))
            val primary = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.size(36.dp)) {
                val stroke = 3.dp.toPx()
                val r = size.minDimension / 2 - stroke / 2
                drawCircle(
                    color = Color.Gray.copy(alpha = 0.25f),
                    radius = r,
                    style = Stroke(width = stroke)
                )
                drawArc(
                    color = primary,
                    startAngle = angle,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "正在检查$label（$pct%）",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(28.dp))
            StartupPermissionChecklist(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp)
            )
        }
    }
}

/** Two actionable permission check items shown at startup. */
@Composable
private fun StartupPermissionChecklist(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var storageGranted by remember { mutableStateOf(hasAllFilesAccess()) }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }

    // Re-check when coming back from system Settings.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                storageGranted = hasAllFilesAccess()
                notifGranted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifGranted = granted || hasNotificationPermission(context)
    }

    Column(modifier = modifier) {
        StartupCheckItem(
            title = "所有文件的访问权限",
            detail = if (storageGranted) "已允许访问所有文件" else "未授权 · 点此跳转系统设置授权",
            granted = storageGranted
        ) {
            val intent = if (Build.VERSION.SDK_INT >= 30) {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            } else {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        StartupCheckItem(
            title = "通知权限",
            detail = when {
                notifGranted -> "已允许通知"
                Build.VERSION.SDK_INT >= 33 -> "未授权 · 点此请求通知权限"
                else -> "未开启 · 点此前往通知设置"
            },
            granted = notifGranted
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }
}

@Composable
private fun StartupCheckItem(
    title: String,
    detail: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (granted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                detail,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) {
        try {
            Environment.isExternalStorageManager()
        } catch (_: Exception) {
            false
        }
    } else {
        true
    }

private fun hasNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.areNotificationsEnabled() ?: true
    }

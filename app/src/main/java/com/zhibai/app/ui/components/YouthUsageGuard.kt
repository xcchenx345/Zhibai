package com.zhibai.app.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class YouthHotline(
    val name: String,
    val number: String,
    val note: String = ""
)

/** National youth / mental-health hotlines — tap to open the dialer. */
val YouthSupportHotlines = listOf(
    YouthHotline("12355 青少年服务台", "12355", "团中央青少年服务热线"),
    YouthHotline("希望24热线", "400-161-9995", "24小时心理援助"),
    YouthHotline("教育部心理援助热线", "400-9678920", "华中师范大学心理支持"),
    YouthHotline("北京心理危机研究与干预中心", "010-82951332", "24小时危机干预"),
    YouthHotline("全国统一心理援助热线", "12356", "国家卫健委心理热线"),
    YouthHotline("紧急求助 · 公安", "110", "人身安全受到威胁时"),
    YouthHotline("医疗急救", "120", "身体不适或紧急医疗情况")
)

/** Match if message text contains any hotline number (for AI output cards). */
fun findHotlinesInText(text: String): List<YouthHotline> {
    if (text.isBlank()) return emptyList()
    return YouthSupportHotlines.filter { hot ->
        text.contains(hot.number) ||
            (hot.number.length >= 4 && text.contains(hot.number.replace("-", "")))
    }
}

fun openDialer(context: android.content.Context, number: String) {
    try {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

/** Clickable hotline row used in drawer + suggestion cards. */
@Composable
fun HotlineRow(hot: YouthHotline, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { openDialer(context, hot.number) }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Phone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(28.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(hot.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (hot.note.isNotBlank()) {
                Text(hot.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            hot.number,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End
        )
    }
}

/** Full hotline list as a side drawer sheet. */
@Composable
fun HotlineDrawerSheet() {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("求助与心理热线", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "点击条目可跳转系统拨号盘",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider()
        YouthSupportHotlines.forEach { HotlineRow(it, modifier = Modifier.padding(horizontal = 12.dp)) }
    }
}

/**
 * Age-graded continuous-usage reminder.
 * - Under 18: 30 min, youth wellbeing + hotline drawer
 * - Adult: 60 min, simple "休息一下" + coffee
 * Age is mandatory (no real-name; voluntary number only).
 */
@Composable
fun YouthUsageGuard(
    userAge: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var foregroundMs by remember { mutableLongStateOf(0L) }
    var showDialog by remember { mutableStateOf(false) }
    var isForeground by remember { mutableStateOf(true) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val isMinor = userAge in 0..17
    val thresholdMin = if (isMinor) 30 else 60

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isForeground = true
                Lifecycle.Event.ON_PAUSE -> isForeground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isForeground) {
        while (isForeground) {
            delay(15_000)
            foregroundMs += 15_000
            if (foregroundMs >= thresholdMin * 60_000L) {
                showDialog = true
            }
        }
    }

    LaunchedEffect(isForeground) {
        if (!isForeground) {
            showDialog = false
            scope.launch { drawerState.close() }
        }
    }

    if (showDialog) {
        val minutes = (foregroundMs / 60_000L).toInt()

        // Hotlines live in a drawer; dialog only holds summary + open-drawer.
        if (isMinor) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { HotlineDrawerSheet() },
                // Drawer only slides when opened from the dialog.
                gesturesEnabled = drawerState.isOpen
            ) {
                AlertDialog(
                    onDismissRequest = { /* require explicit action */ },
                    confirmButton = {
                        TextButton(onClick = {
                            foregroundMs = 0L
                            showDialog = false
                            scope.launch { drawerState.close() }
                        }) { Text("继续使用") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                scope.launch { if (drawerState.isClosed) drawerState.open() else drawerState.close() }
                            }) { Text("求助热线") }
                            TextButton(onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }) { Text("关闭") }
                        }
                    },
                    icon = { Icon(Icons.Default.SelfImprovement, contentDescription = null) },
                    title = { Text("请注意合理安排使用时间", fontWeight = FontWeight.SemiBold) },
                    text = {
                        Column {
                            Text(
                                "你已连续使用 $minutes 分钟。青少年应优先保障学习、运动与休息，" +
                                    "请合理规划时间。需要倾诉或帮助时，可打开「求助热线」。",
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("少刷手机，多读一页书", fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SelfImprovement, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("起来走走，看看远处", fontSize = 13.sp)
                            }
                        }
                    },
                    shape = RoundedCornerShape(20.dp)
                )
            }
        } else {
            AlertDialog(
                onDismissRequest = { },
                confirmButton = {
                    TextButton(onClick = {
                        foregroundMs = 0L
                        showDialog = false
                    }) { Text("好的，继续") }
                },
                icon = { Icon(Icons.Default.LocalCafe, contentDescription = null) },
                title = { Text("休息一下") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "已经用了 $minutes 分钟了，喝杯热咖啡休息一下吧。",
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                shape = RoundedCornerShape(20.dp),
                modifier = modifier
            )
        }
    }
}

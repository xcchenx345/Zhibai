package com.zhibai.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zhibai.app.agent.ScreenCaptureService
import com.zhibai.app.ui.components.StartupCheckScreen
import com.zhibai.app.ui.screens.ChatScreen
import com.zhibai.app.ui.screens.OnboardingScreen
import com.zhibai.app.ui.screens.PhoneAgentScreen
import com.zhibai.app.ui.theme.MiMoAppTheme
import com.zhibai.app.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

/** Old package name that must be uninstalled before this build works cleanly. */
private const val LEGACY_PACKAGE = "com.mimo.app"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Language system: zh-cn.json is the default table; first launch follows a
        // supported system language, later system switches raise a bilingual prompt.
        com.zhibai.app.i18n.LangManager.init(this)
        Thread.setDefaultUncaughtExceptionHandler(
            com.zhibai.app.data.CrashHandler(applicationContext)
        )
        setContent {
            val chatViewModel: ChatViewModel = viewModel()
            val customFontColor by chatViewModel.customFontColor.collectAsState()
            val customBgColor by chatViewModel.customBgColor.collectAsState()
            val customBubbleColor by chatViewModel.customBubbleColor.collectAsState()
            val customTextColor by chatViewModel.customTextColor.collectAsState()
            val customFontPath by chatViewModel.customFont.collectAsState()

            MiMoAppTheme(
                customFontColor = customFontColor,
                customBgColor = customBgColor,
                customBubbleColor = customBubbleColor,
                customTextColor = customTextColor,
                customFontPath = customFontPath
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val onboardingDone by chatViewModel.onboardingDone.collectAsState()
                    val showUpdatePreview by chatViewModel.showUpdatePreview.collectAsState()
                    val appContext = LocalContext.current.applicationContext

                    // ── Startup dependency check ─────────────────────────────
                    // phases: checking → (oldapp | perms | age) → done
                    var startupPhase by remember { mutableStateOf("checking") }
                    var checkLabel by remember { mutableStateOf("年龄验证") }
                    var checkProgress by remember { mutableFloatStateOf(0f) }
                    var pendingOldApp by remember { mutableStateOf(false) }
                    var pendingAge by remember { mutableStateOf(false) }
                    var ageOk by remember { mutableStateOf(false) }
                    var missingPerms by remember { mutableStateOf(emptyList<String>()) }

                    fun isPackageInstalled(pkg: String): Boolean = try {
                        appContext.packageManager.getPackageInfo(pkg, 0)
                        true
                    } catch (_: PackageManager.NameNotFoundException) {
                        false
                    }

                    fun basicMissingPermissions(): List<String> {
                        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) {
                            need += Manifest.permission.POST_NOTIFICATIONS
                        }
                        return need.filter {
                            appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                        }
                    }

                    // Launcher first so advance helpers can reference it.
                    val permLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { _ ->
                        // Continue even if denied — request was made on the spot.
                        if (!ageOk) startupPhase = "age"
                        else startupPhase = "done"
                    }

                    fun advanceAfterOldApp() {
                        if (missingPerms.isNotEmpty()) {
                            startupPhase = "perms"
                            permLauncher.launch(missingPerms.toTypedArray())
                        } else if (!ageOk) {
                            startupPhase = "age"
                        } else {
                            startupPhase = "done"
                        }
                    }

                    // Sequential checks with cosmetic progress (心理安慰)
                    LaunchedEffect(Unit) {
                        checkLabel = "年龄验证"
                        checkProgress = 0.22f
                        delay(450)
                        ageOk = chatViewModel.hasAgeSet

                        checkLabel = "备份数据"
                        checkProgress = 0.48f
                        delay(400)
                        // Detection only — restore already runs inside loadSessions()
                        com.zhibai.app.data.ChatBackupManager.hasBackup(appContext)

                        checkLabel = "旧版应用"
                        checkProgress = 0.74f
                        delay(400)
                        pendingOldApp = isPackageInstalled(LEGACY_PACKAGE)

                        checkLabel = "基础权限"
                        checkProgress = 0.96f
                        delay(400)
                        missingPerms = basicMissingPermissions()

                        if (pendingOldApp) {
                            startupPhase = "oldapp"
                        } else if (missingPerms.isNotEmpty()) {
                            startupPhase = "perms"
                            permLauncher.launch(missingPerms.toTypedArray())
                        } else if (!ageOk) {
                            startupPhase = "age"
                        } else {
                            checkProgress = 1f
                            delay(200)
                            startupPhase = "done"
                        }
                    }

                    val inStartup = startupPhase != "done"

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (inStartup) {
                            StartupCheckScreen(
                                label = checkLabel,
                                progress = checkProgress
                            )
                        } else when (onboardingDone) {
                            null -> { /* prefs still loading — blank */ }
                            false -> OnboardingScreen(
                                viewModel = chatViewModel,
                                onFinished = { }
                            )
                            true -> NavHost(navController = navController, startDestination = "chat") {
                                composable("chat") {
                                    ChatScreen(
                                        viewModel = chatViewModel,
                                        onNavigateToPhoneAgent = { navController.navigate("phone_agent") }
                                    )
                                }
                                composable("phone_agent") {
                                    PhoneAgentScreen(
                                        onBack = { navController.popBackStack() },
                                        onRequestScreenCapture = {
                                            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                            @Suppress("DEPRECATION")
                                            startActivityForResult(mgr.createScreenCaptureIntent(), ScreenCaptureService.REQUEST_CODE)
                                        }
                                    )
                                }
                            }
                        }

                        // Bilingual prompt when the system language changed
                        com.zhibai.app.i18n.LanguageSwitchDialog()

                        // Old package found → prompt to delete
                        if (startupPhase == "oldapp") {
                            AlertDialog(
                                onDismissRequest = { /* must choose */ },
                                title = { Text("检测到旧版应用", fontWeight = FontWeight.SemiBold) },
                                text = {
                                    Text(
                                        "检测到旧包名应用（$LEGACY_PACKAGE），可能与当前版本冲突。\n\n" +
                                            "请先卸载旧版，再继续使用本应用。"
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        try {
                                            startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:$LEGACY_PACKAGE")
                                                )
                                            )
                                        } catch (_: Exception) {}
                                    }) { Text("去卸载", fontWeight = FontWeight.SemiBold) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { advanceAfterOldApp() }) {
                                        Text("暂时跳过")
                                    }
                                }
                            )
                        }

                        // Age gate — only after checks, never during prefs load (no flash)
                        if (startupPhase == "age") {
                            com.zhibai.app.ui.components.AgeInputDialog(
                                onConfirm = { chatViewModel.setUserAge(it) }
                            )
                        }
                        // Close age phase once stored
                        val userAge by chatViewModel.userAge.collectAsState()
                        LaunchedEffect(userAge, startupPhase) {
                            if (startupPhase == "age" && userAge >= 0) {
                                startupPhase = "done"
                            }
                        }
                    }

                    // Update preview overlay (post-startup only)
                    if (!inStartup && showUpdatePreview) {
                        com.zhibai.app.ui.screens.UpdatePreviewScreen(
                            onDismiss = { chatViewModel.dismissUpdatePreview() }
                        )
                    }

                    // Youth wellbeing — only after startup checks finished
                    if (!inStartup) {
                        val userAge by chatViewModel.userAge.collectAsState()
                        if (userAge >= 0) {
                            com.zhibai.app.ui.components.YouthUsageGuard(userAge = userAge)
                        }
                    }

                    // Crash report dialog — show on next launch after a crash
                    var crashLog by remember { mutableStateOf(com.zhibai.app.data.CrashHandler.readCrashLog(applicationContext)) }
                    val currentCrashLog = crashLog
                    if (currentCrashLog != null) {
                        androidx.compose.runtime.LaunchedEffect(currentCrashLog) {
                            try {
                                val channelId = "crash_channel"
                                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                if (android.os.Build.VERSION.SDK_INT >= 26) {
                                    nm.createNotificationChannel(android.app.NotificationChannel(
                                        channelId, "崩溃报告", android.app.NotificationManager.IMPORTANCE_HIGH
                                    ))
                                }
                                val pendingIntent = android.app.PendingIntent.getActivity(
                                    this@MainActivity, 0,
                                    Intent(this@MainActivity, MainActivity::class.java),
                                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                                )
                                val notif = if (android.os.Build.VERSION.SDK_INT >= 26) {
                                    android.app.Notification.Builder(this@MainActivity, channelId)
                                } else {
                                    @Suppress("DEPRECATION")
                                    android.app.Notification.Builder(this@MainActivity)
                                }
                                    .setContentTitle("上次运行发生崩溃")
                                    .setContentText(currentCrashLog.take(100).replace('\n', ' '))
                                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                                    .setContentIntent(pendingIntent)
                                    .setAutoCancel(true)
                                    .build()
                                nm.notify(9999, notif)
                            } catch (_: Exception) {}
                        }
                        com.zhibai.app.ui.screens.CrashReportDialog(
                            crashLog = currentCrashLog,
                            onAnalyze = { log ->
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    chatViewModel.analyzeCrash(log)
                                }
                            },
                            onBackupChats = { chatViewModel.backupAllChats() },
                            onDismiss = {
                                com.zhibai.app.data.CrashHandler.clearCrashLog(applicationContext)
                                finish()
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers a language switch made in system Settings while we were away.
        com.zhibai.app.i18n.LangManager.onSystemLanguageMaybeChanged(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenCaptureService.REQUEST_CODE) {
            ScreenCaptureService.onPermissionResult(resultCode, data, this)
        }
    }
}

package com.mimo.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mimo.app.agent.ScreenCaptureService
import com.mimo.app.ui.screens.ChatScreen
import com.mimo.app.ui.screens.PhoneAgentScreen
import com.mimo.app.ui.theme.MiMoAppTheme
import com.mimo.app.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiMoAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val chatViewModel: ChatViewModel = viewModel()

                    NavHost(navController = navController, startDestination = "chat") {
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
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenCaptureService.REQUEST_CODE) {
            ScreenCaptureService.onPermissionResult(resultCode, data, this)
        }
    }
}

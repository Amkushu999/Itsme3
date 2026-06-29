package com.itsme.amkush.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.itsme.amkush.utils.SharedPrefs
import kotlinx.coroutines.delay

private val DarkBg = Color(0xFF0D0D18)
private val Violet  = Color(0xFF6C63FF)
private val Pink    = Color(0xFFFF4D9D)

class SplashScreenActivity : ComponentActivity() {

    // Launcher to open the "All Files Access" settings page and wait for the user to return
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User returned from settings page, proceed to the next screen
        proceedToNextScreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedPrefs.init(this)
        
        setContent {
            SplashContent(
                onFinished = {
                    // Instead of navigating immediately, check for storage permission first
                    checkStoragePermissionAndProceed()
                }
            )
        }
    }

    private fun checkStoragePermissionAndProceed() {
        // Android 11 (API 30) and above requires MANAGE_EXTERNAL_STORAGE to write to Downloads
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Permission not granted, open system settings
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    manageStorageLauncher.launch(intent)
                } catch (e: Exception) {
                    // Fallback to general settings if app-specific fails
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                }
                return // Wait for user to return from settings before proceeding
            }
        }
        
        // Permission granted (or Android version < 11)
        proceedToNextScreen()
    }

    private fun proceedToNextScreen() {
        val next = Intent(this, HomeScreen::class.java)
        startActivity(next)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        finish()
    }
}

@Composable
private fun SplashContent(onFinished: () -> Unit) {
    var show   by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(200); show = true
        while (true) { delay(530); cursor = !cursor }
    }
    LaunchedEffect(Unit) { delay(2800); onFinished() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = show,
            enter = fadeIn(tween(600)) + scaleIn(tween(600), initialScale = 0.85f)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "FaceGate",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(
                        brush = Brush.linearGradient(listOf(Violet, Pink))
                    )
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    Text("Happy hooking", color = Color.White.copy(0.55f),
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(if (cursor) "_" else " ", color = Violet,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

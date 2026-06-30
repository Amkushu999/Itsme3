package com.itsme.amkush.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.itsme.amkush.AppState
import com.itsme.amkush.model.AppInfo
import com.itsme.amkush.network.ApiClient
import com.itsme.amkush.network.models.TokenRequest
import com.itsme.amkush.services.InjectionService
import com.itsme.amkush.utils.DeviceUtils
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

// ─── Colors ───────────────────────────────────────────────────────────────────
private val BgDark   = Color(0xFF0D0D18)
private val Surface  = Color(0x12FFFFFF)
private val Border   = Color(0x1AFFFFFF)
private val Violet   = Color(0xFF6C63FF)
private val Pink     = Color(0xFFFF4D9D)
private val Cyan     = Color(0xFF00D4FF)
private val GreenOk  = Color(0xFF4ADE80)
private val OrangeWait = Color(0xFFFF6B35)
private val TextSec  = Color(0x44FFFFFF)
private val TextMid  = Color(0x88FFFFFF)
private val RedHook  = Color(0xFFFF1744)

class HomeScreen : ComponentActivity() {

    @SuppressLint("QueryPermissionsNeeded")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedPrefs.init(this)

        setContent {
            var showAdmin by remember { mutableStateOf(false) }
            if (showAdmin) {
                AdminCreditsScreen(onBack = { showAdmin = false })
            } else {
                HomeScreenContent(
                    onShowAdmin = { showAdmin = true },
                    onProceedToDashboard = { app ->
                        val intent = Intent(this, TabsScreen::class.java).apply {
                            putExtra("target_package", app.packageName)
                            putExtra("target_app_name", app.appName)
                        }
                        startActivity(intent)
                    },
                    onProceedToPayment = { app ->
                        val intent = Intent(this, PaymentScreen::class.java).apply {
                            putExtra("target_package", app.packageName)
                            putExtra("target_app_name", app.appName)
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }
}

// ─── IP helper ────────────────────────────────────────────────────────────────
private fun getCurrentIpAddress(context: Context): String {
    // Try WiFi first
    try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val ip = wm?.connectionInfo?.ipAddress ?: 0
        if (ip != 0) {
            return String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        }
    } catch (_: Exception) {}
    // Fall back to NetworkInterface (covers mobile data)
    try {
        val ifaces = NetworkInterface.getNetworkInterfaces()
        while (ifaces.hasMoreElements()) {
            val iface = ifaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress ?: continue
                }
            }
        }
    } catch (_: Exception) {}
    return "Unavailable"
}

// ─── Main HomeScreen ──────────────────────────────────────────────────────────
@SuppressLint("QueryPermissionsNeeded")
@Composable
private fun HomeScreenContent(
    onShowAdmin: () -> Unit,
    onProceedToDashboard: (AppInfo) -> Unit,
    onProceedToPayment: (AppInfo) -> Unit
) {
    val context = LocalContext.current

    var appList      by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading      by remember { mutableStateOf(true) }
    var selectedApp  by remember { mutableStateOf<AppInfo?>(null) }
    var showAppList  by remember { mutableStateOf(false) }
    var search       by remember { mutableStateOf("") }
    var locking      by remember { mutableStateOf(false) }

    // IP address — refreshed every 5 seconds
    var ipAddress by remember { mutableStateOf(getCurrentIpAddress(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            ipAddress = getCurrentIpAddress(context)
            delay(5_000)
        }
    }

    // Frame count from AppState (refresh every second)
    var frameCount by remember { mutableStateOf(0) }
    val isInjecting = remember { mutableStateOf(InjectionService.isRunning) }
    LaunchedEffect(Unit) {
        while (true) {
            isInjecting.value = InjectionService.isRunning
            frameCount = AppState.currentFrame.data.size / maxOf(AppState.currentFrame.width * AppState.currentFrame.height * 3 / 2, 1)
            delay(1_000)
        }
    }

    val hasStream = (SharedPrefs.getStreamUrl() ?: "").isNotEmpty()
    val mediaReady = isInjecting.value && hasStream

    val filteredApps = remember(appList, search) {
        val q = search.lowercase().trim()
        if (q.isEmpty()) appList
        else appList.filter { it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    // FG logo rotation
    val infiniteTransition = rememberInfiniteTransition(label = "logoRot")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "logoRotation"
    )

    // Load installed apps
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val pkgs = pm.getInstalledApplications(0)
                val apps = pkgs.map { pkg ->
                    val name = pm.getApplicationLabel(pkg).toString()
                    val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
                    val isSys = (pkg.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    AppInfo(pkg.packageName, name, icon, isSys)
                }.sortedBy { it.appName.lowercase() }
                withContext(Dispatchers.Main) {
                    appList = apps
                    loading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading = false
                    Logger.e("Error loading apps", e)
                }
            }
        }
    }

    LaunchedEffect(appList) {
        if (appList.isNotEmpty()) {
            val pkg  = SharedPrefs.getTargetPackage()
            val name = SharedPrefs.getTargetAppName()
            if (!pkg.isNullOrEmpty() && !name.isNullOrEmpty()) {
                val found = appList.find { it.packageName == pkg }
                if (found != null) selectedApp = found
                else if (selectedApp == null) selectedApp = AppInfo(pkg, name, null)
            }
        }
    }

    fun handleSelectApp(app: AppInfo) {
        selectedApp = app
        showAppList = false
        search = ""
        SharedPrefs.setTargetPackage(app.packageName)
        SharedPrefs.setTargetAppName(app.appName)
    }

    fun handleRestart() {
        selectedApp = null
        showAppList = false
        search = ""
        SharedPrefs.clearTarget()
        SharedPrefs.setStreamUrl(null)
        SharedPrefs.setStreamType(null)
        SharedPrefs.setLastUsedUrl(null)
        if (InjectionService.isRunning) {
            InjectionService.stop(context)
            isInjecting.value = false
        }
        Toast.makeText(context, "System reset", Toast.LENGTH_SHORT).show()
    }

    fun openTelegram() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/facegateofficial")))
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open Telegram", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleHookCamera() {
        val app = selectedApp ?: run { showAppList = true; return }
        locking = true
        CoroutineScope(Dispatchers.IO).launch {
            val token = SharedPrefs.getActivationToken()
            if (!token.isNullOrEmpty()) {
                try {
                    val deviceId = DeviceUtils.getDeviceId(context)
                    val request  = TokenRequest(token, deviceId)
                    val response = ApiClient.getApiService().verifyToken(request).execute()
                    withContext(Dispatchers.Main) {
                        locking = false
                        if (response.isSuccessful && response.body()?.valid == true) {
                            onProceedToDashboard(app)
                        } else {
                            onProceedToPayment(app)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        locking = false
                        onProceedToPayment(app)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    locking = false
                    onProceedToPayment(app)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ── Top Bar ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // FG logo + title
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Rotating FG logo
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .rotate(rotation)
                            .background(
                                brush = Brush.sweepGradient(listOf(Violet, Pink, Cyan, Violet)),
                                shape = CircleShape
                            )
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BgDark, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "FG",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    Column {
                        Text(
                            "FACEGATE",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            letterSpacing = 3.sp
                        )
                        Text(
                            "Camera Access System",
                            color = Cyan.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Admin icon button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0x226C63FF), CircleShape)
                        .border(1.dp, Violet.copy(alpha = 0.4f), CircleShape)
                        .clickable { onShowAdmin() },
                    contentAlignment = Alignment.Center
                ) {
                    // Admin/shield icon drawn with Canvas
                    Canvas(modifier = Modifier.size(22.dp)) {
                        val w = size.width
                        val h = size.height
                        // Draw a person/admin silhouette
                        drawCircle(
                            color = Color(0xFFAAAAAA),
                            radius = w * 0.22f,
                            center = Offset(w * 0.5f, h * 0.28f)
                        )
                        // Body arc
                        drawArc(
                            color = Color(0xFFAAAAAA),
                            startAngle = 180f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(w * 0.15f, h * 0.52f),
                            size = androidx.compose.ui.geometry.Size(w * 0.7f, h * 0.36f)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── IP + Status Card ─────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0F1624))
                        .border(1.dp, Border, RoundedCornerShape(20.dp))
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Column {
                        // IP Address
                        Text(
                            text = ipAddress,
                            color = Cyan,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))

                        // Status row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val pulse = rememberInfiniteTransition(label = "dot")
                                    val dotAlpha by pulse.animateFloat(
                                        initialValue = 1f,
                                        targetValue = 0.3f,
                                        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                                        label = "dotAlpha"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(
                                                color = if (mediaReady) GreenOk.copy(alpha = dotAlpha) else OrangeWait.copy(alpha = dotAlpha),
                                                shape = CircleShape
                                            )
                                    )
                                    Text(
                                        text = if (mediaReady) "MEDIA READY" else "WAITING",
                                        color = if (mediaReady) GreenOk else OrangeWait,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (mediaReady) "Stream available for hook" else "No media stream detected",
                                    color = TextMid,
                                    fontSize = 11.sp
                                )
                            }

                            if (mediaReady) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "$frameCount",
                                        color = GreenOk,
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "FRAMES",
                                        color = GreenOk.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Target App Card ──────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0F1624))
                        .border(1.dp, Border, RoundedCornerShape(20.dp))
                        .clickable { if (selectedApp == null) showAppList = !showAppList }
                        .padding(16.dp)
                ) {
                    if (selectedApp != null) {
                        val app = selectedApp!!
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            AppIconCircle(app = app, size = 50.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "TARGET LOCKED",
                                    color = Cyan.copy(alpha = 0.8f),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    app.appName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color(0x33FF4D9D), CircleShape)
                                    .border(1.dp, Pink.copy(alpha = 0.4f), CircleShape)
                                    .clickable { selectedApp = null; SharedPrefs.clearTarget() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✕", color = Pink, fontSize = 14.sp)
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .background(Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
                                    .border(1.dp, Border, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.size(26.dp)) {
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    // Camera body
                                    drawRoundRect(
                                        color = Color(0x99FFFFFF),
                                        topLeft = Offset(size.width * 0.08f, size.height * 0.28f),
                                        size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.52f),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f)
                                    )
                                    // Lens
                                    drawCircle(color = Color(0x99FFFFFF), radius = size.width * 0.18f, center = Offset(cx, cy + size.height * 0.04f))
                                    // Bump
                                    drawRoundRect(
                                        color = Color(0x99FFFFFF),
                                        topLeft = Offset(size.width * 0.3f, size.height * 0.14f),
                                        size = androidx.compose.ui.geometry.Size(size.width * 0.22f, size.height * 0.18f),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.06f)
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "SELECT TARGET",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "Choose app to hook camera",
                                    color = TextMid,
                                    fontSize = 11.sp
                                )
                            }
                            Text("›", color = TextMid, fontSize = 22.sp)
                        }
                    }
                }

                // ── App List ─────────────────────────────────────────────────
                AnimatedVisibility(
                    visible = showAppList,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x0DFFFFFF))
                            .border(1.dp, Border, RoundedCornerShape(20.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("🔍", fontSize = 13.sp, color = TextMid)
                            BasicTextField(
                                value = search,
                                onValueChange = { search = it },
                                modifier = Modifier.weight(1f),
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                                singleLine = true,
                                decorationBox = { inner ->
                                    if (search.isEmpty()) Text("Search apps...", color = TextSec, fontSize = 14.sp)
                                    inner()
                                },
                                cursorBrush = SolidColor(Violet)
                            )
                            if (search.isNotEmpty()) {
                                Text("✕", modifier = Modifier.clickable { search = "" }, color = TextSec, fontSize = 13.sp)
                            }
                        }
                        HorizontalDivider(color = Color(0x14FFFFFF))
                        if (loading) {
                            Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Violet, modifier = Modifier.size(28.dp))
                            }
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                                items(filteredApps, key = { it.packageName }) { app ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { handleSelectApp(app) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        AppIconCircle(app = app, size = 40.dp)
                                        Column(Modifier.weight(1f)) {
                                            Text(app.appName, color = Color.White, fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(app.packageName, color = TextSec, fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (selectedApp?.packageName == app.packageName) {
                                            Text("✔", color = Violet, fontSize = 14.sp)
                                        }
                                    }
                                    HorizontalDivider(color = Color(0x0AFFFFFF))
                                }
                            }
                        }
                    }
                }

                // ── Action Buttons ───────────────────────────────────────────
                // Restart System
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F1624))
                        .border(1.dp, Cyan.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .clickable { handleRestart() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Refresh icon drawn
                        Canvas(modifier = Modifier.size(18.dp)) {
                            val r = size.minDimension * 0.38f
                            drawArc(
                                color = Cyan,
                                startAngle = -30f,
                                sweepAngle = 270f,
                                useCenter = false,
                                topLeft = Offset(size.width / 2f - r, size.height / 2f - r),
                                size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                            )
                            // Arrow head
                            val tipX = size.width / 2f + r
                            val tipY = size.height / 2f
                            drawLine(Cyan, Offset(tipX - 4f, tipY - 4f), Offset(tipX, tipY), strokeWidth = 2.5f)
                            drawLine(Cyan, Offset(tipX - 4f, tipY + 4f), Offset(tipX, tipY), strokeWidth = 2.5f)
                        }
                        Text(
                            "Restart System",
                            color = Cyan,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }

                // Support & Updates
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F1624))
                        .border(1.dp, Cyan.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .clickable { openTelegram() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Telegram-style send icon
                        Canvas(modifier = Modifier.size(18.dp)) {
                            val pts = listOf(
                                Offset(size.width * 0.05f, size.height * 0.5f),
                                Offset(size.width * 0.95f, size.height * 0.12f),
                                Offset(size.width * 0.62f, size.height * 0.88f),
                                Offset(size.width * 0.38f, size.height * 0.62f),
                                Offset(size.width * 0.05f, size.height * 0.5f),
                            )
                            for (i in 0 until pts.size - 1) {
                                drawLine(Cyan, pts[i], pts[i + 1], strokeWidth = 2f)
                            }
                            drawLine(Cyan, pts[3], pts[1], strokeWidth = 2f)
                        }
                        Text(
                            "Support & Updates",
                            color = Cyan,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(Modifier.height(80.dp))
            }
        }

        // ── Bottom Hook / Select Button ───────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            val hasApp = selectedApp != null
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (hasApp)
                            Brush.linearGradient(listOf(RedHook, Color(0xFFFF4D9D)))
                        else
                            Brush.linearGradient(listOf(Color(0xFF1A1A2E), Color(0xFF1A1A2E)))
                    )
                    .border(
                        1.dp,
                        if (hasApp) Color.Transparent else Border,
                        RoundedCornerShape(20.dp)
                    )
                    .clickable(enabled = !locking) {
                        if (hasApp) handleHookCamera() else showAppList = !showAppList
                    },
                contentAlignment = Alignment.Center
            ) {
                if (locking) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Locking...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                } else if (hasApp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Camera icon
                        Canvas(modifier = Modifier.size(22.dp)) {
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(size.width * 0.06f, size.height * 0.28f),
                                size = androidx.compose.ui.geometry.Size(size.width * 0.78f, size.height * 0.52f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f)
                            )
                            drawCircle(color = Color(0xFFFF1744), radius = size.width * 0.17f,
                                center = Offset(size.width * 0.44f, size.height * 0.54f))
                            // Lens triangle (viewfinder)
                            val path = Path().apply {
                                moveTo(size.width * 0.86f, size.height * 0.35f)
                                lineTo(size.width * 0.86f, size.height * 0.65f)
                                lineTo(size.width * 1.0f, size.height * 0.5f)
                                close()
                            }
                            drawPath(path, Color.White)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "HOOK CAMERA",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "Tap to inject camera hook",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 9.sp
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("🔒", fontSize = 18.sp)
                        Text(
                            "SELECT TARGET",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

// ─── Admin Credits Screen ─────────────────────────────────────────────────────
@Composable
fun AdminCreditsScreen(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x1A6C63FF))
                        .clickable { onBack() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("‹ Back", color = Violet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "ADMIN",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(60.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(Modifier.height(10.dp))

                // Logo glow
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .background(
                            brush = Brush.radialGradient(listOf(Violet.copy(0.3f), Color.Transparent)),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                brush = Brush.sweepGradient(listOf(Violet, Pink, Cyan, Violet)),
                                shape = CircleShape
                            )
                            .padding(3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BgDark, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("FG", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
                        }
                    }
                }

                Text(
                    "FACEGATE",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    letterSpacing = 5.sp
                )
                Text(
                    "Camera Access System",
                    color = Cyan.copy(0.7f),
                    fontSize = 12.sp
                )

                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(1.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Violet, Pink, Color.Transparent)))
                )

                Spacer(Modifier.height(4.dp))

                // Credits cards
                AdminLinkCard(
                    label = "Admin",
                    handle = "@facegateofficial",
                    description = "Official FaceGate Admin",
                    url = "https://t.me/facegateofficial",
                    accentColor = Violet
                )

                AdminLinkCard(
                    label = "Bot",
                    handle = "@facegateofficialbot",
                    description = "Official FaceGate Bot",
                    url = "https://t.me/facegateofficialbot",
                    accentColor = Cyan
                )

                AdminLinkCard(
                    label = "Channel",
                    handle = "@facegateupdates",
                    description = "FaceGate Updates Channel",
                    url = "https://t.me/facegateupdates",
                    accentColor = GreenOk
                )

                Spacer(Modifier.height(12.dp))

                // Version chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x1AFFFFFF))
                        .border(1.dp, Border, RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        "FaceGate v2.0 — Secure",
                        color = TextMid,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AdminLinkCard(
    label: String,
    handle: String,
    description: String,
    url: String,
    accentColor: Color
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0F1624))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .clickable {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Telegram round icon
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF2CA5E0), Color(0xFF1A8AC4))),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Telegram paper-plane icon drawn with canvas
                Canvas(modifier = Modifier.size(24.dp)) {
                    val w = size.width
                    val h = size.height
                    // Telegram plane shape
                    val planePath = Path().apply {
                        moveTo(w * 0.08f, h * 0.48f)
                        lineTo(w * 0.92f, h * 0.14f)
                        lineTo(w * 0.58f, h * 0.86f)
                        lineTo(w * 0.36f, h * 0.62f)
                        close()
                    }
                    drawPath(planePath, Color.White)
                    drawLine(Color.White.copy(0.6f), Offset(w * 0.36f, h * 0.62f), Offset(w * 0.92f, h * 0.14f), 1.5f)
                }
            }

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        label.uppercase(),
                        color = accentColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    handle,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    description,
                    color = TextMid,
                    fontSize = 11.sp
                )
            }

            Text("›", color = accentColor, fontSize = 20.sp)
        }
    }
}

// ─── Shared helpers ───────────────────────────────────────────────────────────
@Composable
fun AppIconCircle(app: AppInfo, size: Dp, cornerRadius: Dp = 14.dp) {
    val icon = app.icon
    if (icon != null) {
        val bitmap = remember(app.packageName) { drawableToBitmap(icon) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.appName,
                modifier = Modifier.size(size).clip(RoundedCornerShape(cornerRadius))
            )
            return
        }
    }
    val fallbackColor = Color(hashColor(app.packageName))
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(fallbackColor),
        contentAlignment = Alignment.Center
    ) {
        Text(app.initials, color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.33f).sp)
    }
}

fun drawableToBitmap(drawable: Drawable): Bitmap? {
    return try {
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bmp
    } catch (_: Exception) { null }
}

private fun hashColor(pkg: String): Int {
    val colors = intArrayOf(
        0xFF6C63FF.toInt(), 0xFFFF4D9D.toInt(), 0xFF4ADE80.toInt(),
        0xFF2CA5E0.toInt(), 0xFFFF0000.toInt(), 0xFF25D366.toInt(),
        0xFF1877F2.toInt(), 0xFF5865F2.toInt(), 0xFFFC00.toInt(),
        0xFF010101.toInt(), 0xFF2D8CFF.toInt(), 0xFF00897B.toInt()
    )
    return colors[Math.abs(pkg.hashCode()) % colors.size]
}

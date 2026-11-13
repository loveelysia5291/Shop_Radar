package com.aykkdk.shop

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    onNavigateToTab: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    val accountStore = remember { AccountStore.get(context) }
    var username by remember { mutableStateOf(accountStore.getUsername()) }
    var email by remember { mutableStateOf(accountStore.getEmail()) }
    var isLoggedIn by remember { mutableStateOf(accountStore.isLoggedIn()) }
    var role by remember { mutableStateOf(accountStore.getRole()) }
    var serverUrl by remember { mutableStateOf(accountStore.getServerUrl()) }
    var fcmToken by remember { mutableStateOf(accountStore.getFcmToken()) }
    var lastSyncTime by remember { mutableStateOf(accountStore.getLastSyncTime()) }

    var showAuthDialog by remember { mutableStateOf(false) }
    var showServerUrlDialog by remember { mutableStateOf(false) }
    var showDeployGuideDialog by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var testingPush by remember { mutableStateOf(false) }

    val notificationsEnabled = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(context, "已開啟通知權限", Toast.LENGTH_SHORT).show()
        }
    }

    // 初始化自動取得 FCM Device Token，僅在已登入帳號時進行背景推播同步
    LaunchedEffect(Unit) {
        FcmTokenManager.fetchToken(context) { token ->
            if (!token.isNullOrBlank()) {
                fcmToken = token
            }
            if (accountStore.isLoggedIn()) {
                scope.launch {
                    accountStore.uploadFcmToken(token.orEmpty())
                    accountStore.syncToCloud(context)
                }
            }
            username = accountStore.getUsername()
            email = accountStore.getEmail()
            isLoggedIn = accountStore.isLoggedIn()
            role = accountStore.getRole()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 頂部個人帳號與推播概況卡片
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isLoggedIn) {
                                            Brush.linearGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.colorScheme.tertiary
                                                )
                                            )
                                        } else {
                                            Brush.linearGradient(
                                                listOf(
                                                    Color(0xFF64748B),
                                                    Color(0xFF475569)
                                                )
                                            )
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = "個人帳號",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Spacer(Modifier.width(16.dp))

                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (isLoggedIn) username else "訪客模式 (未登入)",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = when {
                                            !isLoggedIn -> Color(0xFFF1F5F9)
                                            role == "low" -> Color(0xFFFEF3C7)
                                            else -> Color(0xFFDCFCE7)
                                        }
                                    ) {
                                        Text(
                                            text = when {
                                                !isLoggedIn -> "推播未啟用"
                                                role == "low" -> "60分鐘 (低權限)"
                                                else -> "30分鐘 (標準)"
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                !isLoggedIn -> Color(0xFF64748B)
                                                role == "low" -> Color(0xFFB45309)
                                                else -> Color(0xFF15803D)
                                            }
                                        )
                                    }
                                }

                                Spacer(Modifier.height(4.dp))

                                Text(
                                    text = if (isLoggedIn) {
                                        if (email.isNotBlank()) "信箱：$email (雲端推播運作中)" else "已綁定專屬帳號，24小時自動監控"
                                    } else {
                                        "雲端推播已暫停，請登入專屬帳號以啟動服務"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isLoggedIn) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFE11D48)
                                )
                            }
                        }

                        if (!isLoggedIn) {
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { showAuthDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Security, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("立即註冊 / 登入帳號以啟動推播服務", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(14.dp))

                        // 操作列：一鍵同步與測試推播
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!isLoggedIn) {
                                        Toast.makeText(context, "請先登入帳號以啟用雲端推播與資料同步", Toast.LENGTH_SHORT).show()
                                        showAuthDialog = true
                                        return@Button
                                    }
                                    scope.launch {
                                        syncing = true
                                        val res = accountStore.syncToCloud(context)
                                        syncing = false
                                        res.onSuccess {
                                            lastSyncTime = System.currentTimeMillis()
                                            role = it.role
                                            Toast.makeText(
                                                context,
                                                "同步完成！(${it.checkFrequency}，訂閱:${it.subscriptionCount}, 競拍:${it.auctionCount}, 物流:${it.logisticsCount})",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }.onFailure {
                                            Toast.makeText(context, "同步失敗: ${it.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !syncing,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (syncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("同步中...", fontSize = 13.sp)
                                } else {
                                    Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("一鍵同步", fontSize = 13.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    if (!isLoggedIn) {
                                        Toast.makeText(context, "請先登入帳號以啟用雲端推播測試", Toast.LENGTH_SHORT).show()
                                        showAuthDialog = true
                                        return@OutlinedButton
                                    }
                                    scope.launch {
                                        testingPush = true
                                        val res = accountStore.sendTestPush()
                                        testingPush = false
                                        res.onSuccess {
                                            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                                        }.onFailure {
                                            Toast.makeText(context, "測試推播失敗: ${it.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !testingPush,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (testingPush) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("發送中...", fontSize = 13.sp)
                                } else {
                                    Icon(Icons.Default.NotificationsActive, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("測試推播", fontSize = 13.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (lastSyncTime > 0) "上次同步: " + SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN).format(Date(lastSyncTime))
                                else "尚未同步過資料",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (isLoggedIn) {
                                TextButton(
                                    onClick = {
                                        accountStore.logout()
                                        isLoggedIn = false
                                        username = ""
                                        email = ""
                                        role = "standard"
                                        Toast.makeText(context, "已登出帳號，雲端推播已暫停", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("登出帳號", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                }
                            } else {
                                TextButton(onClick = { showAuthDialog = true }) {
                                    Text("登入專屬帳號", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 雲端伺服器與 FCM 推播狀態卡片
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudQueue, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Cloudflare 伺服器與推播設定", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        Spacer(Modifier.height(14.dp))

                        // 伺服器端點
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { showServerUrlDialog = true }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("伺服器網址 (Cloudflare Worker)", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                Text(
                                    serverUrl,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { showServerUrlDialog = true }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Edit, "修改網址", Modifier.size(16.dp))
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 開啟雲端管理後台 (Web Admin) 按鈕
                        OutlinedButton(
                            onClick = { uriHandler.openUri("$serverUrl/admin") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.AdminPanelSettings, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("開啟雲端管理後台 (Web Admin)", fontSize = 13.sp)
                        }

                        Spacer(Modifier.height(10.dp))

                        // FCM Token 狀態
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Firebase Cloud Messaging (FCM) Token", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                Text(
                                    if (fcmToken.isNotBlank()) "${fcmToken.take(12)}...${fcmToken.takeLast(10)}" else "尚未取得 Token",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (fcmToken.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(fcmToken))
                                        Toast.makeText(context, "已複製 FCM Token", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, "複製 Token", Modifier.size(16.dp))
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 通知權限狀態
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Android 系統通知權限", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                Text(
                                    if (notificationsEnabled) "已授權通知接收 ✅" else "尚未開啟通知權限 ⚠️",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (notificationsEnabled) Color(0xFF15803D) else MaterialTheme.colorScheme.error
                                )
                            }
                            if (!notificationsEnabled) {
                                TextButton(onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        context.startActivity(intent)
                                    }
                                }) {
                                    Text("開啟權限", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 高安全性與密碼防護說明卡片
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("高安全性與防護機制", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            "• 本機零密碼殘留：手機 SharedPreferences 絕不儲存任何明文密碼，僅保留受保護的授權 Token。\n" +
                            "• 伺服器加鹽加密：採用 PBKDF2 10 萬次迭代加鹽雜湊 (Salt + PBKDF2-SHA256)，彩虹表與字典攻擊無法破解。\n" +
                            "• 防暴力破解鎖定：連續密碼錯誤 5 次自動鎖定 15 分鐘，全面防止撞庫與暴力猜測。\n" +
                            "• 智能頻率分級：一般與管理員帳號每 30 分鐘巡查，低權限帳號自動間隔 60 分鐘巡查，大幅降低被防護系統查封風險。\n" +
                            "• 開箱即用：每支安裝的手機均自動生成專屬識別碼註冊連線，直接享有 FCM 推播。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // 關於購物雷達軟體資訊卡片
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("關於購物雷達", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("應用程式名稱", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("購物雷達 (Shop Radar)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("版本編號", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("v1.28 (Build 29)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("推播架構", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Cloudflare Edge + Google FCM", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("簽名憑證確認", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("CN=aykkdkdoeoco", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF15803D))
                        }
                    }
                }
            }
        }
    }

    // 登入 / 註冊 Dialog
    if (showAuthDialog) {
        AuthDialog(
            currentServerUrl = serverUrl,
            onDismiss = { showAuthDialog = false },
            onAuthSuccess = { newUsername, newEmail, newUrl, newRole ->
                username = newUsername
                email = newEmail
                isLoggedIn = true
                serverUrl = newUrl
                role = newRole
                showAuthDialog = false
                Toast.makeText(context, "歡迎，$newUsername！已成功登入並啟動雲端推播", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val syncRes = accountStore.syncToCloud(context)
                    syncRes.onSuccess { lastSyncTime = System.currentTimeMillis() }
                }
            }
        )
    }

    // 修改伺服器網址 Dialog
    if (showServerUrlDialog) {
        ServerUrlEditDialog(
            initialUrl = serverUrl,
            onDismiss = { showServerUrlDialog = false },
            onSave = { newUrl ->
                serverUrl = newUrl
                accountStore.setServerUrl(newUrl)
                showServerUrlDialog = false
                Toast.makeText(context, "伺服器網址已更新", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Cloudflare 架設說明 Dialog
    if (showDeployGuideDialog) {
        DeployGuideDialog(onDismiss = { showDeployGuideDialog = false })
    }
}

@Composable
private fun AuthDialog(
    currentServerUrl: String,
    onDismiss: () -> Unit,
    onAuthSuccess: (username: String, email: String, serverUrl: String, role: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountStore = remember { AccountStore.get(context) }

    var isRegisterMode by remember { mutableStateOf(false) }
    var inputEmail by remember { mutableStateOf("") }
    var inputUsername by remember { mutableStateOf("") }
    var inputPassword by remember { mutableStateOf("") }
    var inputCaptchaAnswer by remember { mutableStateOf("") }
    var captchaId by remember { mutableStateOf("") }
    var captchaQuestion by remember { mutableStateOf("") }
    var captchaLoading by remember { mutableStateOf(false) }

    var inputServerUrl by remember { mutableStateOf(currentServerUrl) }
    var selectedRole by remember { mutableStateOf("standard") } // "standard" (30分) 或 "low" (60分)
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }

    fun loadCaptcha() {
        scope.launch {
            captchaLoading = true
            accountStore.fetchCaptcha(inputServerUrl).onSuccess {
                captchaId = it.captchaId
                captchaQuestion = it.question
            }.onFailure {
                captchaQuestion = "取得驗證碼失敗，點此重新取得"
            }
            captchaLoading = false
        }
    }

    LaunchedEffect(isRegisterMode) {
        if (isRegisterMode && captchaId.isBlank()) {
            loadCaptcha()
        }
    }

    if (showForgotPasswordDialog) {
        ForgotPasswordDialog(
            currentServerUrl = inputServerUrl,
            onDismiss = { showForgotPasswordDialog = false },
            onSuccess = {
                showForgotPasswordDialog = false
                isRegisterMode = false
                errorMessage = null
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isRegisterMode) "信箱註冊專屬帳號" else "登入專屬帳號",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 切換 登入 / 註冊
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(
                        onClick = { isRegisterMode = false; errorMessage = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (!isRegisterMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Text("登入", fontWeight = if (!isRegisterMode) FontWeight.Bold else FontWeight.Normal)
                    }
                    TextButton(
                        onClick = {
                            isRegisterMode = true
                            errorMessage = null
                            if (captchaId.isBlank()) loadCaptcha()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (isRegisterMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Text("信箱註冊", fontWeight = if (isRegisterMode) FontWeight.Bold else FontWeight.Normal)
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (isRegisterMode) {
                    // 電子信箱
                    OutlinedTextField(
                        value = inputEmail,
                        onValueChange = { inputEmail = it },
                        label = { Text("電子信箱 (Email)") },
                        placeholder = { Text("example@gmail.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Email, null) }
                    )

                    Spacer(Modifier.height(10.dp))

                    // 使用者名稱
                    OutlinedTextField(
                        value = inputUsername,
                        onValueChange = { inputUsername = it },
                        label = { Text("使用者名稱") },
                        placeholder = { Text("3 ~ 30 字元") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Person, null) }
                    )

                    Spacer(Modifier.height(10.dp))

                    // 密碼
                    OutlinedTextField(
                        value = inputPassword,
                        onValueChange = { inputPassword = it },
                        label = { Text("密碼") },
                        placeholder = { Text("至少 6 碼安全密碼") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        }
                    )

                    Spacer(Modifier.height(10.dp))

                    // 巡查權限選擇
                    Text("選擇巡查權限等級：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedRole == "standard",
                            onClick = { selectedRole = "standard" },
                            label = { Text("標準 (每30分鐘)") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedRole == "low",
                            onClick = { selectedRole = "low" },
                            label = { Text("低權限 (每1小時)") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // 人機驗證卡片
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (captchaLoading) "載入驗證碼中..." else captchaQuestion.ifBlank { "點此載入題目" },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            IconButton(onClick = { loadCaptcha() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Refresh, "換一題", Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = inputCaptchaAnswer,
                        onValueChange = { inputCaptchaAnswer = it },
                        label = { Text("人機驗證答案") },
                        placeholder = { Text("請輸入上述計算答案") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.CheckCircle, null) }
                    )
                } else {
                    // 登入模式
                    OutlinedTextField(
                        value = inputUsername,
                        onValueChange = { inputUsername = it },
                        label = { Text("帳號 或 電子信箱") },
                        placeholder = { Text("請輸入使用者名稱或 Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Person, null) }
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = inputPassword,
                        onValueChange = { inputPassword = it },
                        label = { Text("密碼") },
                        placeholder = { Text("請輸入密碼") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showForgotPasswordDialog = true }) {
                            Text("忘記密碼？", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 伺服器網址
                OutlinedTextField(
                    value = inputServerUrl,
                    onValueChange = { inputServerUrl = it },
                    label = { Text("Cloudflare Worker 網址") },
                    placeholder = { Text("https://shop-radar-worker.xxx.workers.dev") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    leadingIcon = { Icon(Icons.Default.Dns, null) }
                )

                errorMessage?.let { err ->
                    Spacer(Modifier.height(10.dp))
                    Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (isRegisterMode) {
                                if (inputEmail.isBlank() || inputUsername.isBlank() || inputPassword.isBlank()) {
                                    errorMessage = "請填寫完整信箱、帳號與密碼"
                                    return@Button
                                }
                                if (inputCaptchaAnswer.isBlank()) {
                                    errorMessage = "請完成人機驗證"
                                    return@Button
                                }
                                if (inputPassword.length < 6) {
                                    errorMessage = "密碼長度需至少 6 個字元"
                                    return@Button
                                }
                            } else {
                                if (inputUsername.isBlank() || inputPassword.isBlank()) {
                                    errorMessage = "請填寫完整帳號 (或信箱) 與密碼"
                                    return@Button
                                }
                            }
                            if (inputServerUrl.isBlank()) {
                                errorMessage = "請填寫 Cloudflare Worker 伺服器網址"
                                return@Button
                            }
                            scope.launch {
                                loading = true
                                errorMessage = null
                                val result = if (isRegisterMode) {
                                    accountStore.register(
                                        email = inputEmail,
                                        username = inputUsername,
                                        pass = inputPassword,
                                        captchaId = captchaId,
                                        captchaAnswer = inputCaptchaAnswer,
                                        serverUrl = inputServerUrl,
                                        role = selectedRole
                                    )
                                } else {
                                    accountStore.login(inputUsername, inputPassword, inputServerUrl)
                                }
                                loading = false
                                result.onSuccess {
                                    onAuthSuccess(
                                        accountStore.getUsername(),
                                        accountStore.getEmail(),
                                        inputServerUrl.trim(),
                                        accountStore.getRole()
                                    )
                                }.onFailure {
                                    errorMessage = it.message ?: "操作失敗，請確認伺服器連線"
                                    if (isRegisterMode) loadCaptcha()
                                }
                            }
                        },
                        enabled = !loading,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("處理中...")
                        } else {
                            Text(if (isRegisterMode) "立即註冊並啟用推播" else "立即登入並啟用推播")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForgotPasswordDialog(
    currentServerUrl: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountStore = remember { AccountStore.get(context) }

    var step by remember { mutableStateOf(1) } // 1: 申請驗證碼, 2: 輸入驗證碼與新密碼
    var email by remember { mutableStateOf("") }
    var captchaId by remember { mutableStateOf("") }
    var captchaQuestion by remember { mutableStateOf("") }
    var captchaAnswer by remember { mutableStateOf("") }
    var captchaLoading by remember { mutableStateOf(false) }

    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var newPassVisible by remember { mutableStateOf(false) }

    var devCodeHint by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadCaptcha() {
        scope.launch {
            captchaLoading = true
            accountStore.fetchCaptcha(currentServerUrl).onSuccess {
                captchaId = it.captchaId
                captchaQuestion = it.question
            }.onFailure {
                captchaQuestion = "取得驗證碼失敗，點擊重新取得"
            }
            captchaLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadCaptcha()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (step == 1) "忘記密碼 - 驗證信箱" else "重設全新密碼",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (step == 1) {
                    Text(
                        "請輸入您註冊的電子信箱並完成人機驗證，系統將生成 6 位數重設驗證碼。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("註冊電子信箱 (Email)") },
                        placeholder = { Text("example@gmail.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Email, null) }
                    )

                    Spacer(Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (captchaLoading) "載入驗證碼中..." else captchaQuestion.ifBlank { "點此載入題目" },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            IconButton(onClick = { loadCaptcha() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Refresh, "換一題", Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = captchaAnswer,
                        onValueChange = { captchaAnswer = it },
                        label = { Text("人機驗證答案") },
                        placeholder = { Text("請輸入計算結果數字") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.CheckCircle, null) }
                    )
                } else {
                    Text(
                        "重設驗證碼已生成（10分鐘內有效），請輸入驗證碼並設定您的全新密碼。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    devCodeHint?.let { codeHint ->
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "【獨立伺服器提示】重設驗證碼為：$codeHint",
                                modifier = Modifier.padding(10.dp),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("6 位數重設驗證碼") },
                        placeholder = { Text("請輸入 6 碼數字") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.CheckCircle, null) }
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("全新密碼") },
                        placeholder = { Text("至少 6 碼新密碼") },
                        singleLine = true,
                        visualTransformation = if (newPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { newPassVisible = !newPassVisible }) {
                                Icon(if (newPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        }
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("再次確認全新密碼") },
                        placeholder = { Text("請再次輸入新密碼") },
                        singleLine = true,
                        visualTransformation = if (newPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Lock, null) }
                    )
                }

                errorMessage?.let { err ->
                    Spacer(Modifier.height(10.dp))
                    Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (step == 1) {
                                if (email.isBlank() || captchaAnswer.isBlank()) {
                                    errorMessage = "請填寫信箱與人機驗證答案"
                                    return@Button
                                }
                                scope.launch {
                                    loading = true
                                    errorMessage = null
                                    accountStore.requestPasswordReset(email, captchaId, captchaAnswer, currentServerUrl)
                                        .onSuccess {
                                            loading = false
                                            devCodeHint = it.devCode
                                            if (!it.devCode.isNullOrBlank()) {
                                                code = it.devCode
                                            }
                                            step = 2
                                        }.onFailure {
                                            loading = false
                                            errorMessage = it.message ?: "申請失敗，請確認信箱是否已註冊"
                                            loadCaptcha()
                                        }
                                }
                            } else {
                                if (code.isBlank() || newPassword.isBlank()) {
                                    errorMessage = "請填寫驗證碼與新密碼"
                                    return@Button
                                }
                                if (newPassword.length < 6) {
                                    errorMessage = "新密碼長度需至少 6 個字元"
                                    return@Button
                                }
                                if (newPassword != confirmPassword) {
                                    errorMessage = "兩次輸入的新密碼不相符"
                                    return@Button
                                }
                                scope.launch {
                                    loading = true
                                    errorMessage = null
                                    accountStore.resetPassword(email, code, newPassword, currentServerUrl)
                                        .onSuccess {
                                            loading = false
                                            Toast.makeText(context, "密碼已重設成功！請使用新密碼登入", Toast.LENGTH_LONG).show()
                                            onSuccess()
                                        }.onFailure {
                                            loading = false
                                            errorMessage = it.message ?: "重設失敗，請確認驗證碼是否正確"
                                        }
                                }
                            }
                        },
                        enabled = !loading,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("處理中...")
                        } else {
                            Text(if (step == 1) "取得重設驗證碼" else "確認重設密碼")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerUrlEditDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var urlText by remember { mutableStateOf(initialUrl) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("設定 Cloudflare Worker 網址", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("請輸入您在 Cloudflare 部署的 Worker 網址：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Worker URL") },
                    placeholder = { Text("https://shop-radar-worker.xxx.workers.dev") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(18.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(urlText.trim()) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("儲存")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeployGuideDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.92f).heightIn(max = 680.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cloudflare Worker 架設指南", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                }

                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "架設 Cloudflare Worker 完全免費，伺服器在雲端 24 小時定時輪詢監控並透過 Google FCM 發送推播，徹底解決手機被殺後台問題！",
                                modifier = Modifier.padding(12.dp),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    item {
                        StepItem(
                            stepNumber = "1",
                            title = "前往 Cloudflare 建立 Worker",
                            description = "登入 https://www.cloudflare.com/zh-tw/，點選「Workers 和 Pages」->「建立 Worker」，名稱可取名為 shop-radar-worker。"
                        )
                    }

                    item {
                        StepItem(
                            stepNumber = "2",
                            title = "貼入專案程式碼並部署",
                            description = "點選「編輯代碼 (Edit code)」，將專案中 cloudflare-worker/src/index.js 的程式碼全部複製貼入並點擊「儲存並部署」。"
                        )
                    }

                    item {
                        StepItem(
                            stepNumber = "3",
                            title = "設定定時觸發程序 (Cron Triggers)",
                            description = "在 Worker 的「觸發程序 (Triggers)」中，新增 Cron 觸發程序並填入 */30 * * * *（每 30 分鐘自動監控巡查一次，低權限帳號自動間隔 60 分鐘）。"
                        )
                    }

                    item {
                        StepItem(
                            stepNumber = "4",
                            title = "建立並綁定 KV 資料表",
                            description = "在 Cloudflare 的「儲存和資料庫 -> KV」建立一個名稱為 RADAR_KV 的命名空間，並回到 Worker 的「設定 -> 變數 -> KV 命名空間繫結」綁定為 RADAR_KV。"
                        )
                    }

                    item {
                        StepItem(
                            stepNumber = "5",
                            title = "設定 Firebase FCM 伺服器金鑰",
                            description = "在 Firebase 控制台的「專案設定 -> Cloud Messaging」取得 Server Key，並在 Cloudflare Worker 的「設定 -> 變數 -> 環境變數」新增 FCM_SERVER_KEY 變數。"
                        )
                    }

                    item {
                        StepItem(
                            stepNumber = "6",
                            title = "開啟後台管理系統",
                            description = "瀏覽網址 /admin 即可開啟登入後台，視覺化查看所有連線手機與調整 30 分鐘 / 1 小時權限。"
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { uriHandler.openUri("https://www.cloudflare.com/zh-tw/") }) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("開啟 Cloudflare 官網", fontSize = 13.sp)
                    }
                    Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                        Text("我知道了")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(stepNumber: String, title: String, description: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(stepNumber, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
        }
    }
}

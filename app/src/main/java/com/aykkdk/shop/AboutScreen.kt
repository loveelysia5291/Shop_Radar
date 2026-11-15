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
    var isMaintenance by remember { mutableStateOf(accountStore.isMaintenanceMode()) }
    var maintenanceMessage by remember { mutableStateOf(accountStore.getMaintenanceMessage()) }

    var showAuthDialog by remember { mutableStateOf(false) }
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }

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
        scope.launch {
            accountStore.checkServerStatus(context)
            isMaintenance = accountStore.isMaintenanceMode()
            maintenanceMessage = accountStore.getMaintenanceMessage()
        }
        FcmTokenManager.fetchToken(context) { token ->
            if (!token.isNullOrBlank()) {
                fcmToken = token
            }
            if (accountStore.isLoggedIn()) {
                scope.launch {
                    accountStore.uploadFcmToken(token.orEmpty())
                    accountStore.syncToCloud(context)
                    isMaintenance = accountStore.isMaintenanceMode()
                    maintenanceMessage = accountStore.getMaintenanceMessage()
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
                                                role == "low" -> "普通會員"
                                                else -> "VIP 會員"
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isLoggedIn) {
                                    if (isMaintenance) {
                                        if (maintenanceMessage.isNotBlank()) "⚠️ 伺服器維護中 ($maintenanceMessage) - 已暫停回傳"
                                        else "⚠️ 伺服器維護中 (已暫停資料回傳)"
                                    } else "雲端即時同步中 (訂閱變更自動同步)"
                                } else "尚未登入帳號",
                                fontSize = 12.sp,
                                color = if (isLoggedIn && isMaintenance) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                            )
                            if (isLoggedIn) {
                                TextButton(
                                    onClick = { showLogoutConfirmDialog = true }
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

            // 雲端伺服器與推播設定卡片
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudQueue, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("雲端推播服務", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        Spacer(Modifier.height(14.dp))

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
                Toast.makeText(context, "歡迎，$newUsername！正在從雲端同步還原商品...", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val restoreRes = accountStore.restoreFromCloud(context)
                    restoreRes.onSuccess {
                        Toast.makeText(context, "已成功同步並還原雲端訂閱與競標！", Toast.LENGTH_SHORT).show()
                    }
                    lastSyncTime = System.currentTimeMillis()
                }
            }
        )
    }

    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            title = { Text("確認登出帳號？") },
            text = { Text("確定要登出此帳號嗎？\n登出後將清除本機暫存的所有訂閱與競拍紀錄。下次登入專屬帳號時，將會從雲端自動同步恢復所有商品。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirmDialog = false
                    ProductSubscriptionStore(context).clearAll()
                    AuctionStore(context).clearAll()
                    context.getSharedPreferences("shop_subscriptions_v2", Context.MODE_PRIVATE).edit().clear().apply()
                    accountStore.logout()
                    isLoggedIn = false
                    username = ""
                    email = ""
                    role = "standard"
                    Toast.makeText(context, "已登出帳號，本機訂閱與競拍紀錄已清除", Toast.LENGTH_SHORT).show()
                }) {
                    Text("確定登出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
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

    var inputServerUrl by remember { mutableStateOf(currentServerUrl) }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }

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
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🎁", fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "首次註冊贈送 7 天 VIP 高頻巡查！(已自動綁定本裝置)",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
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
                            scope.launch {
                                loading = true
                                errorMessage = null
                                val result = if (isRegisterMode) {
                                    accountStore.register(
                                        email = inputEmail,
                                        username = inputUsername,
                                        pass = inputPassword,
                                        serverUrl = inputServerUrl
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

private enum class ForgotStep {
    REQUEST_CODE,
    VERIFY_AND_RESET,
    ADMIN_HELP_DONE,
    RESET_SUCCESS
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

    var step by remember { mutableStateOf(ForgotStep.REQUEST_CODE) }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("") }

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
                        when (step) {
                            ForgotStep.REQUEST_CODE -> "忘記密碼"
                            ForgotStep.VERIFY_AND_RESET -> "輸入驗證碼並設定密碼"
                            ForgotStep.ADMIN_HELP_DONE -> "求助請求已送出"
                            ForgotStep.RESET_SUCCESS -> "密碼重設成功"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                Spacer(Modifier.height(10.dp))

                when (step) {
                    ForgotStep.REQUEST_CODE -> {
                        Text(
                            "請輸入註冊的電子信箱以寄送 6 位數驗證碼；若電子信箱無法收到郵件，亦可直接點選「向管理員求助」。",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
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

                        errorMessage?.let { err ->
                            Spacer(Modifier.height(10.dp))
                            Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    if (email.isBlank() || !email.contains("@")) {
                                        errorMessage = "請先填寫註冊電子信箱再向管理員求助"
                                        return@TextButton
                                    }
                                    scope.launch {
                                        loading = true
                                        errorMessage = null
                                        accountStore.requestAdminPasswordReset(email.trim(), currentServerUrl)
                                            .onSuccess {
                                                loading = false
                                                statusMessage = it
                                                step = ForgotStep.ADMIN_HELP_DONE
                                            }.onFailure {
                                                loading = false
                                                errorMessage = it.message ?: "向管理員求助失敗"
                                            }
                                    }
                                },
                                enabled = !loading
                            ) {
                                Text("🆘 向管理員求助", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            }

                            Row {
                                TextButton(onClick = onDismiss) { Text("取消") }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (email.isBlank() || !email.contains("@")) {
                                            errorMessage = "請填寫正確格式的註冊電子信箱"
                                            return@Button
                                        }
                                        scope.launch {
                                            loading = true
                                            errorMessage = null
                                            accountStore.requestPasswordReset(email.trim(), currentServerUrl)
                                                .onSuccess {
                                                    loading = false
                                                    statusMessage = it
                                                    step = ForgotStep.VERIFY_AND_RESET
                                                }.onFailure {
                                                    loading = false
                                                    errorMessage = it.message ?: "寄送驗證碼失敗"
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
                                        Text("📧 寄送驗證碼")
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                if (email.isBlank() || !email.contains("@")) {
                                    errorMessage = "請先填寫註冊電子信箱"
                                } else {
                                    errorMessage = null
                                    step = ForgotStep.VERIFY_AND_RESET
                                }
                            }) {
                                Text("已有驗證碼？直接設定新密碼", fontSize = 12.sp)
                            }
                        }
                    }

                    ForgotStep.VERIFY_AND_RESET -> {
                        Text(
                            "請輸入信箱 ($email) 收到的 6 位數驗證碼，並設定您的新密碼：",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))

                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text("6 位數驗證碼") },
                            placeholder = { Text("請輸入 6 碼驗證碼") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            leadingIcon = { Icon(Icons.Default.Lock, null) }
                        )

                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            label = { Text("新密碼 (至少 6 碼)") },
                            placeholder = { Text("請輸入全新密碼") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            leadingIcon = { Icon(Icons.Default.Lock, null) }
                        )

                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text("再次確認新密碼") },
                            placeholder = { Text("請再次輸入全新密碼") },
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

                        errorMessage?.let { err ->
                            Spacer(Modifier.height(10.dp))
                            Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        loading = true
                                        errorMessage = null
                                        accountStore.requestAdminPasswordReset(email.trim(), currentServerUrl)
                                            .onSuccess {
                                                loading = false
                                                statusMessage = it
                                                step = ForgotStep.ADMIN_HELP_DONE
                                            }.onFailure {
                                                loading = false
                                                errorMessage = it.message ?: "向管理員求助失敗"
                                            }
                                    }
                                },
                                enabled = !loading
                            ) {
                                Text("收不到驗證碼？向管理員求助", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }

                            Row {
                                TextButton(onClick = { step = ForgotStep.REQUEST_CODE }) { Text("上一步") }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (code.isBlank()) {
                                            errorMessage = "請輸入 6 位數驗證碼"
                                            return@Button
                                        }
                                        if (newPassword.length < 6) {
                                            errorMessage = "新密碼長度需至少 6 個字元"
                                            return@Button
                                        }
                                        if (newPassword != confirmPassword) {
                                            errorMessage = "兩次輸入的新密碼不一致"
                                            return@Button
                                        }
                                        scope.launch {
                                            loading = true
                                            errorMessage = null
                                            accountStore.resetPassword(email.trim(), code.trim(), newPassword.trim(), currentServerUrl)
                                                .onSuccess {
                                                    loading = false
                                                    statusMessage = it
                                                    step = ForgotStep.RESET_SUCCESS
                                                }.onFailure {
                                                    loading = false
                                                    errorMessage = it.message ?: "重設密碼失敗"
                                                }
                                        }
                                    },
                                    enabled = !loading,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (loading) {
                                        CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text("驗證中...")
                                    } else {
                                        Text("確認重設密碼")
                                    }
                                }
                            }
                        }
                    }

                    ForgotStep.ADMIN_HELP_DONE -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "求助申請已送出！",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "已成功為信箱 ($email) 向管理員登記重設密碼求助。\n\n管理員在收到求助後會直接為您重設密碼。請聯繫管理員為您設定新密碼後直接登入！",
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                                Text("我知道了")
                            }
                        }
                    }

                    ForgotStep.RESET_SUCCESS -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "密碼重設成功！",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    statusMessage.ifBlank { "您的新密碼已設定生效，請使用新密碼登入。" },
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(onClick = onSuccess, shape = RoundedCornerShape(12.dp)) {
                                Text("前往登入")
                            }
                        }
                    }
                }
            }
        }
    }
}

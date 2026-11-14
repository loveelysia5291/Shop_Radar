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
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }
    var showDeployGuideDialog by remember { mutableStateOf(false) }

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
                                text = if (isLoggedIn) "雲端即時同步中 (訂閱變更自動同步)"
                                else "尚未登入帳號",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline
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
    var selectedRole by remember { mutableStateOf("standard") } // "standard" (1小時) 或 "low" (1.5小時)
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
                            label = { Text("VIP 會員 (每 1 小時巡查)") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedRole == "low",
                            onClick = { selectedRole = "low" },
                            label = { Text("普通會員 (每 1.5 小時巡查)") },
                            modifier = Modifier.weight(1f)
                        )
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

    var email by remember { mutableStateOf("") }
    var isSubmitted by remember { mutableStateOf(false) }
    var successMsg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                        if (isSubmitted) "密碼已重設發送" else "忘記密碼 - 寄送新密碼",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (isSubmitted) {
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
                                    "新密碼已發送至您的電子信箱！",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                successMsg.ifBlank { "系統已重設一組全新密碼並寄送至您的註冊電子信箱 ($email)，請前往信箱查收並使用新密碼登入。" },
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
                        Button(
                            onClick = {
                                onSuccess()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("前往登入")
                        }
                    }
                } else {
                    Text(
                        "請輸入您註冊的電子信箱，系統將自動重設一組全新密碼並發送至您的信箱。",
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
                                            successMsg = it.message
                                            isSubmitted = true
                                        }.onFailure {
                                            loading = false
                                            errorMessage = it.message ?: "發送失敗，請確認信箱是否已註冊"
                                        }
                                }
                            },
                            enabled = !loading,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (loading) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("寄送中...")
                            } else {
                                Text("發送新密碼至信箱")
                            }
                        }
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
                            description = "在 Worker 的「觸發程序 (Triggers)」中，新增 Cron 觸發程序並填入 */30 * * * *（系統排程每 30 分鐘自動執行：VIP 會員每 1 小時巡查，普通會員每 1.5 小時巡查）。"
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

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
    var isLoggedIn by remember { mutableStateOf(accountStore.isLoggedIn()) }
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

    // 初始化自動取得 FCM Device Token
    LaunchedEffect(Unit) {
        if (fcmToken.isBlank()) {
            FcmTokenManager.fetchToken(context) { token ->
                if (!token.isNullOrBlank()) {
                    fcmToken = token
                    if (isLoggedIn) {
                        scope.launch { accountStore.uploadFcmToken(token) }
                    }
                }
            }
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
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary
                                            )
                                        )
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
                                        color = if (isLoggedIn) Color(0xFFDCFCE7) else Color(0xFFFEF3C7)
                                    ) {
                                        Text(
                                            text = if (isLoggedIn) "雲端已連線" else "離線",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isLoggedIn) Color(0xFF15803D) else Color(0xFFB45309)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(4.dp))

                                Text(
                                    text = if (isLoggedIn) "24 小時邊緣伺服器自動監控中" else "登入後即可啟用 Cloudflare 雲端推播",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(14.dp))

                        if (isLoggedIn) {
                            // 已登入操作列
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            syncing = true
                                            val res = accountStore.syncToCloud(context)
                                            syncing = false
                                            res.onSuccess {
                                                lastSyncTime = System.currentTimeMillis()
                                                Toast.makeText(
                                                    context,
                                                    "同步完成！(訂閱:${it.subscriptionCount}, 競拍:${it.auctionCount}, 物流:${it.logisticsCount})",
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

                            Spacer(Modifier.height(8.dp))

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
                                TextButton(
                                    onClick = {
                                        accountStore.logout()
                                        isLoggedIn = false
                                        username = ""
                                        Toast.makeText(context, "已登出帳號", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("登出帳號", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                }
                            }
                        } else {
                            // 未登入提示與登入按鈕
                            Text(
                                "登入 Cloudflare Worker 雲端帳號後，將由全球邊緣節點 24 小時在後台替您定時巡查跳蚤本舖最新上架商品、露天/雅虎競標超標及超商包裹貨況，並透過 Google FCM 官方推播直接喚醒手機通知欄。App 無須常駐背景，徹底省電免被系統殺後台！",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { showAuthDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Login, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("登入 / 註冊帳號")
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
                                    if (fcmToken.isNotBlank()) "${fcmToken.take(12)}...${fcmToken.takeLast(10)}" else "尚未取得 Token (點右側重新獲取)",
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
                            } else {
                                IconButton(
                                    onClick = {
                                        FcmTokenManager.fetchToken(context) { token ->
                                            if (!token.isNullOrBlank()) {
                                                fcmToken = token
                                                Toast.makeText(context, "已取得 FCM Token", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "獲取失敗，請確認網路連線", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, "重新獲取", Modifier.size(16.dp))
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

                        Spacer(Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "已強制移除 App 自己發送通知：所有背景檢查與提醒完全轉由 Cloudflare 雲端伺服器經由 FCM 發送，免除耗電與殺後台問題。",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // Cloudflare Worker 架設指南卡片
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.HelpOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Cloudflare 服務架設指南", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                            TextButton(onClick = { showDeployGuideDialog = true }) {
                                Text("詳細教學", fontSize = 13.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "本專案支援使用免費用戶額度在 https://www.cloudflare.com/ 架設專屬的監控 Worker 服務，24小時自動監控與推播。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { uriHandler.openUri("https://www.cloudflare.com/zh-tw/") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("前往 Cloudflare", fontSize = 12.sp)
                            }
                            Button(
                                onClick = { showDeployGuideDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.MenuBook, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("架設步驟說明", fontSize = 12.sp)
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
            onAuthSuccess = { newUsername, newUrl ->
                username = newUsername
                isLoggedIn = true
                serverUrl = newUrl
                showAuthDialog = false
                Toast.makeText(context, "歡迎，$newUsername！已成功登入", Toast.LENGTH_SHORT).show()
                // 自動同步一次
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
    onAuthSuccess: (username: String, serverUrl: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountStore = remember { AccountStore.get(context) }

    var isRegisterMode by remember { mutableStateOf(false) }
    var inputUsername by remember { mutableStateOf("") }
    var inputPassword by remember { mutableStateOf("") }
    var inputServerUrl by remember { mutableStateOf(currentServerUrl) }
    var passwordVisible by remember { mutableStateOf(false) }
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
                        if (isRegisterMode) "註冊 Cloudflare 帳號" else "登入 Cloudflare 帳號",
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
                        onClick = { isRegisterMode = true; errorMessage = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (isRegisterMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Text("註冊新帳號", fontWeight = if (isRegisterMode) FontWeight.Bold else FontWeight.Normal)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 帳號輸入框
                OutlinedTextField(
                    value = inputUsername,
                    onValueChange = { inputUsername = it },
                    label = { Text("使用者帳號") },
                    placeholder = { Text("請輸入帳號 (至少3碼)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    leadingIcon = { Icon(Icons.Default.Person, null) }
                )

                Spacer(Modifier.height(12.dp))

                // 密碼輸入框
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

                Spacer(Modifier.height(12.dp))

                // 伺服器網址
                OutlinedTextField(
                    value = inputServerUrl,
                    onValueChange = { inputServerUrl = it },
                    label = { Text("Cloudflare Worker 伺服器網址") },
                    placeholder = { Text("https://xxx.workers.dev") },
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
                            if (inputUsername.isBlank() || inputPassword.isBlank()) {
                                errorMessage = "請填寫完整帳號與密碼"
                                return@Button
                            }
                            if (inputServerUrl.isBlank()) {
                                errorMessage = "請填寫 Cloudflare Worker 伺服器網址"
                                return@Button
                            }
                            scope.launch {
                                loading = true
                                errorMessage = null
                                val result = if (isRegisterMode) {
                                    accountStore.register(inputUsername, inputPassword, inputServerUrl)
                                } else {
                                    accountStore.login(inputUsername, inputPassword, inputServerUrl)
                                }
                                loading = false
                                result.onSuccess {
                                    onAuthSuccess(inputUsername.trim(), inputServerUrl.trim())
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
                            Text(if (isRegisterMode) "立即註冊" else "立即登入")
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
                            description = "在 Worker 的「觸發程序 (Triggers)」中，新增 Cron 觸發程序並填入 */10 * * * *（每 10 分鐘自動監控巡查一次）。"
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
                            title = "在 App「關於」頁面登入並同步",
                            description = "複製您的 Worker 網址（例如 https://shop-radar-worker.xxx.workers.dev），在 App 輸入並註冊帳號，點擊「一鍵同步」即可開始 24 小時雲端推播！"
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

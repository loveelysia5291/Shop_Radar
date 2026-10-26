package com.aykkdk.shop

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DebugNotificationTestMenu(onOpen: () -> Unit) {
    DropdownMenuItem(text = { Text("15 分鐘背景通知測試（Debug）") }, onClick = onOpen)
}

@Composable
internal fun DebugNotificationTestDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { BackgroundDeliveryCheck.prefs(context) }
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    fun schedule() {
        error = runCatching { BackgroundDeliveryCheck.schedule(context, 15 * 60L) }
            .exceptionOrNull()?.let { "無法建立測試排程，請重試。" }.orEmpty()
        tick++
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) schedule() else error = "未允許通知，尚未開始測試。"
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val status = remember(tick) { BackgroundDeliveryCheck.deliveryText(prefs.getString("probe", null)) }
    fun time(key: String): String = prefs.getLong(key, 0).takeIf { it > 0 }
        ?.let { SimpleDateFormat("MM/dd HH:mm:ss", Locale.TAIWAN).format(Date(it)) } ?: "尚無紀錄"
    AlertDialog(onDismissRequest = onClose, title = { Text("15 分鐘背景通知測試") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("按開始後返回桌面並鎖屏，等待測試通知。這是真實 WorkManager 延遲排程，不會加速模擬時間，也不會讀取商品。")
            Text("15 分鐘是最早執行時間，省電／待機可能延後。請勿強制停止 App。再次開始會取代上一個通知測試。")
            Text("狀態：$status\n開始：${time("probeAt")}\n預定：${time("probeDueAt")}\n完成：${time("probeFinishedAt")}")
            Text(BackgroundProbeTiming.text(prefs.getLong("probeDueAt", 0), prefs.getLong("probeStartedAt", 0), System.currentTimeMillis()))
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onClose) { Text("關閉") }
            TextButton(onClick = {
                if (RefreshNotificationAccess.needsPermission(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else schedule()
            }) { Text("開始測試") }
        }
    })
}

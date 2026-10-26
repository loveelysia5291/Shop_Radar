package com.aykkdk.shop

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DebugBackgroundRefreshMenu(onOpen: () -> Unit) {
    DropdownMenuItem(text = { Text("背景刷新狀態") }, onClick = onOpen)
}

@Composable
internal fun BackgroundRefreshDialog(target: RefreshTarget? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AutomaticRefreshStore(context) }
    val log = remember { BackgroundRefreshStatus(context) }
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(store, log) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        log.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
            log.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    val plans = remember(version, target) { store.entries().filter { store.exists(it.first) && (target == null || target == it.first) } }
    var workState by remember { mutableStateOf("正在讀取排程狀態…") }
    LaunchedEffect(target, version) {
        workState = withContext(Dispatchers.IO) {
            val manager = WorkManager.getInstance(context)
            val info = runCatching {
                val id = store.prefs.getString("scheduledWorkId", null)?.let(UUID::fromString)
                if (id != null) manager.getWorkInfoById(id).get(3, TimeUnit.SECONDS)
                else manager.getWorkInfosForUniqueWork("radar-automatic-refresh").get(3, TimeUnit.SECONDS).lastOrNull { !it.state.isFinished }
            }.getOrNull()
            when (info?.state?.name) {
                "RUNNING" -> "背景工作正在執行"
                "ENQUEUED" -> "背景工作已排程，等待系統執行"
                "BLOCKED" -> "背景工作等待前一批完成"
                "FAILED" -> "背景工作失敗"
                "CANCELLED" -> "背景工作已取消"
                "SUCCEEDED" -> "這批背景工作已完成"
                else -> if (plans.isEmpty()) "沒有啟用背景自動刷新" else "尚未取得背景工作狀態"
            }
        }
    }
    fun time(at: Long) = if (at > 0) SimpleDateFormat("MM/dd HH:mm:ss", Locale.TAIWAN).format(Date(at)) else "尚無紀錄"
    AlertDialog(onDismissRequest = onClose, title = { Text("背景刷新狀態") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(workState)
            Text("啟用項目：${plans.size}")
            Text("最近背景啟動：${time(log.prefs.getLong("workerStartedAt", 0))}")
            Text("最近背景完成：${time(log.prefs.getLong("workerFinishedAt", 0))}")
            val power = context.getSystemService(android.os.PowerManager::class.java)
            if (power.isDeviceIdleMode) Text("系統目前處於 Doze 待機，可能延後背景工作。")
            if (power.isPowerSaveMode) Text("系統省電模式已啟用。")
            if (Build.VERSION.SDK_INT >= 28 && context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted)
                Text("系統限制此 App 的背景執行，請查看應用程式／電池設定。", color = MaterialTheme.colorScheme.error)
            if (store.sleep().contains(System.currentTimeMillis())) Text("目前在睡眠時段，自動刷新暫停。")
            if (target != null) {
                val plan = store.plan(target)
                Text(if (plan == null) "這個項目未啟用自動刷新；只有開啟 App 的前台刷新或手動刷新。"
                    else "刷新間隔：${plan.intervalMinutes} 分鐘\n下次可執行：${time(store.sleep().nextAwake(plan.dueAt))}")
                val record = log.read(target)
                if (record != null) {
                    val result = when (record.optString("outcome")) {
                        "reading" -> "刷新開始，尚未記錄完成"
                        "complete" -> "刷新完成"
                        "pending" -> "已讀取一批，下輪繼續比對"
                        "retry" -> "斷網，等待本輪重試"
                        "sleeping" -> "睡眠時段已略過"
                        "interrupted" -> "工作中止，待送提醒保留"
                        else -> "刷新未完成"
                    }
                    Text("本項目：$result\n開始：${time(record.optLong("startedAt"))}\n完成：${time(record.optLong("finishedAt"))}")
                    Text(when (record.optString("notification")) {
                        "notification_sent" -> "本次通知已交給 Android"
                        "notification_blocked" -> "本次通知被系統設定擋住，提醒已保留"
                        else -> "本次沒有新的待送通知"
                    })
                    if (record.optString("error").isNotBlank()) Text(record.optString("error"), color = MaterialTheme.colorScheme.error)
                }
                Text(if (RefreshNotificationAccess.enabled(context, RefreshNotificationAccess.channel(target))) "通知設定：已開啟" else "通知設定：未開啟")
            }
            Text("前台競拍每 20 秒刷新；後台依設定間隔。待機、省電、低電量時 Android 可能延後排程，即使 App 未被關閉。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val intent = if (Build.VERSION.SDK_INT >= 26)
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }) { Text("系統通知設定") }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("應用程式／電池設定") }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("關閉") } })
}

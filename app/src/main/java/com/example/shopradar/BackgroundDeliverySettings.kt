package com.example.shopradar

import android.content.Context
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** User-initiated system settings only; no hidden permissions or automatic OS changes. */
private fun openBackgroundSetting(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent); true }.getOrElse {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
            true
        }.getOrDefault(false)
    }

@Composable
internal fun BackgroundDeliverySettingsDialog(onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("背景通知設定") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            BackgroundDeliverySettingsContent()
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("關閉") } })
}

/** Shared by the card's fixed-size settings panel and the app settings dialog. */
@Composable
internal fun BackgroundDeliverySettingsContent() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var unavailable by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf(BackgroundDeliveryCheck.statusText(context)) }
    var scheduleHealth by remember { mutableStateOf("正在確認 Android 排程…") }
    LaunchedEffect(context, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Reconcile missing/obsolete persisted jobs when returning from system settings.
            AutomaticRefreshScheduler.initialize(context)
            while (true) {
                health = BackgroundDeliveryCheck.statusText(context)
                scheduleHealth = BackgroundDeliveryCheck.scheduleText(context)
                delay(2000)
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) BackgroundDeliveryCheck.schedule(context)
    }
    fun open(intent: Intent) { unavailable = !openBackgroundSetting(context, intent) }
        Column(Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("請先為需要追蹤的項目開啟「自動刷新」。本機按設定間隔檢查後才會通知，不是平台即時推送；睡眠模式期間不執行。")
            Text(BackgroundSettingsGuide.instructions(Build.MANUFACTURER))
            Text(health, style = MaterialTheme.typography.bodySmall)
            Text(scheduleHealth, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                if (RefreshNotificationAccess.needsPermission(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else BackgroundDeliveryCheck.schedule(context)
                health = BackgroundDeliveryCheck.statusText(context)
            }) { Text("測試背景通知（20 秒後）") }
            Text("按下測試後切到背景。這只測試 Android 排程與通知，不刷新商品；20 秒為最早執行時間，系統可能延後。", style = MaterialTheme.typography.bodySmall)
            Text("如果通知只在開啟 App 時出現，請檢查系統是否限制背景執行、背景網路與省電。可自行決定是否放寬本 App 的電池限制（可能增加耗電）；App 不會自動變更設定。強制停止後需手動開啟 App，排程不保證準點。")
            TextButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("檢查系統電池最佳化") }
            TextButton(onClick = {
                open(if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("通知設定") }
            if (BackgroundSettingsGuide.xiaomi(Build.MANUFACTURER)) TextButton(onClick = {
                open(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
            }) { Text("小米背景自啟動設定") }
            TextButton(onClick = {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("應用程式資訊／通知與網路") }
            if (unavailable) Text("無法開啟設定，請由手機設定手動調整。", color = MaterialTheme.colorScheme.error)
        }
}

package com.example.shopradar

import android.app.TimePickerDialog
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.ui.graphics.graphicsLayer
import java.util.Locale

// Pager keeps adjacent pages composed. Only the visible page may consume Back.
internal val LocalCardBackActive = compositionLocalOf { true }

@Composable
internal fun AutomaticRefreshMenu(target: RefreshTarget, iconModifier: Modifier = Modifier, compactFeedback: Boolean = false,
    inCard: Boolean = false, header: @Composable (@Composable () -> Unit) -> Unit = { it() },
    extra: @Composable (onDismiss: () -> Unit) -> Unit = { }) {
    val context = LocalContext.current
    val store = remember(context) { AutomaticRefreshStore(context) }
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(store, target) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == target.key) version++ }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val plan = remember(version, target) { store.plan(target) }
    var menu by remember(target) { mutableStateOf(false) }
    var configure by remember(target) { mutableStateOf(false) }
    var interval by remember(target) { mutableStateOf("30") }
    var diagnostic by remember(target) { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var backgroundSettings by remember { mutableStateOf(false) }
    val backActive = LocalCardBackActive.current
    LaunchedEffect(backActive) {
        if (!backActive) { menu = false; configure = false; backgroundSettings = false }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionDenied = !it }
    // The visual glyph is 28dp tall; align its center to the first caption line,
    // while its 48dp touch target stays separate from the text, including at larger font scales.
    val captionIconOffset = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2f } - 14.dp
    val menuRotation by animateFloatAsState(if (menu || configure || backgroundSettings) 90f else 0f, tween(240), label = "cardMenuRotation")
    val menuButton: @Composable () -> Unit = {
        val icon: @Composable () -> Unit = {
            Icon(Icons.Default.MoreVert, "自動刷新設定", Modifier.size(14.dp).then(iconModifier).graphicsLayer { rotationZ = menuRotation })
        }
        if (compactFeedback) CardIconButton(onClick = { menu = !menu; configure = false }, narrow = true,
            visualAlignment = if (inCard) Alignment.TopEnd else Alignment.Center,
            visualOffsetY = if (inCard) captionIconOffset else (-6).dp, content = icon)
        else IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp), content = icon)
    }
    val menuItems: @Composable () -> Unit = {
            extra { menu = false }
            DropdownMenuItem(text = { Text("自動刷新") }, leadingIcon = { Checkbox(plan != null, null) }, onClick = {
                menu = false
                if (plan == null) { interval = "30"; configure = true } else store.configure(target, null)
            })
            if (plan != null) DropdownMenuItem(text = { Text("間隔：${plan.intervalMinutes} 分鐘") }, onClick = {
                menu = false; interval = plan.intervalMinutes.toString(); configure = true
            })
            DebugBackgroundRefreshMenu { menu = false; diagnostic = true }
            if (target.kind == RefreshKind.SHOP)
                DropdownMenuItem(text = { Text("背景通知設定") }, onClick = { menu = false; backgroundSettings = true })
    }
    val minutes = interval.toIntOrNull()
    val valid = minutes != null && minutes in 15..10080
    val fields: @Composable () -> Unit = {
        OutlinedTextField(interval, { if (it.length <= 5 && it.all(Char::isDigit)) interval = it },
            label = { Text("刷新間隔（分鐘）") }, singleLine = true, isError = !valid,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("可設定 15～10080 分鐘。省電或待機時，Android 可能延後執行。", style = MaterialTheme.typography.bodySmall)
    }
    val save: () -> Unit = {
        if (valid) {
            store.configure(target, minutes!!); configure = false
            if (RefreshNotificationAccess.needsPermission(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else if (!RefreshNotificationAccess.enabled(context, RefreshNotificationAccess.channel(target))) permissionDenied = true
        }
    }
    val cardBounds = LocalCardMenuBounds.current?.value
    fun dismissPanel() { menu = false; configure = false; backgroundSettings = false }
    BackHandler(enabled = backActive && (menu || configure || backgroundSettings)) { dismissPanel() }
    if (cardBounds != null) {
        Box(if (inCard) Modifier.fillMaxWidth() else Modifier) {
            header(menuButton)
            CardMenuPopup(cardBounds, inCard, expanded = backActive && (menu || configure || backgroundSettings),
                onDismiss = { dismissPanel() }, preferredHeight = if (target.kind == RefreshKind.SHOP) 420.dp else 300.dp) {
                Crossfade(targetState = when { backgroundSettings -> 2; configure -> 1; else -> 0 },
                    animationSpec = tween(200), label = "refreshSettingsPage", modifier = Modifier.fillMaxSize()) { page ->
                    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (page) {
                                2 -> { Text("背景通知設定", style = MaterialTheme.typography.titleSmall); BackgroundDeliverySettingsContent() }
                                1 -> { Text("自動刷新", style = MaterialTheme.typography.titleSmall); fields() }
                                else -> menuItems()
                            }
                        }
                        if (page == 1) PairedActions("取消", { configure = false; menu = true }, "儲存", save, valid)
                        if (page == 2) PairedActions("返回", { backgroundSettings = false; menu = true }, "關閉", { dismissPanel() })
                    }
                }
            }
        }
    } else Box {
        header(menuButton)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { menuItems() }
    }
    if (configure && cardBounds == null) {
        AlertDialog(onDismissRequest = { configure = false }, title = { Text("自動刷新") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                fields()
            }
        }, confirmButton = { PairedActions("取消", { configure = false }, "儲存", save, valid) })
    }
    if (diagnostic) BackgroundRefreshDialog(target) { diagnostic = false }
    if (backgroundSettings && cardBounds == null) BackgroundDeliverySettingsDialog { backgroundSettings = false }
    if (permissionDenied) RefreshNotificationPermissionDialog { permissionDenied = false }
}

@Composable
private fun RefreshNotificationPermissionDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onClose, title = { Text("通知未開啟") },
        text = { Text("自動刷新已啟用，但通知權限尚未開啟。可到系統設定允許通知。") },
        confirmButton = { TextButton(onClick = {
            val intent = if (Build.VERSION.SDK_INT >= 26)
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
            onClose()
        }) { Text("系統通知設定") } },
        dismissButton = { TextButton(onClick = onClose) { Text("關閉") } })
}

@Composable
internal fun RefreshSleepDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AutomaticRefreshStore(context) }
    val saved = remember { store.sleep() }
    var enabled by remember { mutableStateOf(saved.enabled) }
    var start by remember { mutableIntStateOf(saved.start) }
    var end by remember { mutableIntStateOf(saved.end) }
    fun timeLabel(value: Int) = String.format(Locale.TAIWAN, "%02d:%02d", value / 60, value % 60)
    fun pickTime(value: Int, change: (Int) -> Unit) {
        TimePickerDialog(context, { _, hour, minute -> change(hour * 60 + minute) }, value / 60, value % 60, true).show()
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("睡眠模式") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(enabled, { enabled = it }); Text("啟用睡眠模式", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { pickTime(start) { start = it } }, Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("開始", style = MaterialTheme.typography.labelMedium)
                        Text(timeLabel(start), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
                OutlinedButton(onClick = { pickTime(end) { end = it } }, Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("結束", style = MaterialTheme.typography.labelMedium)
                        Text(timeLabel(end), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
            }
            Text("每日依裝置時間暫停所有自動刷新，支援跨午夜。手動刷新仍可使用。", style = MaterialTheme.typography.bodySmall)
            if (enabled && start == end) Text("開始與結束時間不可相同。", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !enabled || start != end, onClick = {
        store.setSleep(RefreshSleep(enabled, start, end)); onClose()
    }) { Text("儲存") } }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

package com.aykkdk.shop

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

// Pager keeps adjacent pages composed. Only the visible page may consume Back.
internal val LocalCardBackActive = compositionLocalOf { true }

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

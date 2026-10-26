package com.example.shopradar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SearchHistoryDropdown(market: Market, entries: List<SearchHistoryEntry>, enabled: Boolean,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    onRestore: (SearchHistoryEntry) -> Unit, onDelete: (SearchHistoryEntry) -> Unit) {
    LaunchedEffect(enabled) { if (!enabled) onExpandedChange(false) }
    Box {
        IconButton(onClick = { onExpandedChange(!expanded) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.ArrowDropDown, "${market.label}搜尋紀錄", Modifier.size(24.dp))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.widthIn(min = 260.dp, max = 360.dp).heightIn(max = 420.dp)) {
            if (entries.isEmpty()) Text("尚無搜尋紀錄", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            entries.forEach { entry ->
                key(entry.id) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onExpandedChange(false); onRestore(entry) }
                            .heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.criteria.title, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            entry.criteria.summary().takeIf { it.isNotBlank() }?.let { summary ->
                                Text(summary, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { onExpandedChange(false); onDelete(entry) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, "刪除搜尋紀錄：${entry.criteria.title}", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchHistoryDeleteDialog(entry: SearchHistoryEntry?, clearAll: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    if (entry == null && !clearAll) return
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (clearAll) "確認清除歷史紀錄？" else "確認刪除紀錄？") },
        text = { Text(if (clearAll) "將清除所有平台的搜尋紀錄，不影響訂閱與登入資料。"
            else "${entry!!.criteria.market.label}：${entry.criteria.title}") },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (clearAll) "清除" else "刪除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

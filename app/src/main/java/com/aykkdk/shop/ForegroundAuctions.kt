package com.aykkdk.shop

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Locale

internal object ForegroundAuctionPolicy {
    const val INTERVAL_MS = 20_000L
    const val RAPID_INTERVAL_MS = 3_000L

    fun active(entry: JSONObject): Boolean = !entry.optBoolean("cancelled") &&
        entry.optString("displayState") !in setOf("ended", "cancelled") &&
        entry.optString("url").isNotBlank()

    /** 判斷競拍是否處於結標前 5 分鐘高頻即時刷新窗口內 */
    fun isEndingSoon(entry: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
        if (!active(entry)) return false
        val end = entry.optLong("endsAt", 0L)
        val checked = entry.optLong("checkedAt", 0L)
        val serverAt = entry.optLong("serverAt", 0L)
        val estimatedServer = if (checked > 0L) {
            val base = if (serverAt > 0L) serverAt else checked
            base + (now - checked).coerceAtLeast(0L)
        } else {
            if (serverAt > 0L) serverAt else now
        }
        return end > 0 && (end - estimatedServer) in 1..(5 * 60 * 1000L)
    }
}

/** Owned by the Activity's foreground lifecycle, not the selected pager tab or WorkManager. */
internal object ForegroundAuctionPolling {
    suspend fun run(context: Context) = coroutineScope {
        val store = AuctionStore(context)
        val schedules = AutomaticRefreshStore(context)
        var read: Job? = null
        // Let the first UI frame draw. Other subscriptions still require manual/opt-in checks.
        delay(500)
        while (isActive) {
            val now = System.currentTimeMillis()
            val allActive = store.list().filter { ForegroundAuctionPolicy.active(it) && AdultVisibility.visible(it) }
            val endingSoon = allActive.filter { ForegroundAuctionPolicy.isEndingSoon(it, now) }
            val hasEndingSoon = endingSoon.isNotEmpty()

            if (read?.isActive != true && !schedules.sleep().contains(now) && RefreshNetwork.connected(context)) {
                val urls = if (hasEndingSoon) {
                    val needInitial = allActive.filter { it.optLong("endsAt", 0L) <= 0L }.map { it.optString("url") }
                    endingSoon.map { it.optString("url") }.toSet() + needInitial
                } else {
                    allActive.map { it.optString("url") }.toSet()
                }
                if (urls.isNotEmpty()) read = launch {
                    AuctionRefresher.refresh(context, urls, notifySystem = false) {
                        !schedules.sleep().contains(System.currentTimeMillis())
                    }
                }
            }
            val nextDelay = if (hasEndingSoon) ForegroundAuctionPolicy.RAPID_INTERVAL_MS else ForegroundAuctionPolicy.INTERVAL_MS
            delay(nextDelay)
        }
    }
}

@Composable
internal fun ForegroundAuctions(foreground: Boolean, dialogBusy: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { AuctionStore(context) }
    var version by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<AuctionAlertNotice?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(foreground) {
        if (foreground) ForegroundAuctionPolling.run(context.applicationContext)
        else withContext(Dispatchers.IO) {
            // Hand off already-observed, unacknowledged losses. No extra fetch,
            // background 20s loop or dependence on opening the auction page.
            if (!AutomaticRefreshStore(context).sleep().contains(System.currentTimeMillis()))
                AuctionNotifications.deliverPending(context.applicationContext)
        }
    }
    val pending = remember(version, foreground, AdultVisibility.unlocked) {
        store.list().filter { AdultVisibility.visible(it) }.mapNotNull(AuctionAlertPolicy::pending)
    }
    LaunchedEffect(foreground, dialogBusy, pending) {
        if (!foreground) shown = null
        else {
            if (shown != null && pending.none { it.url == shown?.url && it.token == shown?.token }) shown = null
            if (!dialogBusy && shown == null) shown = pending.firstOrNull()
        }
    }
    if (foreground && !dialogBusy) shown?.let { notice ->
        AlertDialog(onDismissRequest = {}, title = { Text("競標已被超過") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(notice.title)
                Text("超標時最高價：NT$${String.format(Locale.TAIWAN, "%,d", notice.price)}")
                Text("你的出價已被超過。")
            }
        }, confirmButton = { TextButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { store.acknowledgeAlert(notice.url, notice.token) }
                if (shown?.token == notice.token) shown = null
            }
        }) { Text("知道了") } })
    }
}

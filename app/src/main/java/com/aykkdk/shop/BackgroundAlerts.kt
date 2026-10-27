package com.aykkdk.shop

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object RefreshNotificationAccess {
    const val CHANNEL_SUBSCRIPTIONS = "subscription-updates"
    const val CHANNEL_AUCTIONS = "auction-outbid"

    fun needsPermission(context: Context) = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val subChannel = android.app.NotificationChannel(
                CHANNEL_SUBSCRIPTIONS,
                "訂閱更新",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "店家新品與商品降價更新通知"
            }
            val auctionChannel = android.app.NotificationChannel(
                CHANNEL_AUCTIONS,
                "競拍超標提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "競標出價被超過提醒"
            }
            manager.createNotificationChannel(subChannel)
            manager.createNotificationChannel(auctionChannel)
        }
    }

    fun enabled(context: Context, channel: String): Boolean {
        if (needsPermission(context)) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 26) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        var ch = manager.getNotificationChannel(channel)
        if (ch == null) {
            createChannels(context)
            ch = manager.getNotificationChannel(channel)
        }
        return ch != null && ch.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun channel(target: RefreshTarget) = if (target.kind == RefreshKind.AUCTION) CHANNEL_AUCTIONS else CHANNEL_SUBSCRIPTIONS
}

/** Saved in the same record/commit as the changed product/checkpoint, never an Activity callback. */
internal object BackgroundAlertJournal {
    private const val KEY = "pendingBackgroundAlerts"
    fun append(record: JSONObject, alerts: List<ProductSubscriptionAlert>, discoveredAt: Long = System.currentTimeMillis()) {
        val pending = rows(record).toMutableList()
        alerts.filter(::visible).forEach { alert ->
            if (pending.none { identity(fromRow(it)) == identity(alert) })
                pending += JSONObject().put("id", UUID.randomUUID().toString()).put("shop", alert.shop.take(240))
                    .put("title", alert.title.take(240)).put("kind", alert.kind.take(80)).put("productUrl", alert.productUrl)
                    .put("discoveredAt", discoveredAt)
        }
        if (pending.isNotEmpty()) record.put(KEY, JSONArray(pending.takeLast(64)))
    }
    fun rows(record: JSONObject): List<JSONObject> = record.optJSONArray(KEY)?.let { data ->
        (0 until data.length()).mapNotNull { data.optJSONObject(it) }
    }.orEmpty()
    private fun fromRow(row: JSONObject) = ProductSubscriptionAlert(row.optString("shop"), row.optString("title"),
        row.optString("kind"), productUrl = row.optString("productUrl"))
    fun identity(alert: ProductSubscriptionAlert): String = if (alert.productUrl.isNotBlank()) "${alert.productUrl}|${alert.kind}"
        else "${alert.shop}|${alert.title}|${alert.kind}"
    fun alerts(record: JSONObject): List<ProductSubscriptionAlert> = rows(record).map(::fromRow).filter(::visible)
    fun clear(record: JSONObject, deliveredIds: Set<String>) {
        val remaining = rows(record).filter { it.optString("id") !in deliveredIds }
        if (remaining.isEmpty()) record.remove(KEY) else record.put(KEY, JSONArray(remaining))
    }
    fun visible(alert: ProductSubscriptionAlert) = !alert.isAdult && !AdultVisibility.adultText(alert.title) && !AdultVisibility.adultText(alert.shop)
}

internal object BackgroundAlertDelivery {
    /** Retry already saved alerts independently of the next network/check interval. */
    fun drainEnabled(context: Context): Boolean {
        val schedules = AutomaticRefreshStore(context)
        if (schedules.sleep().contains(System.currentTimeMillis())) return true
        var completed = true

        // 1. Drain pending auctions
        runCatching {
            if (RefreshNotificationAccess.enabled(context, RefreshNotificationAccess.CHANNEL_AUCTIONS)) {
                val sent = AuctionNotifications.deliverPending(context)
                if (sent) BackgroundDeliveryCheck.delivery(context, "notification_sent")
            }
        }.onFailure {
            BackgroundDeliveryCheck.delivery(context, "notification_failed")
            completed = false
        }

        // 2. Drain all scheduled targets
        schedules.entries().forEach { (target, plan) ->
            if (schedules.allowed(target, plan)) {
                val result = runCatching { drain(context, target) { schedules.allowed(target, plan) } }
                    .getOrElse {
                        BackgroundDeliveryCheck.delivery(context, "notification_failed")
                        "notification_failed"
                    }
                if (result == "notification_failed") completed = false
            }
        }

        // 3. Drain any unscheduled pending shop & product alerts remaining in journal
        val shopPrefs = context.getSharedPreferences("shop_subscriptions_v2", 0)
        shopPrefs.all.keys.forEach { url ->
            val target = RefreshTarget(RefreshKind.SHOP, url)
            if (schedules.exists(target) && schedules.plan(target) == null) {
                val allowed = { schedules.exists(target) && !schedules.sleep().contains(System.currentTimeMillis()) }
                val result = runCatching { drain(context, target, allowed) }.getOrElse {
                    BackgroundDeliveryCheck.delivery(context, "notification_failed")
                    "notification_failed"
                }
                if (result == "notification_failed") completed = false
            }
        }

        val productPrefs = context.getSharedPreferences("product_subscriptions_v1", 0)
        productPrefs.all.keys.forEach { url ->
            val target = RefreshTarget(RefreshKind.PRODUCT, url)
            if (schedules.exists(target) && schedules.plan(target) == null) {
                val allowed = { schedules.exists(target) && !schedules.sleep().contains(System.currentTimeMillis()) }
                val result = runCatching { drain(context, target, allowed) }.getOrElse {
                    BackgroundDeliveryCheck.delivery(context, "notification_failed")
                    "notification_failed"
                }
                if (result == "notification_failed") completed = false
            }
        }

        return completed
    }
    fun drain(context: Context, target: RefreshTarget, allowed: () -> Boolean): String =
        drainSaved(context, target, allowed).also { BackgroundDeliveryCheck.delivery(context, it) }

    private fun drainSaved(context: Context, target: RefreshTarget, allowed: () -> Boolean): String {
        if (!allowed()) return "cancelled"
        if (target.kind == RefreshKind.AUCTION) {
            val store = AuctionStore(context)
            val hasPending = store.list().any { it.optString("url") == target.url && AuctionAlertPolicy.needsSystemNotification(it) }
            if (!hasPending) return "no_updates"
            if (!RefreshNotificationAccess.enabled(context, RefreshNotificationAccess.channel(target))) return "notification_blocked"
            return if (allowed() && AuctionNotifications.deliverPending(context, target.url)) "notification_sent" else "no_updates"
        }
        val prefs = context.getSharedPreferences(if (target.kind == RefreshKind.SHOP) "shop_subscriptions_v2" else "product_subscriptions_v1", 0)
        return synchronized(SubscriptionMutations.lock) {
            if (!allowed()) return@synchronized "cancelled"
            val record = prefs.getString(target.url, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@synchronized "cancelled"
            val alerts = BackgroundAlertJournal.alerts(record)
            if (alerts.isEmpty()) return@synchronized "no_updates"
            if (!SubscriptionRefreshNotifications.show(context, target, alerts, record)) return@synchronized "notification_blocked"
            BackgroundAlertJournal.clear(record, BackgroundAlertJournal.rows(record).map { it.optString("id") }.toSet())
            if (prefs.edit().putString(target.url, record.toString()).commit()) "notification_sent" else "notification_failed"
        }
    }
}

package com.example.shopradar

import java.net.URLEncoder
import java.util.UUID

internal data class SubscriptionNotificationRoute(val id: String, val target: RefreshTarget) {
    companion object {
        const val ACTION = "com.example.shopradar.OPEN_SUBSCRIPTION"
        const val EXTRA_KIND = "subscriptionNotificationKind"
        const val EXTRA_URL = "subscriptionNotificationUrl"
        fun parse(kind: String?, url: String?): SubscriptionNotificationRoute? {
            val type = RefreshKind.values().firstOrNull { it.name == kind && it != RefreshKind.AUCTION } ?: return null
            if (url.isNullOrBlank() || url.length > 4096) return null
            val market = Market.fromPublicUrl(url) ?: return null
            if (type == RefreshKind.PRODUCT && !market.isPublicProductUrl(url)) return null
            return SubscriptionNotificationRoute(UUID.randomUUID().toString(), RefreshTarget(type, url))
        }
        // Intent extras alone do not distinguish PendingIntents, and hash codes can collide.
        fun identity(target: RefreshTarget): String = "shopradar://subscription/${target.kind.name}?target=" +
            URLEncoder.encode(target.url, "UTF-8")
    }
}

internal data class SubscriptionNotificationText(val title: String, val text: String) {
    companion object {
        fun build(target: RefreshTarget, alerts: List<ProductSubscriptionAlert>, record: org.json.JSONObject? = null): SubscriptionNotificationText? {
            val visible = alerts.filter(BackgroundAlertJournal::visible).distinctBy(BackgroundAlertJournal::identity)
            if (visible.isEmpty()) return null
            if (target.kind != RefreshKind.SHOP) return SubscriptionNotificationText("訂閱有更新",
                visible.joinToString("\n") { "${it.shop} · ${it.title} · ${it.kind}" })
            val current = record?.let { saved -> ShopNewItems.read(saved).filter {
                it.url in ShopNewItems.latestUrls(saved) && BackgroundAlertJournal.visible(
                    ProductSubscriptionAlert(it.shop, it.title, "新品", it.isAdult, it.url))
            } }.orEmpty()
            val counted = current.map { it.url }.toMutableSet()
            visible.forEach { alert ->
                if (alert.productUrl.isNotBlank()) counted += alert.productUrl
                else if (current.none { it.title == alert.title }) counted += BackgroundAlertJournal.identity(alert)
            }
            val name = ShopText.shop(record?.optString("name").orEmpty()).takeUnless(AdultVisibility::adultText).orEmpty()
                .ifBlank { ShopText.shop(visible.first().shop) }.ifBlank { "訂閱店家" }
            return SubscriptionNotificationText(name, "上架了 ${counted.size} 件物品，快來看看吧")
        }
    }
}

/** Event order, not refresh/delivery order. Missing public dates use the saved first discovery. */
internal object SubscriptionNotificationOrder {
    fun eventTime(alerts: List<ProductSubscriptionAlert>, record: org.json.JSONObject?, now: Long): Long {
        val visible = alerts.filter(BackgroundAlertJournal::visible)
        val latest = record?.let(ShopNewItems::latestUrls).orEmpty()
        val known = record?.let(ShopNewItems::read).orEmpty().filter { product ->
            BackgroundAlertJournal.visible(ProductSubscriptionAlert(product.shop, product.title, "新品", product.isAdult, product.url)) &&
                (product.url in latest || visible.any { alert ->
                    if (alert.productUrl.isNotBlank()) alert.productUrl == product.url else alert.title == product.title
                })
        }.mapNotNull(ShopCheckpoint::timestamp).filter { it in 1..now }.maxOrNull()
        if (known != null) return known
        val detected = record?.let(BackgroundAlertJournal::rows).orEmpty().filter { row ->
            visible.any { alert -> row.optString("productUrl").takeIf(String::isNotBlank)?.let { it == alert.productUrl }
                ?: (row.optString("title") == alert.title && row.optString("kind") == alert.kind) }
        }.map { it.optLong("discoveredAt") }.filter { it in 1..now }.maxOrNull()
        // Legacy journals did not record discovery time. The saved successful check is the best available fallback.
        return detected ?: record?.optLong("checkedAt")?.takeIf { it in 1..now } ?: now
    }

    fun sortKey(eventTime: Long, target: RefreshTarget): String =
        (Long.MAX_VALUE - eventTime.coerceAtLeast(0)).toString().padStart(19, '0') + ":" + target.key
}

package com.example.shopradar

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

internal object SubscriptionMutations { val lock = Any() }
/** Attempt timestamps are independent of the last successfully completed listing watermark. */
internal object RefreshAttemptRecord {
    fun begin(record: JSONObject, run: String, now: Long) {
        record.put("refreshAttemptRun", run).put("lastRefreshAttemptAt", now).put("lastRefreshAttemptState", "reading")
    }
    fun finish(record: JSONObject, run: String, result: RefreshResult, now: Long): Boolean {
        if (record.optString("refreshAttemptRun") != run || record.optLong("checkedAt") > now) return false
        record.put("lastRefreshFinishedAt", now).put("lastRefreshAttemptState",
            if (result.error != null) "failed" else if (result.pending) "pending" else "complete")
        if (result.error == null) record.remove("lastRefreshError") else record.put("lastRefreshError", result.error)
        return true
    }
}
internal data class ShopRefreshUpdate(val record: JSONObject, val alerts: List<ProductSubscriptionAlert>) {
    companion object {
        /** Null/deleted records and a newer watermark can never be recreated/overwritten. */
        fun build(key: String, raw: String?, market: Market, checkpoint: Long, seen: Set<String>,
            found: List<Product>, collected: List<Product>, initialize: Boolean, complete: Boolean,
            nextPage: Int, now: Long): ShopRefreshUpdate? {
            if (raw == null) return null
            val latest = shopSubscriptionRecord(key, raw)
            if (latest.optLong("listingCheckpointAt", latest.optLong("rutenCheckpointAt")) != checkpoint) return null
            val oldItems = ShopNewItems.read(latest)
            val existingUrls = oldItems.map { it.url }.toSet()
            val newItems = if (initialize) emptyList() else collected
            val name = found.firstOrNull { it.shop.isNotBlank() }?.shop ?: latest.optString("name").ifBlank { market.label }
            latest.put("name", name)
            ShopSubscriptionCover.update(latest, found)
            ShopNewItems.merge(latest, newItems)
            if (complete) {
                val checked = if (initialize) found else collected
                latest.put("seen", JSONArray((seen + checked.map { it.url }).toList()))
                    .put("listingCheckpointAt", maxOf(checkpoint, ShopCheckpoint.newest(checked)))
                    .put("checkedAt", now)
                latest.remove("refreshScan"); latest.remove("lastRefreshError")
            } else latest.put("refreshScan", JSONObject().put("checkpoint", checkpoint).put("page", nextPage)
                .put("products", JSONArray(collected.map { it.json() })))
            return ShopRefreshUpdate(latest, newItems.filter { it.url !in existingUrls }
                .map { ProductSubscriptionAlert(name, it.title, "新品", it.isAdult, it.url) })
        }
    }
}

/** Select once from known products; refreshes must not replace an existing store avatar. */
internal object ShopSubscriptionCover {
    fun update(record: JSONObject, products: List<Product>, random: Random = Random.Default): Boolean {
        if (ProductThumbnail.url(record.optString("image")) != null) return false
        val images = products.filter { !it.isAdvertisement && !it.isAdult &&
            !AdultVisibility.adultText(it.title) && !AdultVisibility.adultText(it.shop) &&
            !AdultVisibility.adultText(it.category) }
            .mapNotNull { ProductThumbnail.url(it.image) }.distinct()
        val image = images.randomOrNull(random) ?: return false
        record.put("image", image)
        return true
    }
}

package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject

/** Presentation/history order only; never reorder a source scan before its old-item boundary. */
internal object ShopNewItems {
    fun pageTitle(name: String, products: List<Product> = emptyList()): String {
        val seller = ShopText.shop(name).ifBlank {
            products.firstNotNullOfOrNull { ShopText.shop(it.shop).takeIf(String::isNotBlank) }.orEmpty()
        }.ifBlank { "訂閱" }
        return (if (seller.endsWith("賣場")) seller else "${seller}賣場") + "的新品"
    }
    private const val LATEST = "latestNewItemUrls"
    fun latestUrls(record: JSONObject): Set<String> = record.optJSONArray(LATEST)?.let { rows ->
        (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }.toSet()
    }.orEmpty()

    fun newestFirst(products: List<Product>, latest: Set<String> = emptySet()): List<Product> {
        // Parse a legacy text timestamp once per product, not on every comparison.
        val unique = products.distinctBy { it.url }
        if (unique.size < 2) return unique
        val times = unique.associate { it.url to (ShopCheckpoint.timestamp(it) ?: 0L) }
        return unique.sortedWith(compareByDescending<Product> { it.url in latest }
            .thenByDescending { times[it.url] ?: 0L })
    }

    fun read(record: JSONObject): List<Product> = newestFirst(record.optJSONArray("newItems")?.let { rows ->
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(Product::parse) }
    }.orEmpty().filterNot { it.platform == Market.YAHOO.label &&
        it.title in setOf("全部商品", "所有商品", "商品列表", "全部", "拍賣商品") }, latestUrls(record))

    /** Prepend the current check and replace matching metadata, retaining all earlier findings. */
    fun merge(record: JSONObject, incoming: List<Product>) {
        val latest = incoming.distinctBy { it.url }
        val retained = read(record)
        val urls = latest.map { it.url }.toSet()
        record.put(LATEST, JSONArray(urls.toList()))
            .put("newItems", JSONArray(newestFirst(latest + retained, urls).map { it.json() }))
            .put("newCount", latest.size)
    }
}

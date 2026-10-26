package com.example.shopradar

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Shared first-party public metadata endpoint used by search and stock checks. */
internal object RutenProductMetadata {
    fun productId(url: String): String? {
        if (!Market.RUTEN.isPublicProductUrl(url)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        Regex("^/item/(\\d{8,})/?$").find(uri.path)?.let { return it.groupValues[1] }
        if (uri.path.trimEnd('/') != "/item/show") return null
        return uri.rawQuery.orEmpty().split('&').map { it.substringAfter('=') }
            .firstOrNull { it.matches(Regex("\\d{8,}")) }
    }

    fun read(ids: List<String>): JSONArray {
        require(ids.isNotEmpty() && ids.size <= 40 && ids.all { it.matches(Regex("\\d{8,}")) })
        val connection = URL("https://rtapi.ruten.com.tw/api/prod/v3/index.php/prod?id=" + ids.distinct().joinToString(",")).openConnection() as HttpsURLConnection
        return try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ShopRadar/1.27 Android")
            val status = connection.responseCode
            if (status != 200) throw SearchHttpException(status, "露天")
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val chunk = CharArray(8192)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    require(text.length + count <= 2_000_000) { "露天資料過大" }
                    text.append(chunk, 0, count)
                }
                text.toString()
            }
            JSONArray(raw)
        } finally { connection.disconnect() }
    }

    fun snapshot(url: String, rows: JSONArray): PublicProductSnapshot {
        val id = productId(url) ?: return PublicProductSnapshot(error = "露天商品編號無法辨識；保留上次狀態")
        val item = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull { it.optString("ProdId") == id }
            ?: return PublicProductSnapshot(error = "露天未回傳這件商品；保留上次狀態")
        return snapshot(item)
    }

    /** API-only: absent data remains unknown; never fetch a product page as a fallback. */
    fun snapshotOrUnavailable(url: String, rows: JSONArray?): PublicProductSnapshot =
        rows?.let { snapshot(url, it) } ?: PublicProductSnapshot(error = "露天暫時無法檢查；保留上次狀態")

    private fun snapshot(item: JSONObject): PublicProductSnapshot {
        val listed = RutenListingTime.read(item)
        if (RutenAuctionMetadata.candidate(item)) return PublicProductSnapshot(isAuction = true,
            title = ShopText.title(item.optString("ProdName")), auctionTypeKnown = true,
            listedAt = listed?.text, listedAtEpochMillis = listed?.epochMillis)
        // Missing/ambiguous variant or preorder stock is not evidence of a sale.
        val quantity = ProductStock.quantity(item.opt("StockQty"))
        val ambiguousZero = item.optString("SpecActive") == "Y" ||
            item.optString("PreOrderShipDate").let { it.isNotBlank() && it != "null" }
        val status = when {
            quantity == null -> "unknown"
            // StockStatus describes shipping speed (e.g. 4 = ships within seven
            // days), not quantity or whether the listing is sold.
            quantity > 0 -> "active"
            quantity == 0L && !ambiguousZero -> "sold"
            else -> "unknown"
        }
        val price = item.optJSONArray("PriceRange")?.opt(0)?.toString()?.toDoubleOrNull()
            ?.takeIf { item.optString("Currency") == "TWD" && it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
        return PublicProductSnapshot(status = status, price = price,
            listedAt = listed?.text, listedAtEpochMillis = listed?.epochMillis,
            title = item.optString("ProdName").takeIf { it.isNotBlank() }?.let(ShopText::title),
            image = RutenAuctionMetadata.image(item).takeIf { it.isNotBlank() },
            auctionTypeKnown = item.optString("SaleType") == "buy",
            stockQuantity = quantity?.takeIf { status != "unknown" && !ambiguousZero },
            error = if (status == "unknown") "露天未提供可確認的庫存狀態；保留上次狀態" else null)
    }
}

package com.example.shopradar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class ShopPageRead(val products: List<Product>, val exhausted: Boolean = false)

/** Public APIs used by the official store SPA; no Activity, hidden renderer or account cookies. */
internal object RutenShopReader {
    private const val PAGE_SIZE = 30
    private val ids = Regex("\\d{8,}")

    suspend fun read(request: SearchCapture, fetch: suspend (String) -> JSONObject = ::fetch): ShopPageRead {
        val username = Market.RUTEN.rutenStoreUsername(request.url) ?: error("露天店家網址無法辨識。")
        val store = fetch("https://rapi.ruten.com.tw/api/users/v1/index.php/$username/storeinfo")
        require(store.optString("status") == "success") { "露天未提供店家資料。" }
        val info = store.getJSONObject("data")
        val sellerId = info.optString("user_id")
        require(sellerId.matches(Regex("\\d{1,12}"))) { "露天未提供有效店家編號。" }
        val name = ShopText.shop(info.optString("store_name")).ifBlank { username }
        // request.token is a capture identity, not the store page. Read p from
        // the validated official URL; keep new/dc and the public store URL intact.
        val page = java.net.URI(request.url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("p=") }
            ?.substringAfter('=')?.toIntOrNull()?.coerceIn(1, 50) ?: 1
        val first = (page - 1) * PAGE_SIZE + 1
        val response = fetch("https://rtapi.ruten.com.tw/api/search/v3/index.php/core/seller/$sellerId/prod?sort=new%2Fdc&offset=$first&limit=$PAGE_SIZE")
        val rows = response.getJSONArray("Rows")
        val total = response.getLong("TotalRows")
        require(total >= 0 && rows.length() <= PAGE_SIZE) { "露天店家列表格式已變更。" }
        val ordered = (0 until rows.length()).map { rows.getJSONObject(it).getString("Id") }
        require(ordered.all { ids.matches(it) } && ordered.distinct().size == ordered.size) { "露天店家商品編號無法確認。" }
        if (ordered.isEmpty()) {
            require(first > total) { "露天未回傳預期的店家商品。" }
            return ShopPageRead(emptyList(), exhausted = true)
        }
        suspend fun products(batch: List<String>): List<Product> {
            currentCoroutineContext().ensureActive()
            val raw = fetch("https://rapi.ruten.com.tw/api/items/v2/list?gno=${batch.joinToString(",")}&level=simple")
            require(raw.optString("status") == "success") { "露天店家商品資料未完成。" }
            val items = raw.getJSONArray("data")
            val byId = (0 until items.length()).map { items.getJSONObject(it) }.associateBy { it.optString("id") }
            return batch.map { id ->
                val item = byId[id] ?: error("露天未回傳預期商品；保留比對基準。")
                product(item, id, username, sellerId, name)
            }
        }
        // An old first item proves there is no update: request detail for only
        // that item, not every product/page in a shop.
        val head = products(ordered.take(1))
        if (request.scanSubscription && ShopCheckpoint.scan(head, request.subscriptionCheckpoint, request.subscriptionSeen).reachedOld)
            return ShopPageRead(head)
        val remaining = if (ordered.size > 1) products(ordered.drop(1)) else emptyList()
        val found = head + remaining
        val boundary = if (request.scanSubscription) found.indexOfFirst {
            it.url in request.subscriptionSeen || request.subscriptionCheckpoint > 0 &&
                (ShopCheckpoint.timestamp(it)?.let { at -> at <= request.subscriptionCheckpoint } == true)
        } else -1
        return ShopPageRead(if (boundary >= 0) found.take(boundary + 1) else found, first + ordered.size - 1 >= total)
    }

    internal fun product(item: JSONObject, id: String, username: String, sellerId: String, storeName: String): Product {
        require(item.optString("id") == id && item.optString("user").equals(username, true) && item.optString("ctrl_rowid") == sellerId) {
            "露天回傳商品與店家不符。"
        }
        require(item.optString("platform") == "RUTEN" && item.optString("currency") == "TWD" && item.optBoolean("available")) {
            "露天未提供可確認的公開商品。"
        }
        val title = ShopText.title(item.getString("name"))
        val price = item.optJSONObject("goods_price_range")?.opt("min") ?: item.opt("goods_price")
        val amount = price?.toString()?.toBigDecimalOrNull()?.let { runCatching { it.longValueExact() }.getOrNull() }
        val listed = requireNotNull(RutenListingTime.read(item)) { "露天店家商品時間尚無法確認。" }
        require(title.isNotBlank() && amount != null && amount >= 0) {
            "露天店家商品時間或價格尚無法確認。"
        }
        val images = item.optJSONObject("images")?.optJSONArray("url")
        val detail = JSONObject().put("Image", images)
        return Product(title, amount, Market.RUTEN.label, "https://www.ruten.com.tw/item/$id/",
            RutenAuctionMetadata.image(detail), listed.text, storeName,
            listedAtEpochMillis = listed.epochMillis, isAuction = RutenAuctionMetadata.candidate(detail),
            isAdult = AdultVisibility.adultCategory(Market.RUTEN, item.optString("class")) || AdultVisibility.adultText(title))
    }

    private suspend fun fetch(url: String): JSONObject = runInterruptible(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 5_000; connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ShopRadar/1.27 Android public-store-monitor")
            require(connection.responseCode == 200) { "露天店家回應 HTTP ${connection.responseCode}。" }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder(); val chunk = CharArray(8192)
                while (true) {
                    val count = reader.read(chunk); if (count < 0) break
                    require(output.length + count <= 2_000_000) { "露天店家資料過大。" }
                    output.append(chunk, 0, count)
                }
                output.toString()
            }
            JSONObject(text)
        } finally { connection.disconnect() }
    }
}

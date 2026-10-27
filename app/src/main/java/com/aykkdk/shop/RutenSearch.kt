package com.aykkdk.shop

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// Endpoints and field names verified against Ruten's public search frontend.
object RutenSearch {
    /** Store cards omit dates. Join the public detail response by product ID. */
    suspend fun enrich(products: List<Product>): List<Product> = withContext(Dispatchers.IO) {
        val byId = products.associateBy { Regex("\\d{8,}").find(it.url)?.value.orEmpty() }
        val details = mutableMapOf<String, JSONObject>()
        byId.keys.filter { it.isNotBlank() }.chunked(40).forEach { ids ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            runCatching {
                val data = RutenProductMetadata.read(ids)
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    if (item.optString("ProdId") in ids) details[item.optString("ProdId")] = item
                }
            }
        }
        products.map { product -> enrichProduct(product, details[Regex("\\d{8,}").find(product.url)?.value]) }
    }
    internal fun enrichProduct(product: Product, detail: JSONObject?): Product {
        val listed = detail?.let(RutenListingTime::read)
        return product.copy(
            title = ShopText.title(detail?.optString("ProdName")?.takeIf { it.isNotBlank() } ?: product.title),
            listedAt = listed?.text ?: product.listedAt,
            listedAtEpochMillis = listed?.epochMillis ?: product.listedAtEpochMillis,
            shop = ShopText.shop(product.shop).ifBlank { detail?.optString("SellerId").orEmpty() },
            image = detail?.let(RutenAuctionMetadata::image)?.takeIf { it.isNotBlank() } ?: product.image,
            isAuction = product.isAuction || detail?.let(RutenAuctionMetadata::candidate) == true,
            isAdult = product.isAdult || AdultVisibility.adultCategory(Market.RUTEN, detail?.optString("CateId").orEmpty())
        )
    }
    suspend fun search(keyword: String, min: String, max: String, page: Int = 1, categoryId: String = "",
        sort: String = "", onProgress: suspend (List<Product>) -> Unit = {}): List<Product> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Product>()
        val auctionIds = linkedSetOf<String>()
        // Publish each complete batch as soon as its real detail response arrives.
        for (batch in 0..1) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val builder = Uri.parse("https://rtapi.ruten.com.tw/api/search/v4/index.php/core/prod").buildUpon()
                .appendQueryParameter("offset", (((page.coerceIn(1, 20) - 1) * 80) + batch * 40 + 1).toString())
                .appendQueryParameter("limit", "40")
            val validSort = RutenFilters.sort(sort)
            if (validSort.isNotBlank()) builder.appendQueryParameter("sort", validSort)
            if (keyword.isNotBlank()) builder.appendQueryParameter("q", keyword)
            RutenCategories.validId(categoryId)?.let { builder.appendQueryParameter("cateid", it) }
            if (min.isNotBlank() || max.isNotBlank()) builder.appendQueryParameter("prc.now", "$min-$max")
            val response = JSONObject(read(builder.build().toString()))
            require(response.has("Rows")) { "露天搜尋回應格式已變更。" }
            val rows = response.getJSONArray("Rows")
            if (rows.length() == 0) break
            val ids = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("Id") }
                .filter { it.matches(Regex("\\d+")) }.distinct()
            require(ids.isNotEmpty()) { "露天未提供有效商品編號。" }
            val details = RutenProductMetadata.read(ids)
            val byId = (0 until details.length()).mapNotNull { details.optJSONObject(it) }.associateBy { it.optString("ProdId") }
            ids.mapNotNull { id -> byId[id]?.let(::metadataProduct) }.forEach(result::add)
            onProgress(result.toList())
            auctionIds.addAll(ids.filter { byId[it]?.let(RutenAuctionMetadata::candidate) == true })
            if (rows.length() < 40) break
        }
        // Fetch both halves of this page before optional per-auction details, so
        // a slow bid endpoint cannot hold back the page's remaining ordinary cards.
        val slots = Semaphore(3)
        val updates = Mutex()
        val baseProducts = result.associateBy { RutenProductMetadata.productId(it.url) }
        coroutineScope {
                auctionIds.map { id -> async {
                    slots.withPermit {
                        currentCoroutineContext().ensureActive()
                        val product = baseProducts[id] ?: return@withPermit
                        val enriched = try { RutenAuctionMetadata.product(product, RutenAuctionMetadata.read(id), System.currentTimeMillis()) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        if (enriched != null) updates.withLock {
                            val index = result.indexOfFirst { it.url == product.url }
                            if (index >= 0) result[index] = enriched
                            onProgress(result.toList())
                        }
                    }
                } }.awaitAll()
        }
        result.distinctBy { it.url }
    }
    internal fun metadataProduct(item: JSONObject): Product? {
        val id = item.optString("ProdId")
        val price = item.optJSONArray("PriceRange")?.optLong(0, -1) ?: -1
        if (!id.matches(Regex("\\d{8,}")) || price < 0 || item.optString("Currency") != "TWD" || item.optString("ProdName").isBlank()) return null
        val listed = RutenListingTime.read(item)
        return Product(ShopText.title(item.getString("ProdName")), price, Market.RUTEN.label, "https://www.ruten.com.tw/item/$id/",
            RutenAuctionMetadata.image(item), listed?.text.orEmpty(), ShopText.shop(item.optString("SellerId")),
            listedAtEpochMillis = listed?.epochMillis ?: 0,
            isAuction = RutenAuctionMetadata.candidate(item),
            isAdult = AdultVisibility.adultCategory(Market.RUTEN, item.optString("CateId")) || AdultVisibility.adultText(item.optString("ProdName")))
    }
    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpsURLConnection
        return try {
            c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "ShopRadar/1.1 Android")
            val status = c.responseCode
            if (status != 200) throw SearchHttpException(status, "露天")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }
}

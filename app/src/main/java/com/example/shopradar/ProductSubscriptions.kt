package com.example.shopradar

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class ProductSubscriptionAlert(val shop: String, val title: String, val kind: String, val isAdult: Boolean = false,
    val productUrl: String = "")
data class ProductSubscriptionAddResult(val accepted: Boolean, val message: String)

/** Public product metadata only; transport failures and login pages never mean sold. */
class ProductSubscriptionStore(private val context: Context) {
    internal val prefs = context.getSharedPreferences("product_subscriptions_v1", Context.MODE_PRIVATE)
    fun contains(raw: String): Boolean = synchronized(SubscriptionMutations.lock) {
        prefs.all.keys.any { SubscriptionIdentity.sameProduct(it, raw) }
    }
    fun add(product: Product): Unit = synchronized(SubscriptionMutations.lock) {
        val market = Market.fromPublicProductUrl(product.url)
            ?: throw IllegalArgumentException("這不是可直接訂閱的公開商品網址。")
        val url = market.normalizePublicProductUrl(product.url)
            ?: throw IllegalArgumentException("商品網址格式無法辨識。")
        require(!contains(url)) { SubscriptionIdentity.DUPLICATE_MESSAGE }
        ImageCachePolicy.record(context, listOf(product))
        val item = JSONObject(prefs.getString(url, "{}") ?: "{}")
        ProductSubscriptionDecision.migrate(item)
        item.put("url", url).put("title", product.title).put("platform", market.label)
            .put("isAdult", product.isAdult || AdultVisibility.adultText(product.title))
            .put("shop", ShopText.shop(product.shop)).put("price", product.price)
            .put("status", item.optString("status", "unknown"))
        ProductThumbnail.url(product.image)?.let { item.put("image", it) }
        if (product.category.isNotBlank()) item.put("category", product.category)
        if (product.listedAt.isNotBlank()) {
            if (product.listedAt != item.optString("listedAt")) item.remove("listedAtEpochMillis")
            item.put("listedAt", product.listedAt)
            if (product.listedAtEpochMillis > 0) item.put("listedAtEpochMillis", product.listedAtEpochMillis)
        }
        prefs.edit().putString(url, item.toString()).apply()
        SubscriptionRefreshQueue.request(context, RefreshTarget(RefreshKind.PRODUCT, url))
    }
    fun addPublicUrl(raw: String, initial: PublicProductSnapshot? = null): ProductSubscriptionAddResult = synchronized(SubscriptionMutations.lock) {
        val market = Market.fromPublicProductUrl(raw)
            ?: return@synchronized ProductSubscriptionAddResult(false, "這不是可直接訂閱的公開商品網址。")
        val url = market.normalizePublicProductUrl(raw)
            ?: return@synchronized ProductSubscriptionAddResult(false, "商品網址格式無法辨識。")
        if (contains(url)) return@synchronized ProductSubscriptionAddResult(false, SubscriptionIdentity.DUPLICATE_MESSAGE)
        val item = JSONObject(prefs.getString(url, "{}") ?: "{}")
        ProductSubscriptionDecision.migrate(item)
        item.put("url", url).put("title", item.optString("title").ifBlank { "待取得商品資料" })
            .put("platform", market.label).put("shop", item.optString("shop").ifBlank { market.label })
            .put("price", item.optLong("price", -1)).put("status", item.optString("status", "unknown"))
        initial?.let { ProductSubscriptionDecision.apply(item, it, System.currentTimeMillis()) }
        prefs.edit().putString(url, item.toString()).apply()
        if (initial == null) SubscriptionRefreshQueue.request(context, RefreshTarget(RefreshKind.PRODUCT, url))
        ProductSubscriptionAddResult(true, if (initial != null) "已訂閱商品，已完成首次讀取。"
            else "已訂閱商品，正在讀取名稱、價格與庫存。")
    }
    fun remove(url: String) {
        val image = runCatching { JSONObject(prefs.getString(url, "{}") ?: "{}").optString("image") }.getOrDefault("")
        ImageCachePolicy.removeProducts(context, listOf(url), listOf(image))
        NotificationManagerCompat.from(context).cancel("subscription-updates", RefreshTarget(RefreshKind.PRODUCT, url).key.hashCode())
        synchronized(SubscriptionMutations.lock) { prefs.edit().remove(url).commit() }
        AutomaticRefreshStore(context).configure(RefreshTarget(RefreshKind.PRODUCT, url), null)
    }
    fun list(): List<JSONObject> = prefs.all.values.mapNotNull {
        runCatching { JSONObject(it as String).also { item ->
            if (item.optInt("stockEvidenceVersion") < 2) {
                ProductSubscriptionDecision.migrate(item)
                prefs.edit().putString(item.getString("url"), item.toString()).apply()
            }
        } }.getOrNull()?.takeIf { item -> Market.fromPublicProductUrl(item.optString("url")) != null }
    }
    suspend fun refreshAll(urls: Set<String>? = null, retainAlerts: Boolean = false, allowed: () -> Boolean = { true }): List<ProductSubscriptionAlert> = withContext(Dispatchers.IO) {
        coroutineScope {
            val slots = Semaphore(2)
            val (ruten, other) = list().filter { urls == null || it.optString("url") in urls }
                .partition { Market.fromPublicProductUrl(it.optString("url")) == Market.RUTEN }
            val tasks = ruten.chunked(40).map { batch -> async {
                slots.withPermit {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!allowed()) return@withPermit emptyList<ProductSubscriptionAlert>()
                    val rows = runCatching { RutenProductMetadata.read(batch.mapNotNull { RutenProductMetadata.productId(it.optString("url")) }.distinct()) }.getOrNull()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    batch.mapNotNull { item ->
                        val url = item.optString("url")
                        if (!prefs.contains(url) || !allowed()) null else refresh(item, RutenProductMetadata.snapshotOrUnavailable(url, rows), retainAlerts, allowed)
                    }
                }
            } } + other.map { item -> async { slots.withPermit {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!allowed()) emptyList() else {
                    val key = item.optString("url")
                    val final = item.optString("resolvedUrl").takeIf { PublicProductParser.sameProductUrl(it, key) } ?: key
                    val snapshot = readPublicSnapshot(final)
                    listOfNotNull(refresh(item, provided = snapshot, retainAlerts = retainAlerts, allowed = allowed))
                }
            } } }
            tasks.awaitAll().flatten()
        }
    }
    internal suspend fun readPublicSnapshot(raw: String): PublicProductSnapshot {
        val market = Market.fromPublicProductUrl(raw) ?: return PublicProductSnapshot(error = "商品網址無效")
        var publicPageStatus: Int? = null
        val snapshot = withContext(Dispatchers.IO) {
            try {
                if (market == Market.RUTEN) {
                    val id = RutenProductMetadata.productId(raw) ?: return@withContext PublicProductSnapshot(error = "商品編號無法辨識")
                    RutenProductMetadata.snapshot(raw, RutenProductMetadata.read(listOf(id)))
                } else {
                    val response = page(raw)
                    publicPageStatus = response.first
                    PublicProductParser.parse(raw, response.first, response.second)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { PublicProductSnapshot(error = "暫時無法檢查；保留上次狀態") }
        }
        // An explicit HTTP error is terminal; do not hide it behind another renderer attempt.
        if (publicPageStatus?.let { it != 200 } == true || snapshot.isAuction ||
            market !in setOf(Market.CAROUSELL, Market.SHOPEE, Market.FACEBOOK, Market.IOPEN, Market.YAHOO) ||
            snapshot.status != "unknown" && (market in setOf(Market.CAROUSELL, Market.YAHOO) || !snapshot.listedAt.isNullOrBlank())) return snapshot
        val rendered = PublicProductWebReader.read(context, raw)
        val preferred = if (rendered.status != "unknown" || snapshot.status == "unknown" && rendered.title != null) rendered else snapshot
        return preferred.copy(image = preferred.image ?: snapshot.image ?: rendered.image,
            listedAt = rendered.listedAt?.takeIf { it.isNotBlank() } ?: snapshot.listedAt,
            listedAtEpochMillis = if (!rendered.listedAt.isNullOrBlank()) rendered.listedAtEpochMillis else snapshot.listedAtEpochMillis,
            title = preferred.title ?: snapshot.title ?: rendered.title)
    }
    private fun refresh(item: JSONObject, provided: PublicProductSnapshot? = null, retainAlerts: Boolean = false, allowed: () -> Boolean = { true }): ProductSubscriptionAlert? {
        val url = item.optString("url")
        val snapshot = provided ?: runCatching {
            val response = page(url)
            PublicProductParser.parse(url, response.first, response.second)
        }.getOrElse { PublicProductSnapshot(error = "暫時無法檢查；保留上次狀態") }
        if (!prefs.contains(url) || !allowed()) return null
        if (snapshot.isAuction) {
            val store = AuctionStore(context)
            val canonical = AuctionParser.canonicalUrl(url)
            val schedules = AutomaticRefreshStore(context)
            val oldPlan = schedules.plan(RefreshTarget(RefreshKind.PRODUCT, url))
            val converted = runCatching {
                synchronized(SubscriptionMutations.lock) {
                    if (!prefs.contains(url) || !allowed()) return@synchronized false
                    if (store.list().none { AuctionParser.canonicalUrl(it.optString("url")) == canonical }) store.add(canonical)
                    store.update(canonical) { entry -> entry.put("title", snapshot.title ?: item.optString("title"))
                        .put("isAdult", entry.optBoolean("isAdult") || item.optBoolean("isAdult") || AdultVisibility.adultText(snapshot.title.orEmpty())) }
                    prefs.edit().remove(url).commit()
                    true
                }
            }.getOrDefault(false)
            if (converted) {
                schedules.configure(RefreshTarget(RefreshKind.PRODUCT, url), null)
                val auctionTarget = RefreshTarget(RefreshKind.AUCTION, canonical)
                if (oldPlan != null && schedules.exists(auctionTarget) && schedules.plan(auctionTarget) == null)
                    schedules.configure(auctionTarget, oldPlan.intervalMinutes)
            }
            return null
        }
        return synchronized(SubscriptionMutations.lock) {
            if (!prefs.contains(url) || !allowed()) return@synchronized null
            val latest = JSONObject(prefs.getString(url, "{}") ?: "{}")
            val alert = ProductSubscriptionDecision.apply(latest, snapshot, System.currentTimeMillis())
            if (retainAlerts && alert != null) BackgroundAlertJournal.append(latest, listOf(alert))
            if (!prefs.edit().putString(url, latest.toString()).commit()) return@synchronized null
            if (retainAlerts && alert != null) PendingAlertDelivery.enqueue(context)
            alert
        }
    }
    private fun page(raw: String): Pair<Int, String> {
        val market = Market.fromPublicProductUrl(raw) ?: error("網址無效")
        var current = raw
        repeat(4) {
            require(market.accepts(current)) { "不支援跨平台跳轉" }
            val connection = URL(current).openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 6_000; connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    current = URL(URL(current), connection.getHeaderField("Location") ?: error("跳轉缺少網址")).toString()
                    require(market.isPublicProductUrl(current)) { "商品頁要求登入或驗證" }
                } else {
                    if (code != 200) return code to ""
                    val bytes = connection.inputStream.use { reader ->
                        val result = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                        while (result.size() < 1_000_000) {
                            val n = reader.read(chunk, 0, minOf(chunk.size, 1_000_000 - result.size()))
                            if (n < 0) break
                            result.write(chunk, 0, n)
                        }
                        result.toByteArray()
                    }
                    val text = if (market == Market.BBOBO) BoboProductMetadata.decode(bytes, connection.contentType) else String(bytes, Charsets.UTF_8)
                    return code to text
                }
            } finally { connection.disconnect() }
        }
        error("跳轉次數過多")
    }
}

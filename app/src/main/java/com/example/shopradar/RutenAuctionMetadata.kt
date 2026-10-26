package com.example.shopradar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** The public GETs used by Ruten's official auction page. No account cookies or bidding actions. */
internal object RutenAuctionMetadata {
    private const val HOST = "https://auction-api.ruten.com.tw"
    private val idPattern = Regex("\\d{8,}")

    fun candidate(item: JSONObject): Boolean = item.optString("SaleType") in setOf("bid", "auction") ||
        item.optJSONObject("BidInfo") != null || images(item).any { image ->
            runCatching { URI(image).let { it.host == "gcs.rimg.com.tw" && it.path.startsWith("/auction/") } }.getOrDefault(false)
        }

    private fun images(item: JSONObject): List<String> = when (val image = item.opt("Image")) {
        is JSONArray -> (0 until image.length()).map { image.optString(it) }
        is String -> listOf(image)
        else -> emptyList()
    }
    fun image(item: JSONObject): String = images(item).firstOrNull { safeImage(it) }.orEmpty()
    private fun safeImage(raw: String): Boolean = runCatching {
        URI(raw).let { it.scheme == "https" && it.host != null && it.userInfo == null && it.port == -1 }
    }.getOrDefault(false)

    suspend fun read(id: String): JSONObject {
        require(idPattern.matches(id))
        return JSONObject(readPublic("$HOST/api/goods/$id"))
    }

    fun product(product: Product, raw: JSONObject, now: Long): Product {
        val id = RutenProductMetadata.productId(product.url) ?: error("露天商品編號無法辨識。")
        val item = data(raw, id)
        val snapshot = snapshot(raw, id, now)
        return product.copy(title = snapshot.title, price = snapshot.price,
            image = snapshot.image.ifBlank { product.image }, shop = ShopText.shop(item.optString("user_nick")),
            listedAt = item.getString("start_time"), listedAtEpochMillis = snapshot.startsAt,
            isAuction = true, auctionPriceKnown = true, auctionEndsAt = snapshot.endsAt,
            auctionServerAt = snapshot.serverAt, auctionBidCount = snapshot.bidCount,
            auctionCancelled = snapshot.cancelled, auctionEnded = snapshot.ended,
            highestBidder = snapshot.bidderIds.joinToString("、").ifBlank { if (snapshot.bidCount == 0) "尚無人出價" else "" },
            isAdult = product.isAdult || AdultVisibility.adultCategory(Market.RUTEN, item.optString("g_class")))
    }

    fun snapshot(raw: JSONObject, id: String, now: Long): AuctionSnapshot {
        val item = data(raw, id)
        val start = ShopCheckpoint.millis(item.getString("start_time"), now) ?: error("露天未提供有效起標時間。")
        val end = ShopCheckpoint.millis(item.getString("end_time"), now) ?: error("露天未提供有效結標時間。")
        require(end > start)
        val count = item.getInt("bid_count").also { require(it >= 0) }
        val status = item.getJSONObject("status").getInt("value")
        require(status in setOf(1, 3, 4, 9)) { "露天競拍狀態尚無法確認。" }
        val ended = status in setOf(3, 4) || end <= now
        // An expired countdown/current leader alone is not proof of the winner.
        val bid = if (ended) item.optJSONObject("winner") else item.optJSONObject("current")
        val amount = bid?.let { amount(it.opt("amount")) }
            ?: if (ended) item.optJSONObject("current")?.let { amount(it.opt("amount")) } else null
            ?: if (count == 0) amount(item.opt("start_price")) else null
        require(amount != null) { "露天未提供可確認的出價。" }
        val name = bid?.optString("user_nick").orEmpty().trim().takeUnless { it == "null" }.orEmpty()
        val domain = item.optString("image_domain").trimEnd('/')
        val file = item.optJSONArray("g_img")?.optString(0).orEmpty()
        val image = "$domain/$file".takeIf { safeImage(it) }.orEmpty()
        return AuctionSnapshot(ShopText.title(item.getString("g_name")), amount, end, now,
            listOf(name).filter { it.isNotBlank() }, emptyList(), count, status == 9, image,
            startsAt = start, ended = ended)
    }

    private fun data(raw: JSONObject, id: String): JSONObject {
        require(raw.optInt("code") == 200 && raw.optString("msg") == "success") { "露天未提供公開競拍資料。" }
        return raw.getJSONObject("data").also { require(it.optString("g_no") == id) { "露天回傳商品不符。" } }
    }

    private fun amount(value: Any?): Long? = value?.toString()?.toBigDecimalOrNull()?.let {
        runCatching { it.longValueExact() }.getOrNull()?.takeIf { n -> n >= 0 }
    }

    fun bidPage(raw: JSONObject, id: String): AuctionBidPage {
        val rows = raw.getJSONArray("data")
        val meta = raw.getJSONObject("meta")
        require(rows.length() <= 5 && meta.optInt("per_page") == 5 && meta.getInt("total") >= rows.length())
        val records = (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            require(row.optString("g_no") == id)
            if (!row.optBoolean("is_valid") || row.optString("bid_type") !in setOf("manual", "auto")) return@mapNotNull null
            val name = row.optString("user_nick").trim()
            val at = ShopCheckpoint.millis(row.optString("bid_time"), 0)
            val price = amount(row.opt("bid_amount"))
            if (!BidDecision.fullId(name) || at == null || price == null) return@mapNotNull null
            AuctionBidRecord(name, price, at, if (row.optString("bid_type") == "auto") 3 else 1)
        }
        return AuctionBidPage(records, rows.length(), meta.getInt("total"))
    }

    suspend fun withBidRecords(snapshot: AuctionSnapshot, raw: JSONObject, id: String, allowed: () -> Boolean): AuctionSnapshot {
        val records = mutableListOf<AuctionBidRecord>()
        var count = 0
        var complete = false
        var omissions = false
        for (page in 1..3) {
            currentCoroutineContext().ensureActive()
            if (!allowed()) throw kotlinx.coroutines.CancellationException()
            val found = bidPage(JSONObject(readPublic("$HOST/api/bids/$id?per_page=5&page=$page")), id)
            records += found.records
            omissions = omissions || found.records.size < found.count
            count += found.count
            if (count >= found.total || found.count == 0) { complete = count >= found.total && !omissions; break }
        }
        val item = data(raw, id)
        val bid = if (snapshot.ended) item.optJSONObject("winner") else item.optJSONObject("current")
        return confirmBidder(snapshot.copy(records = records, recordsComplete = complete), bid)
    }

    /** Only an exact time/price match to one public bidder can unmask the current/winning ID. */
    fun confirmBidder(snapshot: AuctionSnapshot, bid: JSONObject?): AuctionSnapshot {
        val masked = bid?.optString("user_nick").orEmpty()
        if (bid == null || BidDecision.fullId(masked)) return snapshot
        val at = ShopCheckpoint.millis(bid.optString("at"), 0) ?: return snapshot
        val price = amount(bid.opt("amount")) ?: return snapshot
        val pattern = Regex("^" + masked.split('*').joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE)
        val matches = snapshot.records.filter { it.price == price && it.at == at && pattern.matches(it.bidderId) }
            .map { it.bidderId }.distinctBy { it.lowercase() }
        return if (matches.size == 1) snapshot.copy(bidderIds = matches) else snapshot
    }

    private suspend fun readPublic(url: String): String = runInterruptible(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 4_000; connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ShopRadar/1.27 Android public-auction-monitor")
            require(connection.responseCode == 200) { "露天競拍回應 HTTP ${connection.responseCode}。" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= 1_000_000) { "露天競拍回應過大。" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
}

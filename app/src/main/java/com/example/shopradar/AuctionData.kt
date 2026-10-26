package com.example.shopradar

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class AuctionSnapshot(
    val title: String, val price: Long, val endsAt: Long, val serverAt: Long,
    val bidderIds: List<String>, val bidderNames: List<String>, val bidCount: Int,
    val cancelled: Boolean, val image: String,
    val quantity: Int = 1, val records: List<AuctionBidRecord> = emptyList(),
    val recordsComplete: Boolean = false, val startsAt: Long = 0L, val ended: Boolean = false
)

data class AuctionBidRecord(val bidderId: String, val price: Long, val at: Long, val type: Int)

internal data class AuctionBidPage(val records: List<AuctionBidRecord>, val count: Int, val total: Int)

object AuctionParser {
    fun canonicalUrl(raw: String): String {
        if (Market.fromPublicProductUrl(raw) == Market.RUTEN) {
            val id = RutenProductMetadata.productId(raw) ?: error("網址必須是露天競拍商品頁。")
            return "https://www.ruten.com.tw/item/$id/"
        }
        val uri = URI(Market.YAHOO.normalizePublicProductUrl(raw) ?: raw.trim())
        require(uri.scheme == "https" && uri.host == "tw.bid.yahoo.com" && uri.userInfo == null && uri.port == -1) {
            "請貼上 Yahoo 或露天的公開競拍商品網址。"
        }
        val id = Regex("^/item/(\\d+)/?$").matchEntire(uri.path)?.groupValues?.get(1)
            ?: error("網址必須是 Yahoo 拍賣商品頁。")
        return "https://tw.bid.yahoo.com/item/$id"
    }

    fun parse(html: String, expectedId: String, now: Long): AuctionSnapshot {
        val raw = Regex("""<script\b[^>]*\bid=["']isoredux-data["'][^>]*>([\s\S]*?)</script>""")
            .find(html)?.groupValues?.get(1) ?: error("公開頁面未提供競拍資料，可能需要驗證或頁面已改版。")
        val root = JSONObject(raw)
        val item = root.getJSONObject("item")
        require(item.optString("id") == expectedId) { "回傳商品與追蹤網址不符。" }
        require(item.optInt("type") == 2) { "這是直購商品，沒有可追蹤的競拍倒數。" }
        val bid = item.getJSONObject("bidInfo")
        val rawEnd = bid.optLong("endTime", 0)
        val end = if (rawEnd in 1..99_999_999_999L) rawEnd * 1000 else rawEnd
        require(end > 0 && bid.has("currentPrice") && !bid.isNull("currentPrice")) { "缺少價格或結標時間，暫時無法判定。" }
        val bidders = bid.optJSONArray("highestBidders") ?: JSONArray()
        val ids = (0 until bidders.length()).map { bidders.getJSONObject(it).optString("ecid") }.filter { it.isNotBlank() }
        val names = (0 until bidders.length()).map { bidders.getJSONObject(it).optString("nickname") }.filter { it.isNotBlank() }
        val rawServer = root.optJSONObject("page")?.optLong("serverTs", now) ?: now
        val server = if (rawServer in 1..99_999_999_999L) rawServer * 1000 else rawServer
        return AuctionSnapshot(item.optString("title", expectedId), bid.getLong("currentPrice"), end,
            server, ids, names,
            bid.optInt("totalBids"), bid.optBoolean("isCancelled"),
            item.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("sm")?.optString("src").orEmpty(),
            bid.optInt("totalQuantity", 1).coerceAtLeast(1))
    }

    /** Same read-only action used by Yahoo's public "詳細記錄" tab. No bidding actions. */
    internal fun bidRecordsRequest(id: String, offset: Int = 0): String {
        require(id.matches(Regex("\\d+")) && offset in 0..100)
        return JSONObject().put("type", "CALL_RESERVICE").put("rtk2", true)
            .put("payload", JSONObject().put("listingId", id).put("showAll", 1).put("offset", offset).put("limit", 50))
            .put("reservice", JSONObject().put("name", "FETCH_BID_RECORDS").put("state", "CREATED")).toString()
    }

    internal fun parseBidRecords(raw: String): AuctionBidPage {
        val root = JSONObject(raw)
        require(root.optString("type") == "FETCH_BID_RECORDS" && root.has("error") && !root.getBoolean("error")) {
            "公開出價紀錄未提供可確認資料。"
        }
        val data = root.getJSONObject("payload")
        require(data.optString("mode") == "detail") { "未取得詳細出價紀錄。" }
        val rows = data.getJSONArray("bids")
        val total = data.getInt("totalRecords")
        require(total >= rows.length() && rows.length() <= 50)
        val records = (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val type = row.optInt("type", -1)
            // 0=start, 1=bid, 3=automatic bid, 4=starting price change,
            // 6=auction cancellation, 7=bidder cancellation. 2=winner, not a new bid.
            if (type !in setOf(0, 1, 3, 4, 6, 7)) return@mapNotNull null
            val time = row.optLong("createdTs")
            require(time in 1..99_999_999_999L && row.has("price") && !row.isNull("price"))
            val price = row.getLong("price")
            require(price >= 0)
            val id = row.optJSONObject("bidder")?.optString("ecid").orEmpty().trim()
            if (type in setOf(1, 3, 7) && id.isBlank()) return@mapNotNull null
            AuctionBidRecord(id, price, time * 1000, type)
        }
        return AuctionBidPage(records, rows.length(), total)
    }
}

object BidDecision {
    internal fun fullId(id: String) = id.isNotBlank() && !id.contains('*') && !id.contains('…') && !id.contains("...")
    // Full public account IDs are authoritative. Nicknames can collide or be masked.
    fun state(s: AuctionSnapshot, account: String): String = when {
        s.cancelled -> "cancelled"
        s.ended || s.endsAt <= s.serverAt -> "ended"
        s.startsAt > s.serverAt -> "not_started"
        account.isBlank() -> "unknown"
        !fullId(account.trim()) -> "unknown"
        s.bidderIds.any { it.equals(account.trim(), true) && fullId(it) } -> "leading"
        s.bidCount == 0 -> "no_bids"
        s.bidderIds.isEmpty() || s.bidderIds.any { !fullId(it) } -> "unknown"
        else -> "other"
    }
    fun outbid(previous: String, current: String) = previous == "leading" && current == "other"
}

/** Never infer a winner from an expired, previously-active countdown snapshot. */
internal object AuctionResultText {
    fun winner(ids: List<String>, bids: Int, account: String, confirmedEnded: Boolean): String {
        if (!confirmedEnded) return "正在確認得標者…"
        val bidders = ids.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (bidders.isEmpty()) return if (bids == 0) "無人出價" else "得標者：平台未公開"
        fun fullId(id: String) = !id.contains('*') && !id.contains('…') && !id.contains("...")
        val own = account.trim().takeIf { it.isNotBlank() && fullId(it) }
        if (own != null && bidders.any { it.equals(own, true) && fullId(it) }) {
            val others = bidders.filterNot { it.equals(own, true) }
            return if (others.isEmpty()) "你已得標" else "你已得標；其他得標者：${others.joinToString("、")}"
        }
        return "得標者：${bidders.joinToString("、")}"
    }
}

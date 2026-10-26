package com.aykkdk.shop

import org.json.JSONObject

/** Display participation separately from notification acknowledgement and lead-cycle rearming. */
internal object AuctionStanding {
    fun observe(entry: JSONObject, snapshot: AuctionSnapshot, account: String, state: String) {
        val own = account.trim()
        if (!BidDecision.fullId(own)) return
        val placedBid = state == "leading" || snapshot.records.any {
            it.type in setOf(1, 3) && it.at <= snapshot.serverAt && it.price <= snapshot.price &&
                BidDecision.fullId(it.bidderId) && it.bidderId.equals(own, true)
        }
        if (placedBid) entry.put("hasPlacedBid", true)
    }

    fun text(entry: JSONObject, account: String): String {
        val state = entry.optString("displayState", "unknown")
        if (state == "not_started") return "尚未開標"
        val ids = entry.optJSONArray("bidderIds")?.let { rows ->
            (0 until rows.length()).map { rows.optString(it).trim() }.filter { it.isNotBlank() }
        }.orEmpty().distinctBy { it.lowercase() }
        val names = entry.optJSONArray("bidderNames")?.let { rows ->
            (0 until rows.length()).map { rows.optString(it).trim() }.filter { it.isNotBlank() }
        }.orEmpty().distinct()
        if (state == "no_bids" || entry.has("bidCount") && entry.optInt("bidCount") == 0 && ids.isEmpty())
            return "尚無出價"
        val own = account.trim().takeIf { BidDecision.fullId(it) }
        if (state == "leading" && own != null && ids.any { it.equals(own, true) && BidDecision.fullId(it) })
            return "你（$own）目前領先"
        val leader = (ids.ifEmpty { names }).joinToString("、")
        if (leader.isBlank()) return "暫無法確認領先者"
        val sameAccount = own != null && entry.optString("monitorAccount").equals(own, true)
        // Legacy lead-cycle fields are confirmed evidence, even after the alert was acknowledged.
        val participated = sameAccount && (entry.optBoolean("hasPlacedBid") || entry.optString("leadKey").isNotBlank())
        return if (state == "other" && participated && ids.isNotEmpty() && ids.all { BidDecision.fullId(it) } &&
            ids.none { it.equals(own, true) }) "被（$leader）超過" else "（$leader）領先"
    }
}

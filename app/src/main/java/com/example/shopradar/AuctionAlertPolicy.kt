package com.example.shopradar

import org.json.JSONObject
import java.util.UUID

internal data class AuctionAlertNotice(val url: String, val token: String, val title: String, val price: Long)

/** Acknowledging a loss does not reset its bid-cycle identity. All fields survive app restarts. */
internal object AuctionAlertPolicy {
    private val cycleFields = listOf("leadKey", "leadPrice", "leadAt", "notifiedLeadKey", "outbidOtherPrice")

    fun reset(entry: JSONObject, account: String) {
        cycleFields.forEach(entry::remove)
        entry.remove("hasPlacedBid")
        entry.put("monitorAccount", account.trim().lowercase())
        clearPending(entry)
    }

    fun pending(entry: JSONObject): AuctionAlertNotice? {
        if (!entry.optBoolean("alertsEnabled", true) || entry.optString("alert").isBlank() ||
            entry.optString("displayState") in setOf("ended", "cancelled")) return null
        val token = entry.optString("alertToken").ifBlank { legacyToken(entry) }
        if (entry.optString("acknowledgedAlertToken") == token) return null
        return AuctionAlertNotice(entry.optString("url"), token, entry.optString("title"),
            entry.optLong("alertPrice", entry.optLong("price")))
    }

    fun acknowledge(entry: JSONObject, token: String): Boolean {
        if (pending(entry)?.token != token) return false // An older dialog cannot acknowledge a newer loss.
        entry.put("acknowledgedAlertToken", token)
        clearPending(entry)
        return true
    }

    fun clearPending(entry: JSONObject) {
        entry.put("alert", "").remove("alertToken")
    }

    fun needsSystemNotification(entry: JSONObject): Boolean = pending(entry)?.let {
        it.token != entry.optString("systemDeliveredAlertToken")
    } ?: false

    fun systemNotificationSent(entry: JSONObject, token: String): Boolean {
        if (pending(entry)?.token != token) return false
        entry.put("systemDeliveredAlertToken", token)
        return true
    }

    /** Called under AuctionStore's mutation lock, BEFORE replacing the previous price/time. */
    fun observe(entry: JSONObject, snapshot: AuctionSnapshot, account: String, checkedAt: Long): Boolean {
        val own = account.trim().lowercase()
        var previousState = entry.optString("state", "unknown")
        if (entry.has("monitorAccount") && entry.optString("monitorAccount") != own) {
            reset(entry, own)
            previousState = "unknown"
            entry.put("state", "unknown")
        }
        else if (!entry.has("monitorAccount")) {
            entry.put("monitorAccount", own)
            // Migrate an outstanding alert from an earlier installed version without re-notifying it.
            if (entry.optString("alert").isNotBlank()) {
                val key = "legacy:${entry.optLong("alertAt")}:${entry.optLong("price")}"
                entry.put("leadKey", key).put("notifiedLeadKey", key).put("leadPrice", entry.optLong("price"))
                    .put("leadAt", entry.optLong("alertAt")).put("outbidOtherPrice", entry.optLong("price"))
                    .put("alertToken", legacyToken(entry))
            }
        }
        var leadKey = entry.optString("leadKey")
        if (leadKey.isBlank() && previousState == "leading" && own.isNotBlank()) {
            leadKey = "observed:${entry.optLong("serverAt", entry.optLong("checkedAt"))}:${entry.optLong("price")}"
            entry.put("leadKey", leadKey).put("leadPrice", entry.optLong("price"))
                .put("leadAt", entry.optLong("serverAt", entry.optLong("checkedAt")))
        }
        val state = BidDecision.state(snapshot, account)
        AuctionStanding.observe(entry, snapshot, account, state)
        if (state != "unknown") entry.put("state", state)
        entry.put("displayState", state)
        if (state in setOf("ended", "cancelled", "no_bids", "not_started")) {
            cycleFields.forEach(entry::remove)
            clearPending(entry)
            return false
        }
        if (state == "unknown") return false // Masked IDs and failed reads never re-arm a cycle.
        if (snapshot.records.any { it.type == 6 && it.at <= snapshot.serverAt }) {
            cycleFields.forEach(entry::remove)
            clearPending(entry)
            return false
        }
        val ownLead = newestPublicLead(snapshot, own, entry)
        if (state == "leading") {
            if (previousState != "leading" || leadKey.isBlank() || snapshot.price > entry.optLong("leadPrice")) {
                entry.put("leadKey", ownLead?.key() ?: "observed:${snapshot.serverAt}:${snapshot.price}")
                    .put("leadPrice", snapshot.price).put("leadAt", ownLead?.at ?: snapshot.serverAt)
            }
            clearPending(entry)
            return false
        }
        // A cancelled bid is not an opponent outbidding the user.
        if (snapshot.records.any { it.type == 7 && it.bidderId.equals(own, true) &&
                    it.at <= snapshot.serverAt && it.at + 1000 >= entry.optLong("leadAt") &&
                    (ownLead == null || it.at >= ownLead.at) }) {
            entry.remove("leadKey")
            clearPending(entry)
            return false
        }
        if (ownLead != null && (leadKey.isBlank() ||
                ownLead.price > entry.optLong("leadPrice") && ownLead.price > entry.optLong("outbidOtherPrice") &&
                ownLead.at + 1000 >= entry.optLong("leadAt"))) {
            leadKey = ownLead.key()
            entry.put("leadKey", leadKey).put("leadPrice", ownLead.price).put("leadAt", ownLead.at)
        }
        if (leadKey.isBlank() || leadKey == entry.optString("notifiedLeadKey") || snapshot.price <= entry.optLong("leadPrice")) return false
        entry.put("notifiedLeadKey", leadKey).put("outbidOtherPrice", snapshot.price)
        if (!entry.optBoolean("alertsEnabled", true)) return false
        entry.put("alert", "你的出價已被超過，請開啟商品確認。")
            .put("alertToken", UUID.randomUUID().toString()).put("alertAt", checkedAt).put("alertPrice", snapshot.price)
        return true
    }

    private fun legacyToken(entry: JSONObject) = "legacy:${entry.optString("url")}:${entry.optLong("alertAt")}" 
    private fun AuctionBidRecord.key() = "bid:$at:$price:$type"

    private fun newestPublicLead(snapshot: AuctionSnapshot, own: String, previous: JSONObject): AuctionBidRecord? {
        // A multi-quantity auction cannot be reconstructed from a single highest-price sequence.
        if (snapshot.quantity != 1 || snapshot.records.isEmpty() || !BidDecision.fullId(own) ||
            snapshot.records.any { it.at > snapshot.serverAt || it.type in setOf(1, 3) && it.price > snapshot.price }) return null
        val floor = previous.optLong("serverAt") / 1000 * 1000
        if (!snapshot.recordsComplete && (!previous.has("price") || floor <= 0)) return null
        var highest = if (snapshot.recordsComplete) 0L else previous.optLong("price")
        var leader = if (snapshot.recordsComplete) "" else previous.optJSONArray("bidderIds")?.optString(0).orEmpty()
        val cancelled = snapshot.records.filter { it.type == 7 }.groupBy { it.bidderId.lowercase() }
            .mapValues { (_, rows) -> rows.maxOf { it.at } }
        var lead: AuctionBidRecord? = null
        // Yahoo returns newest first; reverse equal-time rows as well, preserving automatic-bid order.
        val ordered = snapshot.records.withIndex().sortedWith(compareBy({ it.value.at }, { -it.index }))
        for ((_, row) in ordered) {
            if (!snapshot.recordsComplete && row.at < floor) continue
            if (row.type == 6) return null
            if (row.type in setOf(0, 4)) { highest = row.price; leader = ""; continue }
            if (row.type !in setOf(1, 3) || row.at <= (cancelled[row.bidderId.lowercase()] ?: -1L)) continue
            if (row.price > highest || leader.isBlank() && row.price == highest) {
                highest = row.price
                leader = row.bidderId
                if (BidDecision.fullId(leader) && leader.equals(own, true)) lead = row
            }
        }
        return lead
    }
}

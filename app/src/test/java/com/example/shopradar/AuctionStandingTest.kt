package com.example.shopradar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuctionStandingTest {
    private val own = "Y9300266938"
    private val other = "Y9171752016"
    private fun snapshot(leader: String = other, records: List<AuctionBidRecord> = emptyList()) =
        AuctionSnapshot("手機", 321, 20_000, 10_000, listOf(leader), listOf("相同暱稱"), 6, false, "",
            records = records, recordsComplete = true)
    private fun read(entry: JSONObject, snapshot: AuctionSnapshot, account: String = own): JSONObject {
        AuctionAlertPolicy.observe(entry, snapshot, account, snapshot.serverAt)
        entry.put("bidderIds", JSONArray(snapshot.bidderIds)).put("bidderNames", JSONArray(snapshot.bidderNames))
            .put("bidCount", snapshot.bidCount).put("price", snapshot.price).put("serverAt", snapshot.serverAt)
        return entry
    }

    @Test fun neverObservedOwnBidShowsNamedLeader() {
        val entry = read(JSONObject(), snapshot(records = listOf(AuctionBidRecord(other, 321, 9_000, 1))))
        assertEquals("（$other）領先", AuctionStanding.text(entry, own))
        assertFalse(entry.optBoolean("hasPlacedBid"))
    }

    @Test fun currentOwnLeadIncludesConfiguredAccountAndPersistsParticipation() {
        val entry = read(JSONObject(), snapshot(own), " $own ")
        assertEquals("你（$own）目前領先", AuctionStanding.text(entry, " $own "))
        assertTrue(entry.optBoolean("hasPlacedBid"))
    }

    @Test fun aPriorObservedLeadShowsOutbidWithoutNeedingPublicHistory() {
        val entry = read(JSONObject(), snapshot(own).copy(price = 100))
        read(entry, snapshot())
        assertEquals("被（$other）超過", AuctionStanding.text(entry, own))
    }

    @Test fun anOwnLosingBidAlsoCountsEvenWithoutEverLeading() {
        val entry = read(JSONObject(), snapshot(records = listOf(
            AuctionBidRecord(other, 321, 9_500, 1), AuctionBidRecord(own, 100, 9_000, 1),
            AuctionBidRecord(other, 300, 8_000, 1))))
        assertEquals("被（$other）超過", AuctionStanding.text(entry, own))
        // Display participation does not invent an outbid notification/previous lead cycle.
        assertNull(AuctionAlertPolicy.pending(entry))
        assertFalse(entry.has("leadKey"))
    }

    @Test fun restartAndAcknowledgementDoNotEraseParticipation() {
        val entry = read(JSONObject(), snapshot(own).copy(price = 100))
        read(entry, snapshot())
        val notice = AuctionAlertPolicy.pending(entry)!!
        assertTrue(AuctionAlertPolicy.acknowledge(entry, notice.token))
        val restored = JSONObject(entry.toString())
        read(restored, snapshot())
        assertEquals("被（$other）超過", AuctionStanding.text(restored, own))
        assertNull(AuctionAlertPolicy.pending(restored))
    }

    @Test fun changingAccountClearsThePreviousUsersParticipation() {
        val entry = read(JSONObject(), snapshot(own))
        read(entry, snapshot(), "Y123456")
        assertFalse(entry.optBoolean("hasPlacedBid"))
        assertEquals("（$other）領先", AuctionStanding.text(entry, "Y123456"))
        assertEquals("（$other）領先", AuctionStanding.text(entry.put("hasPlacedBid", true), own))
    }

    @Test fun nicknamesMaskedAccountsAndNonBidRecordTypesNeverProveParticipation() {
        for (record in listOf(AuctionBidRecord("Y9300***", 100, 9_000, 1),
            AuctionBidRecord("相同暱稱", 100, 9_000, 1), AuctionBidRecord(own, 100, 11_000, 1),
            AuctionBidRecord(own, 999, 9_000, 1)) + listOf(0,4,6,7).map { AuctionBidRecord(own, 100, 9_000, it) }) {
            val entry = read(JSONObject(), snapshot(records = listOf(record)))
            assertFalse(record.toString(), entry.optBoolean("hasPlacedBid"))
            assertEquals("（$other）領先", AuctionStanding.text(entry, own))
        }
    }

    @Test fun automaticBiddingAndCaseInsensitiveFullIdsCount() {
        val entry = read(JSONObject(), snapshot(records = listOf(AuctionBidRecord(own.lowercase(), 100, 9_000, 3))))
        assertEquals("被（$other）超過", AuctionStanding.text(entry, own.lowercase()))
    }

    @Test fun maskedLeaderAndMissingHistoryAreNotProofOfBeingOutbid() {
        val entry = read(JSONObject(), snapshot(own))
        read(entry, snapshot("Y917***"))
        assertEquals("（Y917***）領先", AuctionStanding.text(entry, own))
        assertEquals("暫無法確認領先者", AuctionStanding.text(JSONObject(), own))
        assertEquals("（$other）領先", AuctionStanding.text(read(JSONObject(), snapshot(), ""), ""))
    }

    @Test fun zeroBidsAndNotStartedHaveOneShortStatus() {
        val empty = read(JSONObject(), snapshot().copy(bidCount = 0, bidderIds = emptyList(), bidderNames = emptyList()))
        assertEquals("尚無出價", AuctionStanding.text(empty, own))
        val notStarted = read(JSONObject(), snapshot().copy(startsAt = 15_000))
        assertEquals("尚未開標", AuctionStanding.text(notStarted, own))
    }

    @Test fun legacyConfirmedLeadCycleMigratesWithoutRequiringFreshParticipationFlag() {
        val entry = JSONObject().put("displayState", "other").put("monitorAccount", own.lowercase())
            .put("leadKey", "observed:1:100").put("bidderIds", JSONArray(listOf(other))).put("bidCount", 3)
        assertEquals("被（$other）超過", AuctionStanding.text(entry, own))
    }

    @Test fun multipleLeadersRemainVisibleAndOneOwnWinnerCountsAsLeading() {
        val entry = read(JSONObject(), snapshot().copy(bidderIds = listOf(other, "Y200")))
        assertEquals("（$other、Y200）領先", AuctionStanding.text(entry, own))
        read(entry, snapshot().copy(bidderIds = listOf(own, other)))
        assertEquals("你（$own）目前領先", AuctionStanding.text(entry, own))
    }
}

package com.example.shopradar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuctionAlertPolicyTest {
    private val base = 1_790_000_000_000L
    private val own = "Y100"
    private val other = "Y200"
    private fun entry() = JSONObject().put("url", "https://tw.bid.yahoo.com/item/123")
        .put("title", "測試競拍").put("state", "unknown").put("alertsEnabled", true)
    private fun bid(id: String, price: Long, second: Int, type: Int = 1) =
        AuctionBidRecord(id, price, base + second * 1000L, type)
    private fun snapshot(price: Long, leader: String, second: Int,
                         history: List<AuctionBidRecord> = emptyList(), complete: Boolean = false) =
        AuctionSnapshot("測試競拍", price, base + 1000_000, base + second * 1000L,
            listOf(leader), emptyList(), 1, false, "", records = history.reversed(), recordsComplete = complete)
    private fun read(entry: JSONObject, snapshot: AuctionSnapshot, account: String = own): Boolean {
        val notified = AuctionAlertPolicy.observe(entry, snapshot, account, snapshot.serverAt + 100)
        entry.put("price", snapshot.price).put("serverAt", snapshot.serverAt).put("checkedAt", snapshot.serverAt + 100)
            .put("bidderIds", JSONArray(snapshot.bidderIds))
        return notified
    }
    private fun acknowledge(entry: JSONObject): String {
        val token = AuctionAlertPolicy.pending(entry)!!.token
        assertTrue(AuctionAlertPolicy.acknowledge(entry, token))
        assertNull(AuctionAlertPolicy.pending(entry))
        return token
    }

    @Test fun acknowledgesOnlyOnceEvenAfterSerializationAndOpponentPriceChanges() {
        val item = entry()
        assertFalse(read(item, snapshot(400, own, 10)))
        assertTrue(read(item, snapshot(500, other, 20)))
        val notice = AuctionAlertPolicy.pending(item)!!
        assertFalse(read(item, snapshot(500, other, 30)))
        assertEquals(notice, AuctionAlertPolicy.pending(item))
        acknowledge(item)
        val restored = JSONObject(item.toString())
        for (price in listOf(500L, 550L, 600L)) {
            assertFalse(read(restored, snapshot(price, other, 40)))
            assertNull(AuctionAlertPolicy.pending(restored))
        }
    }
    @Test fun aConfirmedNewLeadRearmsButAnOlderDialogCannotAcknowledgeItsNewLoss() {
        val item = entry()
        read(item, snapshot(400, own, 10))
        assertTrue(read(item, snapshot(500, other, 20)))
        val oldToken = acknowledge(item)
        assertFalse(read(item, snapshot(700, own, 30)))
        assertTrue(read(item, snapshot(800, other, 40)))
        val fresh = AuctionAlertPolicy.pending(item)!!
        assertNotEquals(oldToken, fresh.token)
        assertFalse(AuctionAlertPolicy.acknowledge(item, oldToken))
        assertEquals(fresh, AuctionAlertPolicy.pending(item))
    }
    @Test fun fullHistoryFindsAWinningAndLosingBidBetweenPolls() {
        val item = entry()
        val first = listOf(bid("", 100, 0, 0), bid(own, 400, 1), bid(other, 500, 2))
        assertTrue(read(item, snapshot(500, other, 10, first, true)))
        acknowledge(item)
        val next = first + listOf(bid(own, 700, 11), bid(other, 800, 12))
        assertTrue(read(item, snapshot(800, other, 20, next, true)))
        acknowledge(item)
        assertFalse(read(item, snapshot(900, other, 30, next + bid(other, 900, 21), true)))
    }
    @Test fun biddingEqualToOrBelowAnOpponentDoesNotRearm() {
        for (amount in listOf(450L, 500L)) {
            val item = entry()
            val first = listOf(bid("", 100, 0, 0), bid(own, 400, 1), bid(other, 500, 2))
            assertTrue(read(item, snapshot(500, other, 10, first, true)))
            acknowledge(item)
            assertFalse(read(item, snapshot(600, other, 20, first + listOf(bid(own, amount, 11), bid(other, 600, 12)), true)))
        }
    }
    @Test fun yahooNewestFirstEqualSecondProxyBidsKeepTheirOrderAndPriority() {
        val item = entry()
        val first = listOf(bid("", 99, 0, 0), bid(other, 99, 1), bid(own, 229, 2),
            bid(other, 309, 2, 3), bid(own, 309, 2, 3), bid(other, 329, 2, 3))
        assertTrue(read(item, snapshot(329, other, 10, first, true)))
        acknowledge(item)
        val tied = first + listOf(bid(own, 329, 11), bid(other, 360, 11, 3), bid(own, 360, 11, 3), bid(other, 380, 11, 3))
        assertFalse(read(item, snapshot(380, other, 20, tied, true)))
        val surpassed = tied + listOf(bid(own, 400, 21), bid(other, 420, 21, 3))
        assertTrue(read(item, snapshot(420, other, 30, surpassed, true)))
    }
    @Test fun missingMaskedOrFutureHistoryNeverInventsALead() {
        assertFalse(read(entry(), snapshot(500, other, 10)))
        assertFalse(read(entry(), snapshot(500, other, 10, listOf(bid("Y1***", 400, 1), bid(other, 500, 2)), true)))
        assertFalse(read(entry(), snapshot(500, other, 10, listOf(bid(own, 400, 11)), true)))
        assertFalse(read(entry(), snapshot(500, other, 10, listOf(bid(own, 600, 1)), true)))
        assertFalse(read(entry(), snapshot(500, other, 10, listOf(bid(own, 400, 1)), false)))
    }
    @Test fun partialHistoryCanUseThePreviousSnapshotButNotOlderRows() {
        val item = entry()
        read(item, snapshot(400, own, 10))
        assertTrue(read(item, snapshot(500, other, 20)))
        acknowledge(item)
        val recent = listOf(bid(own, 700, 21), bid(other, 800, 22))
        assertTrue(read(item, snapshot(800, other, 30, recent)))
        val blank = entry().put("price", 500).put("serverAt", base + 20_000).put("bidderIds", JSONArray(listOf(other)))
        assertFalse(read(blank, snapshot(500, other, 30, listOf(bid(own, 400, 1), bid(other, 500, 2)))))
    }
    @Test fun cancellationIsNotAnOutbidButRebiddingAfterCancellationCanStartANewCycle() {
        val item = entry()
        read(item, snapshot(400, own, 10))
        assertFalse(read(item, snapshot(500, other, 20, listOf(bid(own, 400, 1), bid(other, 500, 2), bid(own, 400, 11, 7)), true)))
        assertNull(AuctionAlertPolicy.pending(item))
        val rebid = listOf(bid("", 100, 0, 0), bid(own, 400, 1), bid(other, 500, 2),
            bid(own, 400, 11, 7), bid(own, 700, 21), bid(other, 800, 22))
        assertTrue(read(item, snapshot(800, other, 30, rebid, true)))
        val missedIntermediate = entry()
        read(missedIntermediate, snapshot(400, own, 10))
        assertTrue(read(missedIntermediate, snapshot(800, other, 30, rebid, true)))
        val second = entry()
        read(second, snapshot(400, own, 10))
        assertFalse(read(second, snapshot(500, other, 20, listOf(bid("", 0, 11, 6)), true)))
    }
    @Test fun endedCancelledOrNoBidSnapshotsClearPendingAlertsAndCycleState() {
        for (kind in listOf("ended", "cancelled", "no_bids")) {
            val item = entry()
            read(item, snapshot(400, own, 10))
            assertTrue(read(item, snapshot(500, other, 20)))
            val current = snapshot(500, other, 30).let { when (kind) {
                "ended" -> it.copy(endsAt = it.serverAt)
                "cancelled" -> it.copy(cancelled = true)
                else -> it.copy(bidCount = 0, bidderIds = emptyList())
            } }
            assertFalse(read(item, current))
            assertNull(AuctionAlertPolicy.pending(item))
            assertFalse(item.has("leadKey"))
        }
    }
    @Test fun unknownIdsPreserveTheLastConfirmedCycleWithoutRearming() {
        val item = entry()
        read(item, snapshot(400, own, 10))
        assertFalse(read(item, snapshot(500, "Y2***", 20)))
        assertEquals("unknown", item.optString("displayState"))
        assertEquals("leading", item.optString("state"))
        assertTrue(read(item, snapshot(500, other, 30)))
        acknowledge(item)
        assertFalse(read(item, snapshot(600, "Y2***", 40)))
        assertFalse(read(item, snapshot(600, other, 50)))
    }
    @Test fun switchingAccountResetsOldLeadingStateAndNotifications() {
        val item = entry()
        read(item, snapshot(400, own, 10))
        assertFalse(read(item, snapshot(500, other, 20), "Y300"))
        assertNull(AuctionAlertPolicy.pending(item))
        assertFalse(item.has("leadKey"))
        assertFalse(read(item, snapshot(600, "Y3***", 30), "Y3***"))
        assertEquals("unknown", item.optString("state"))
    }
    @Test fun turningAlertsBackOnDoesNotReplayAnOldLoss() {
        val item = entry().put("alertsEnabled", false)
        read(item, snapshot(400, own, 10))
        assertFalse(read(item, snapshot(500, other, 20)))
        assertNull(AuctionAlertPolicy.pending(item))
        item.put("alertsEnabled", true)
        assertFalse(read(item, snapshot(500, other, 30)))
        read(item, snapshot(600, own, 40))
        assertTrue(read(item, snapshot(700, other, 50)))
    }
    @Test fun legacyPendingAlertsMigrateWithoutRepeatingAfterAcknowledgement() {
        val item = entry().put("state", "other").put("price", 500).put("alert", "已被超標")
            .put("alertAt", base + 10000)
        assertFalse(read(item, snapshot(500, other, 20)))
        assertNotNull(AuctionAlertPolicy.pending(item))
        acknowledge(item)
        assertFalse(read(JSONObject(item.toString()), snapshot(600, other, 30)))
    }
    @Test fun multiQuantityHistoryIsNotMistakenForASingleWinnerSequence() {
        val s = snapshot(500, other, 10, listOf(bid(own, 400, 1), bid(other, 500, 2)), true).copy(quantity = 2)
        assertFalse(read(entry(), s))
        val item = entry()
        read(item, s.copy(price = 400, bidderIds = listOf(own, other)))
        assertTrue(read(item, s.copy(serverAt = base + 20_000)))
    }
    @Test fun foregroundPolicySkipsOnlyConfirmedFinalAuctions() {
        val item = entry().put("endsAt", 1).put("displayState", "other")
        assertTrue(ForegroundAuctionPolicy.active(item)) // An expired local countdown still needs a server check.
        assertFalse(ForegroundAuctionPolicy.active(item.put("displayState", "ended")))
        assertFalse(ForegroundAuctionPolicy.active(entry().put("cancelled", true)))
        assertFalse(ForegroundAuctionPolicy.active(JSONObject()))
    }
    @Test fun foregroundObservedLossIsStillPendingForBackgroundSystemDeliveryOnce() {
        val saved = entry()
        read(saved, snapshot(400, own, 10))
        assertTrue(read(saved, snapshot(500, other, 20)))
        val restored = JSONObject(saved.toString())
        assertFalse(read(restored, snapshot(500, other, 30)))
        assertTrue(AuctionAlertPolicy.needsSystemNotification(restored))
        val token = AuctionAlertPolicy.pending(restored)!!.token
        assertFalse(AuctionAlertPolicy.systemNotificationSent(restored, "older-token"))
        assertTrue(AuctionAlertPolicy.needsSystemNotification(restored))
        assertTrue(AuctionAlertPolicy.systemNotificationSent(restored, token))
        assertFalse(AuctionAlertPolicy.needsSystemNotification(JSONObject(restored.toString())))
        assertNotNull(AuctionAlertPolicy.pending(restored)) // The in-app acknowledgement is separate.
        acknowledge(restored)
        assertFalse(AuctionAlertPolicy.needsSystemNotification(restored))
    }
    @Test fun aNewWinningCycleCanSendANewSystemNotificationAfterAcknowledgement() {
        val saved = entry()
        read(saved, snapshot(400, own, 10)); read(saved, snapshot(500, other, 20))
        AuctionAlertPolicy.systemNotificationSent(saved, AuctionAlertPolicy.pending(saved)!!.token)
        acknowledge(saved)
        read(saved, snapshot(700, own, 30)); read(saved, snapshot(800, other, 40))
        assertTrue(AuctionAlertPolicy.needsSystemNotification(saved))
    }
}

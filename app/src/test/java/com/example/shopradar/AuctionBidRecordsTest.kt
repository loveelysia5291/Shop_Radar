package com.example.shopradar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuctionBidRecordsTest {
    private fun row(type: Int, id: String?, price: Long = 500, time: Long = 1_790_000_000) =
        JSONObject().put("type", type).put("price", price).put("createdTs", time)
            .put("bidder", id?.let { JSONObject().put("ecid", it).put("nickname", "不可當作帳號") } ?: JSONObject.NULL)
    private fun response(rows: List<JSONObject>, total: Int = rows.size) =
        JSONObject().put("type", "FETCH_BID_RECORDS").put("error", false)
            .put("payload", JSONObject().put("mode", "detail").put("totalRecords", total).put("bids", JSONArray(rows)))
    @Test fun requestUsesOnlyTheReadOnlyPublicDetailedRecordAction() {
        val request = JSONObject(AuctionParser.bidRecordsRequest("101764768005", 50))
        assertEquals("CALL_RESERVICE", request.getString("type"))
        assertEquals("FETCH_BID_RECORDS", request.getJSONObject("reservice").getString("name"))
        assertEquals("CREATED", request.getJSONObject("reservice").getString("state"))
        val payload = request.getJSONObject("payload")
        assertEquals("101764768005", payload.getString("listingId"))
        assertEquals(1, payload.getInt("showAll"))
        assertEquals(50, payload.getInt("offset"))
        assertEquals(50, payload.getInt("limit"))
        assertTrue(request.getBoolean("rtk2"))
        assertFalse(payload.has("price"))
        assertFalse(payload.has("bidder"))
    }
    @Test fun requestRejectsInvalidIdsOrOffsets() {
        for (id in listOf("", "../login", "123?price=500", "Y123"))
            assertThrows(Exception::class.java) { AuctionParser.bidRecordsRequest(id) }
        for (offset in listOf(-1, 101, Int.MAX_VALUE))
            assertThrows(Exception::class.java) { AuctionParser.bidRecordsRequest("123", offset) }
    }
    @Test fun parsesObservedNewestFirstSchemaAndDoesNotTreatAWinnerAsANewBid() {
        val raw = response(listOf(row(2, "Y100", 500), row(3, "Y200", 500), row(1, "Y100", 400), row(0, null, 99)), 19)
        val page = AuctionParser.parseBidRecords(raw.toString())
        assertEquals(4, page.count) // Pagination counts raw rows, not only accepted actions.
        assertEquals(19, page.total)
        assertEquals(listOf(3, 1, 0), page.records.map { it.type })
        assertEquals(listOf("Y200", "Y100", ""), page.records.map { it.bidderId })
        assertEquals(1_790_000_000_000L, page.records.first().at)
        assertEquals(500L, page.records.first().price)
    }
    @Test fun incompleteOrUnknownBidderRowsDoNotBecomeOwnBids() {
        val page = AuctionParser.parseBidRecords(response(listOf(row(1, null), row(3, ""), row(5, "Y100"),
            row(7, "Y100"), row(1, "Y1***"))).toString())
        assertEquals(5, page.count)
        assertEquals(listOf(7, 1), page.records.map { it.type })
        assertEquals("Y1***", page.records.last().bidderId)
        assertFalse(BidDecision.fullId(page.records.last().bidderId))
    }
    @Test fun rejectsErroredSummaryMalformedOrOversizedResponses() {
        val raw = response(listOf(row(1, "Y100")))
        assertThrows(Exception::class.java) { AuctionParser.parseBidRecords(raw.put("error", true).toString()) }
        raw.put("error", false).getJSONObject("payload").put("mode", "simple")
        assertThrows(Exception::class.java) { AuctionParser.parseBidRecords(raw.toString()) }
        assertThrows(Exception::class.java) { AuctionParser.parseBidRecords("<html>登入</html>") }
        assertThrows(Exception::class.java) { AuctionParser.parseBidRecords(response(List(51) { row(1, "Y100") }).toString()) }
        assertThrows(Exception::class.java) { AuctionParser.parseBidRecords(response(listOf(row(1, "Y100")), 0).toString()) }
    }
    @Test fun rejectsInvalidPriceOrTimestamp() {
        for (row in listOf(row(1, "Y100", -1), row(1, "Y100", time = 0), row(1, "Y100", time = Long.MAX_VALUE),
            row(1, "Y100").put("price", JSONObject.NULL)))
            assertThrows(Exception::class.java) { AuctionParser.parseBidRecords(response(listOf(row)).toString()) }
    }
    @Test fun zeroRowsAreValidButDoNotFabricateHistory() {
        val page = AuctionParser.parseBidRecords(response(emptyList(), 50).toString())
        assertEquals(0, page.count)
        assertEquals(50, page.total)
        assertTrue(page.records.isEmpty())
    }
}

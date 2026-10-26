package com.example.shopradar

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YahooListingTimeTest {
    private val at = 1791000000000L
    private val url = "https://tw.bid.yahoo.com/item/101234567894"
    private fun html(item: JSONObject) = "<script id=\"isoredux-data\">${JSONObject().put("item", item)}</script>"
    private fun item() = JSONObject().put("id", "101234567894").put("title", "手機").put("price", 100).put("type", 1)

    @Test fun secondsAndMillisecondsUseTaipeiTime() {
        val seconds = PublicProductParser.yahooListingTime(item().put("startTime", at / 1000), at)!!
        val millis = PublicProductParser.yahooListingTime(item().put("startTime", at), at)!!
        assertEquals(seconds, millis)
        assertEquals(at, seconds.second)
        assertEquals(19, seconds.first.length)
    }
    @Test fun stockAndAuctionPreservePublicStartTimestamp() {
        for (type in listOf(1, 2)) {
            val parsed = PublicProductParser.parse(url, 200, html(item().put("type", type).put("startTime", at / 1000)))
            assertEquals(at, parsed.listedAtEpochMillis)
            assertNotNull(parsed.listedAt)
            assertEquals(type == 2, parsed.isAuction)
            assertEquals("unknown", parsed.status)
        }
    }
    @Test fun bidStartFallbackButNeverEndModifiedOrAnotherItem() {
        val bid = item().put("bidInfo", JSONObject().put("startTime", at))
        assertEquals(at, PublicProductParser.yahooListingTime(bid, at)!!.second)
        assertNull(PublicProductParser.yahooListingTime(item().put("endTime", at).put("dateModified", at), at))
        assertNull(PublicProductParser.parse(url, 200, html(item().put("id", "101234567895").put("startTime", at))).listedAt)
        assertNull(PublicProductParser.parse(url, 403, html(item().put("startTime", at))).listedAt)
    }
    @Test fun invalidMissingAndFutureTimesStayUnknown() {
        for (value in listOf("", "null", "abc", "1", "1791000000.1", "978307200", (at + 61000).toString()))
            assertNull(PublicProductParser.yahooListingTime(item().put("startTime", value), at))
    }
}

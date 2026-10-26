package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RutenAuctionMetadataTest {
    private val id = "62641000008276"
    private val url = "https://www.ruten.com.tw/item/$id/"
    private val start = ShopCheckpoint.millis("2026-10-07 12:00:00", 0)!!
    private val now = ShopCheckpoint.millis("2026-10-07 15:00:00", 0)!!
    private val bidAt = ShopCheckpoint.millis("2026-10-07 14:42:39", 0)!!
    private fun bid(name: String = "g***o", price: Long = 300) = JSONObject()
        .put("amount", price).put("user_nick", name).put("at", "2026-10-07 14:42:39")
    private fun response() = JSONObject().put("code", 200).put("msg", "success").put("data", JSONObject()
        .put("g_no", id).put("g_name", "測試競拍手機").put("g_class", "00210001").put("user_nick", "fixture_shop")
        .put("start_time", "2026-10-07 12:00:00").put("end_time", "2026-10-12 00:00:00")
        .put("start_price", 1).put("bid_count", 1).put("current", bid()).put("winner", JSONObject.NULL)
        .put("status", JSONObject().put("value", 1)).put("image_domain", "https://gcs.rimg.com.tw/auction/main/fixture_shop")
        .put("g_img", JSONArray(listOf("fixture.png"))))
    private fun metadata() = JSONObject().put("ProdId", id).put("ProdName", "測試競拍手機")
        .put("PriceRange", JSONArray(listOf(300, 300))).put("Currency", "TWD").put("SaleType", "buy")
        .put("Image", JSONArray(listOf("https://gcs.rimg.com.tw/auction/main/fixture_shop/fixture.png/")))
    @Test fun genericBuyMetadataStillFindsTheOfficialAuctionImage() {
        val raw = metadata()
        assertTrue(RutenAuctionMetadata.candidate(raw))
        val product = RutenSearch.metadataProduct(raw)!!
        assertTrue(product.isAuction)
        assertFalse(product.auctionPriceKnown)
        assertTrue(product.image.endsWith("fixture.png/"))
        raw.put("Image", "https://gcs.rimg.com.tw/g5/fixture.jpg")
        assertFalse(RutenAuctionMetadata.candidate(raw))
        assertEquals("https://gcs.rimg.com.tw/g5/fixture.jpg", RutenAuctionMetadata.image(raw))
    }
    @Test fun correctAuctionApiSuppliesCurrentPriceTimeAndMaskedLeader() {
        val result = RutenAuctionMetadata.snapshot(response(), id, now)
        assertEquals(300L, result.price)
        assertEquals(start, result.startsAt)
        assertEquals(listOf("g***o"), result.bidderIds)
        assertEquals("unknown", BidDecision.state(result, "guest_fixture_o"))
        val product = RutenAuctionMetadata.product(RutenSearch.metadataProduct(metadata())!!, response(), now)
        assertTrue(product.auctionPriceKnown)
        assertTrue(product.isAuction)
        assertEquals(start, product.listedAtEpochMillis)
        assertEquals("fixture_shop", product.shop)
        assertEquals("g***o", product.highestBidder)
    }
    @Test fun endedStatusUsesActualWinnerAndNeverCallsTheOldLeaderTheWinner() {
        val raw = response()
        raw.getJSONObject("data").getJSONObject("status").put("value", 4)
        val noWinner = RutenAuctionMetadata.snapshot(raw, id, now)
        assertTrue(noWinner.ended)
        assertEquals("ended", BidDecision.state(noWinner, "guest_fixture_o"))
        assertTrue(noWinner.bidderIds.isEmpty())
        raw.getJSONObject("data").put("winner", bid("winner_fixture", 500))
        val winner = RutenAuctionMetadata.snapshot(raw, id, now)
        assertEquals(listOf("winner_fixture"), winner.bidderIds)
        assertEquals(500L, winner.price)
    }
    @Test fun unstartedCancelledAndNoBidsAreNotReportedAsLosing() {
        val raw = response()
        val data = raw.getJSONObject("data")
        data.put("bid_count", 0).put("current", JSONObject.NULL)
        assertEquals(1L, RutenAuctionMetadata.snapshot(raw, id, now).price)
        assertEquals("no_bids", BidDecision.state(RutenAuctionMetadata.snapshot(raw, id, now), "fixture"))
        assertEquals("not_started", BidDecision.state(RutenAuctionMetadata.snapshot(raw, id, start - 1), "fixture"))
        data.getJSONObject("status").put("value", 9)
        assertEquals("cancelled", BidDecision.state(RutenAuctionMetadata.snapshot(raw, id, now), "fixture"))
    }
    @Test fun otherProductInvalidStatusOrFractionalPriceCannotBeAccepted() {
        for (data in listOf(response().apply { getJSONObject("data").put("g_no", "62641000008277") },
            response().apply { getJSONObject("data").getJSONObject("status").put("value", 99) },
            response().apply { getJSONObject("data").getJSONObject("current").put("amount", "300.5") })) {
            try { RutenAuctionMetadata.snapshot(data, id, now); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun onlyUniquePublicBidAtTheSamePriceAndTimeCanConfirmTheMaskedId() {
        val snapshot = RutenAuctionMetadata.snapshot(response(), id, now)
        val record = AuctionBidRecord("guest_fixture_o", 300, bidAt, 1)
        assertEquals(listOf("guest_fixture_o"), RutenAuctionMetadata.confirmBidder(snapshot.copy(records = listOf(record)), bid()).bidderIds)
        for (records in listOf(listOf(record.copy(at = bidAt - 1000)), listOf(record.copy(price = 200)),
            listOf(record, record.copy(bidderId = "guest_other_o"))))
            assertEquals(listOf("g***o"), RutenAuctionMetadata.confirmBidder(snapshot.copy(records = records), bid()).bidderIds)
    }
    @Test fun publicBidRowsFilterInvalidBidsAndKeepFullIds() {
        fun row(name: String, valid: Boolean = true) = JSONObject().put("g_no", id).put("user_nick", name)
            .put("is_valid", valid).put("bid_type", "manual").put("bid_amount", 300).put("bid_time", "2026-10-07T14:42:39+08:00")
        val raw = JSONObject().put("meta", JSONObject().put("per_page", 5).put("total", 3))
            .put("data", JSONArray(listOf(row("guest_fixture_o"), row("guest_invalid", false), row("g***o"))))
        val parsed = RutenAuctionMetadata.bidPage(raw, id)
        assertEquals(3, parsed.count)
        assertEquals(1, parsed.records.size)
        assertEquals("guest_fixture_o", parsed.records.single().bidderId)
        assertEquals(bidAt, parsed.records.single().at)
    }
    @Test fun officialRutenLinksCanonicalizeButSearchAndImpersonatingHostsDoNot() {
        assertEquals(url, AuctionParser.canonicalUrl("https://m.ruten.com.tw/item/show?$id"))
        for (bad in listOf("https://ruten.com.tw.evil.example/item/$id", "https://www.ruten.com.tw/store/fixture/list", "https://user@www.ruten.com.tw/item/$id"))
            try { AuctionParser.canonicalUrl(bad); fail() } catch (_: IllegalArgumentException) { }
    }
}

package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class AuctionParserTest {
    private val html = """<script id="isoredux-data" type="mime/invalid">{"item":{"id":"101764768005","title":"測試競拍","type":2,"images":[],"bidInfo":{"endTime":1790942400,"currentPrice":99,"totalBids":1,"highestBidders":[{"ecid":"Y9300266938","nickname":"同名暱稱"}],"isCancelled":false}},"page":{"serverTs":1790433158116}}</script>"""
    @Test fun parsesObservedPublicYahooSchema() {
        val s = AuctionParser.parse(html, "101764768005", 1790433158116)
        assertEquals(99L, s.price)
        assertEquals(1790942400000L, s.endsAt)
        assertEquals(listOf("Y9300266938"), s.bidderIds)
    }
    @Test fun recognisesOwnIdButNotSharedNickname() {
        val s = AuctionParser.parse(html, "101764768005", 1790433158116)
        assertEquals("leading", BidDecision.state(s, " y9300266938 "))
        assertEquals("other", BidDecision.state(s, "同名暱稱"))
    }
    @Test fun normalizesSecondsServerTimestampBeforeJudgingEnd() {
        val secondsHtml = html.replace("1790433158116", "1790942400")
        val s = AuctionParser.parse(secondsHtml, "101764768005", 1790942400000)
        assertEquals(1790942400000L, s.serverAt)
        assertEquals("ended", BidDecision.state(s, "Y9300266938"))
    }
    @Test fun onlyConfirmedLeadingToOtherNotifies() {
        assertTrue(BidDecision.outbid("leading", "other"))
        for (pair in listOf("unknown" to "other", "other" to "other", "leading" to "unknown", "leading" to "ended", "leading" to "cancelled"))
            assertFalse(BidDecision.outbid(pair.first, pair.second))
    }
    @Test fun hiddenBiddersAreUnknown() {
        val s = AuctionParser.parse(html, "101764768005", 1790433158116)
        assertEquals("unknown", BidDecision.state(s.copy(bidderIds = listOf("Y93***")), "Y9300266938"))
        assertEquals("unknown", BidDecision.state(s.copy(bidderIds = emptyList()), "Y9300266938"))
    }
    @Test fun endedAndCancelledNeverLookLikeOutbid() {
        val s = AuctionParser.parse(html, "101764768005", 1790433158116)
        assertEquals("ended", BidDecision.state(s.copy(serverAt = s.endsAt), "Y9300266938"))
        assertEquals("cancelled", BidDecision.state(s.copy(cancelled = true), "Y9300266938"))
    }
    @Test fun rejectsWrongItemOrLoginPage() {
        assertThrows(Exception::class.java) { AuctionParser.parse(html, "999", 0) }
        assertThrows(Exception::class.java) { AuctionParser.parse("<html>登入</html>", "101764768005", 0) }
        assertThrows(Exception::class.java) { AuctionParser.parse(html.replace("\"type\":2", "\"type\":1"), "101764768005", 0) }
    }
    @Test fun onlyYahooHttpsProductUrlsAccepted() {
        assertEquals("https://tw.bid.yahoo.com/item/101764768005", AuctionParser.canonicalUrl("https://tw.bid.yahoo.com/item/101764768005?tracking=abc"))
        for (url in listOf("http://tw.bid.yahoo.com/item/123", "https://tw.bid.yahoo.com.evil.test/item/123", "https://u@tw.bid.yahoo.com/item/123", "https://tw.bid.yahoo.com:8888/item/123", "https://tw.bid.yahoo.com/search/123"))
            assertThrows(Exception::class.java) { AuctionParser.canonicalUrl(url) }
    }
    @Test fun acceptsOfficialMobileProductUrlsButNotSearchUrls() {
        assertEquals(Market.FACEBOOK, Market.fromPublicProductUrl("https://m.facebook.com/marketplace/item/123456789012345/?ref=share"))
        assertEquals("https://www.facebook.com/marketplace/item/123456789012345/", Market.normalizePublicProductUrl("https://m.facebook.com/marketplace/item/123456789012345/?ref=share"))
        assertEquals(Market.RUTEN, Market.fromPublicProductUrl("https://m.ruten.com.tw/item/22639751952652/"))
        assertEquals(Market.SHOPEE, Market.fromPublicProductUrl("https://m.shopee.tw/product/123456/789012"))
        assertNull(Market.fromPublicProductUrl("https://m.facebook.com/marketplace/175509949157212/search?query=phone"))
        assertNull(Market.fromPublicProductUrl("https://www.ruten.com.tw/search/phone"))
    }
}

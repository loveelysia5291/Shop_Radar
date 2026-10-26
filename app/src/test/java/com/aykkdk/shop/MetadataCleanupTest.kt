package com.aykkdk.shop

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MetadataCleanupTest {
    @Test fun removesYahooVerificationBadgeFromSavedStoreNames() {
        assertEquals("Y2369592298", ShopText.shop("Y2369592298實名驗證"))
        assertEquals("Y8500181312", ShopText.shop("Y8500181312 實名驗證  "))
        assertEquals("店名", ShopText.shop("店名 實名認證"))
        assertEquals("實名驗證手機店", ShopText.shop("實名驗證手機店"))
    }
    @Test fun searchHeadingsAreNotShopNames() {
        assertEquals("", ShopText.shop("優惠推薦5月活動"))
        assertEquals("", ShopText.shop("有1000+的零件機商品"))
        assertEquals("小明手機", ShopText.shop("全部商品- 小明手機 |露天市集"))
        assertEquals("店名", ShopText.shop("店名 實名認證"))
        assertEquals("1000片拼圖", ShopText.title("1000片拼圖"))
    }
    @Test fun rutenPublicPostTimeIsPreserved() {
        assertEquals("2026-09-26 21:21:35", RutenListingTime.read(JSONObject().put("PostTime", "2026/09/26 21:21:35"))?.text)
        assertNull(RutenListingTime.read(JSONObject().put("PostTime", JSONObject.NULL)))
        assertEquals(RutenListingTime.read(JSONObject().put("PostTime", 1790433158)),
            RutenListingTime.read(JSONObject().put("PostTime", 1790433158000)))
    }
    @Test fun yahooPublicAuctionUrlsAcceptMobileAndShareParameters() {
        assertEquals("https://tw.bid.yahoo.com/item/101764768005", AuctionParser.canonicalUrl("https://m.tw.bid.yahoo.com/item/101764768005/?ref=share"))
        val raw = """<script id="isoredux-data">{"item":{"id":"101764768005","type":2,"title":"手機","bidInfo":{"currentPrice":100,"endTime":1800000000}}}</script>"""
        assertTrue(PublicProductParser.parse("https://tw.bid.yahoo.com/item/101764768005", 200, raw).isAuction)
    }
}

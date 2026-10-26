package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class SubscriptionShareInputTest {
    @Test fun acceptsBothObservedShopeeLinksAndCaptionedCarousellLink() {
        for (url in listOf("https://tw.shp.ee/repbigFD", "https://tw.shp.ee/XR3xdAaK", "https://carousell.app.link/zf5xSsO226b")) {
            assertEquals(url, SubscriptionShareInput.parse("推薦商品\n$url\n分享給你"))
        }
        assertEquals("https://tw.bid.yahoo.com/item/100527914215", SubscriptionShareInput.parse("https://tw.bid.yahoo.com/item/100527914215。"))
    }
    @Test fun rejectsUnsafeAmbiguousOrUnboundedShareText() {
        for (text in listOf(null, "", "http://tw.shp.ee/repbigFD", "https://tw.shp.ee.evil.test/repbigFD",
            "https://127.0.0.1/a", "https://user:secret@shopee.tw/product/1/2",
            "https://tw.shp.ee/repbigFD https://tw.shp.ee/XR3xdAaK", "x".repeat(32_769))) {
            assertNull(SubscriptionShareInput.parse(text))
        }
    }
    @Test fun explicitAuctionEvidenceUsesAuctionDestinationBeforeProductInsertion() {
        for (market in listOf(Market.YAHOO, Market.RUTEN)) {
            assertEquals(RefreshKind.AUCTION, SubscriptionImporter.productKind(market,
                PublicProductSnapshot(isAuction = true, auctionTypeKnown = true)))
            assertEquals(RefreshKind.PRODUCT, SubscriptionImporter.productKind(market,
                PublicProductSnapshot(auctionTypeKnown = true)))
        }
        assertEquals(RefreshKind.PRODUCT, SubscriptionImporter.productKind(Market.SHOPEE, null))
    }
    @Test fun unknownAuctionTypeMustNotCreateAProductSubscription() {
        for (market in listOf(Market.YAHOO, Market.RUTEN)) {
            try {
                SubscriptionImporter.productKind(market, PublicProductSnapshot(title = "可讀名稱", error = "未確認"))
                fail("Unknown item type must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
}

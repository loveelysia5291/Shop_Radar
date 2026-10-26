package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class PublishedTimeTest {
    @Test fun subscriptionDatesRetainTheFirstConfirmedRelativeTimestamp() {
        val now = 1_800_000_000_000L
        val item = JSONObject().put("url", "https://shopee.tw/product/123/87654321").put("platform", Market.SHOPEE.label)
        ProductSubscriptionDecision.apply(item, PublicProductSnapshot(listedAt = "36秒前", listedAtEpochMillis = now - 36000), now)
        assertEquals(now - 36000, item.optLong("listedAtEpochMillis"))
        ProductSubscriptionDecision.apply(item, PublicProductSnapshot(listedAt = "36秒前", listedAtEpochMillis = now - 35000), now + 1000)
        assertEquals(now - 36000, item.optLong("listedAtEpochMillis"))
        ProductSubscriptionDecision.apply(item, PublicProductSnapshot(), now + 2000)
        assertEquals("36秒前", item.optString("listedAt"))
        assertEquals(now - 36000, item.optLong("listedAtEpochMillis"))
    }

    @Test fun aChangedDateCannotKeepAnUnrelatedOldTimestamp() {
        val now = 1_800_000_000_000L
        val item = JSONObject().put("listedAt", "2026-01-02").put("listedAtEpochMillis", now - 1000)
        ProductSubscriptionDecision.apply(item, PublicProductSnapshot(listedAt = "6個月前"), now)
        assertEquals("6個月前", item.optString("listedAt"))
        assertFalse(item.has("listedAtEpochMillis"))
        ProductSubscriptionDecision.apply(item, PublicProductSnapshot(listedAt = "2026-01-02"), now)
        assertEquals(ShopCheckpoint.millis("2026-01-02", 0), item.optLong("listedAtEpochMillis"))
    }

    @Test fun onlyValidPublishedDatesAreAccepted() {
        assertEquals("2026-01-02 11:04:05", PublicProductParser.publishedTime("2026-01-02T03:04:05Z"))
        assertEquals("2026-01-02", PublicProductParser.publishedTime("2026/01/02"))
        for (value in listOf("", "2026-02-30", "2099-01-01", "12345678", "約一小時前"))
            assertNull(PublicProductParser.publishedTime(value))
    }
    @Test fun aMatchedProductMaySupplyPublishedButNotModifiedDate() {
        val url = "https://mall.iopenmall.tw/017737/index.php?action=product_detail&prod_no=P1773702460748"
        fun html(field: String) = """<script type="application/ld+json">{"@type":"Product","url":"$url","name":"手機",$field}</script>"""
        assertEquals("2026-01-02", PublicProductParser.parse(url, 200, html("\"datePublished\":\"2026-01-02\"")).listedAt)
        assertNull(PublicProductParser.parse(url, 200, html("\"dateModified\":\"2026-01-02\"")).listedAt)
        assertNull(PublicProductParser.parse(url, 200, html("\"datePublished\":\"2026-01-02\"").replace("P1773702460748", "P9999999999999")).listedAt)
    }
}

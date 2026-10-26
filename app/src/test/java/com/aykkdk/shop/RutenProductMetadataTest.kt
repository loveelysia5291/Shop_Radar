package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RutenProductMetadataTest {
    private val url = "https://www.ruten.com.tw/item/22639751952652/"
    private fun rows(quantity: Any = 1) = JSONArray().put(JSONObject()
        .put("ProdId", "22639751952652").put("ProdName", "測試手機")
        .put("Currency", "TWD").put("PriceRange", JSONArray().put(2800).put(2800))
        .put("StockQty", quantity).put("StockStatus", 1).put("SpecActive", "N"))

    @Test fun publicStockClearsRepeatedUnknownWarning() {
        val item = JSONObject().put("status", "active").put("price", 2800).put("lastCheckError", "平台未提供可確認庫存狀態")
        val snapshot = RutenProductMetadata.snapshot(url, rows())
        assertEquals("active", snapshot.status)
        assertEquals(1L, snapshot.stockQuantity)
        assertEquals(2800L, snapshot.price)
        assertNull(ProductSubscriptionDecision.apply(item, snapshot, 123))
        assertFalse(item.has("lastCheckError"))
        assertEquals(123L, item.getLong("checkedAt"))
    }
    @Test fun shippingSpeedIsNotAnOutOfStockStatus() {
        val snapshot = RutenProductMetadata.snapshot(url, rows(3).apply { getJSONObject(0).put("StockStatus", "4") })
        assertEquals("active", snapshot.status)
        assertEquals(3L, snapshot.stockQuantity)
    }
    @Test fun noStockAlertsOnlyOnce() {
        val item = JSONObject().put("status", "active").put("price", 2800)
        val snapshot = RutenProductMetadata.snapshot(url, rows(0))
        assertEquals("已售出／缺貨", ProductSubscriptionDecision.apply(item, snapshot, 123)?.kind)
        assertNull(ProductSubscriptionDecision.apply(item, snapshot, 124))
    }
    @Test fun unknownAndOtherProductsDoNotOverwriteStock() {
        val wrong = rows().apply { getJSONObject(0).put("ProdId", "22639751938555") }
        assertEquals("unknown", RutenProductMetadata.snapshot(url, wrong).status)
        assertEquals("unknown", RutenProductMetadata.snapshot(url, rows("unknown")).status)
        assertEquals("unknown", RutenProductMetadata.snapshot(url, rows().apply { getJSONObject(0).remove("StockQty") }).status)
        assertEquals("unknown", RutenProductMetadata.snapshot(url, rows(0).apply { getJSONObject(0).put("SpecActive", "Y") }).status)
    }
    @Test fun legacyAndMobileLinksHaveTheSameProductIdentity() {
        assertEquals("22639751952652", RutenProductMetadata.productId("https://m.ruten.com.tw/item/show?22639751952652"))
        assertEquals("active", RutenProductMetadata.snapshot("https://www.ruten.com.tw/item/show?22639751952652", rows()).status)
        assertNull(RutenProductMetadata.productId("https://evil.example/item/22639751952652/"))
    }
    @Test fun wholeDecimalQuantitiesAreAcceptedButFractionsAndInvalidNumbersAreNot() {
        for (raw in listOf("1.0", 1.0, "1")) {
            val snapshot = RutenProductMetadata.snapshot(url, rows(raw))
            assertEquals("active", snapshot.status); assertEquals(1L, snapshot.stockQuantity)
        }
        for (raw in listOf("1.5", "-1", "NaN", "9223372036854775808", JSONObject.NULL))
            assertEquals("unknown", RutenProductMetadata.snapshot(url, rows(raw)).status)
    }
    @Test fun multiItemApiStockIsSavedAndDisplayed() {
        val snapshot = RutenProductMetadata.snapshotOrUnavailable(url, rows(25))
        val saved = JSONObject().put("status", "unknown")
        ProductSubscriptionDecision.apply(saved, snapshot, 123)
        assertEquals(25L, saved.getLong("stockQuantity"))
        assertEquals("有庫存（剩餘 25 件）", ProductStock.label(saved))
    }
    @Test fun missingApiDataRemainsUnknownWithoutPageFallback() {
        for (rows in listOf(null, JSONArray(), rows("unknown"))) {
            val result = RutenProductMetadata.snapshotOrUnavailable(url, rows)
            assertEquals("unknown", result.status); assertNull(result.stockQuantity)
            val saved = JSONObject().put("status", "active").put("stockQuantity", 2).put("price", 2800)
            assertNull(ProductSubscriptionDecision.apply(saved, result, 123))
            assertEquals(2L, saved.getLong("stockQuantity")); assertEquals(2800L, saved.getLong("price"))
            assertEquals("上次有庫存（待確認）", ProductStock.label(saved))
        }
    }
    @Test fun rutenProductHtmlIsNoLongerAStockSource() {
        val html = """<script>RT.context={"isProductPage":true,"item":{"no":"22639751952652","remainNum":5,"isActive":true}};</script>
            <script type="application/ld+json">{"@type":"Product","url":"$url","offers":{"availability":"https://schema.org/InStock","inventoryLevel":{"value":5}}}</script>"""
        val result = PublicProductParser.parse(url, 200, html)
        assertEquals("unknown", result.status); assertNull(result.stockQuantity)
    }
    @Test fun subscriptionRefreshPersistsTheApiListingTimeNotTheRefreshTime() {
        val epoch = 1_791_003_961_000L
        val now = epoch + 86400000
        val snapshot = RutenProductMetadata.snapshot(url, rows().apply { getJSONObject(0).put("PostTime", "2026/10/03 13:06:01") })
        val saved = JSONObject().put("url", url).put("status", "active").put("price", 2800)
        assertNull(ProductSubscriptionDecision.apply(saved, snapshot, now))
        assertEquals("2026-10-03 13:06:01", saved.getString("listedAt"))
        assertEquals(epoch, saved.getLong("listedAtEpochMillis"))
        assertEquals(now, saved.getLong("checkedAt"))
        assertNull(ProductSubscriptionDecision.apply(saved, snapshot, now + 3600000))
        assertEquals(epoch, saved.getLong("listedAtEpochMillis"))
    }
    @Test fun missingOrInvalidDateKeepsTheSavedListingTimeEvenIfStockIsUnknown() {
        val saved = JSONObject().put("listedAt", "2026-10-03 13:06:01").put("listedAtEpochMillis", 1_791_003_961_000L)
        for (raw in listOf(JSONObject.NULL, "invalid", "0")) {
            val snapshot = RutenProductMetadata.snapshot(url, rows("unknown").apply { getJSONObject(0).put("PostTime", raw) })
            assertNull(snapshot.listedAt)
            ProductSubscriptionDecision.apply(saved, snapshot, 1_791_090_361_000L)
            assertEquals("2026-10-03 13:06:01", saved.getString("listedAt"))
            assertEquals(1_791_003_961_000L, saved.getLong("listedAtEpochMillis"))
        }
        val snapshot = RutenProductMetadata.snapshot(url, rows("unknown").apply { getJSONObject(0).put("PostTime", "2026/10/04 13:06:01") })
        ProductSubscriptionDecision.apply(saved, snapshot, 1_791_090_361_000L)
        assertEquals("2026-10-04 13:06:01", saved.getString("listedAt"))
    }
    @Test fun auctionMetadataAlsoCarriesItsPublishedTime() {
        val snapshot = RutenProductMetadata.snapshot(url, rows().apply { getJSONObject(0)
            .put("SaleType", "bid").put("PostTime", "2026/10/03 13:06:01") })
        assertTrue(snapshot.isAuction)
        assertEquals("2026-10-03 13:06:01", snapshot.listedAt)
        assertEquals(1_791_003_961_000L, snapshot.listedAtEpochMillis)
    }
}

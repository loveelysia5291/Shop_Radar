package com.example.shopradar

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ProductStockTest {
    private val url = "https://tw.carousell.com/p/phone-1234567890/"
    private fun product(availability: String = "InStock", inventory: Any? = null, link: String = url): JSONObject {
        val offer = JSONObject().put("@type", "Offer").put("priceCurrency", "TWD").put("price", 900)
        if (availability.isNotBlank()) offer.put("availability", "https://schema.org/$availability")
        if (inventory != null) offer.put("inventoryLevel", inventory)
        return JSONObject().put("@type", "Product").put("url", link).put("name", "測試手機").put("offers", offer)
    }
    private fun page(product: JSONObject) = "<script type=\"application/ld+json\">$product</script>"
    private fun count(value: Any) = JSONObject().put("@type", "QuantitativeValue").put("value", value).put("unitCode", "C62")

    @Test fun explicitInventoryIsSavedAsMultipleItems() {
        val snapshot = PublicProductParser.parse(url, 200, page(product(inventory = count("12.0"))))
        assertEquals("active", snapshot.status); assertEquals(12L, snapshot.stockQuantity)
        val saved = JSONObject().put("status", "unknown")
        ProductSubscriptionDecision.apply(saved, snapshot, 123)
        assertEquals("有庫存（剩餘 12 件）", ProductStock.label(saved))
    }
    @Test fun stockCountWorksForOtherSupportedJsonLdPlatforms() {
        for (link in listOf("https://shopee.tw/product/12345/1234567890", "https://www.facebook.com/marketplace/item/1234567890/", "https://tw.bid.yahoo.com/item/1234567890")) {
            val snapshot = PublicProductParser.parse(link, 200, page(product(inventory = count(5), link = link)))
            assertEquals(link, "active", snapshot.status); assertEquals(link, 5L, snapshot.stockQuantity)
        }
    }
    @Test fun yahooNonAuctionMetadataDoesNotPreventStructuredStockParsing() {
        val link = "https://tw.bid.yahoo.com/item/100527914215"
        val metadata = """<script id="isoredux-data">{"item":{"id":"100527914215","type":1,"title":"手機","price":800}}</script>"""
        val result = PublicProductParser.parse(link, 200, metadata + page(product(inventory = count(3), link = link)))
        assertEquals("active", result.status); assertEquals(3L, result.stockQuantity)
    }
    @Test fun inStockAndOrderingLimitsNeverInventOneItem() {
        val data = product().apply {
            getJSONObject("offers").put("eligibleQuantity", count(20)).put("quantity", 1)
            put("description", "庫存：999 件，買二送一")
        }
        val snapshot = PublicProductParser.parse(url, 200, page(data))
        assertEquals("active", snapshot.status); assertNull(snapshot.stockQuantity)
        val saved = JSONObject().put("status", "active").put("stockQuantity", 12)
        ProductSubscriptionDecision.apply(saved, snapshot, 123)
        assertFalse(saved.has("stockQuantity"))
        assertEquals("有庫存", ProductStock.label(saved))
    }
    @Test fun quantitiesMustBeNonnegativeWholeCountsNotRangesOrOtherUnits() {
        for (value in listOf(count("1.5"), count(-1), count("NaN"), count("9223372036854775808"),
            count(true), count(5).put("minValue", 2), count(5).put("unitCode", "KGM"), count(5).put("unitText", "公斤"))) {
            assertNull(PublicProductParser.parse(url, 200, page(product(inventory = value))).stockQuantity)
        }
    }
    @Test fun otherProductsAndMultipleVariantsCannotSupplyCounts() {
        val foreign = product(inventory = count(99), link = "https://tw.carousell.com/p/other-9988776655/")
        assertEquals("unknown", PublicProductParser.parse(url, 200, page(foreign)).status)
        val foreignOffer = product(inventory = count(99)).apply {
            getJSONObject("offers").put("url", "https://tw.carousell.com/p/other-9988776655/")
        }
        val mismatched = PublicProductParser.parse(url, 200, page(foreignOffer))
        assertEquals("unknown", mismatched.status); assertNull(mismatched.stockQuantity); assertNull(mismatched.price)
        val variants = product(inventory = count(9)).put("hasVariant", JSONArray().put(JSONObject()))
        assertNull(PublicProductParser.parse(url, 200, page(variants)).stockQuantity)
        val multiple = product(inventory = count(9)).apply {
            put("offers", JSONArray().put(getJSONObject("offers")).put(JSONObject().put("inventoryLevel", count(12))))
        }
        assertEquals("unknown", PublicProductParser.parse(url, 200, page(multiple)).status)
    }
    @Test fun contradictoryEvidenceIsUnknownAndDoesNotNotifySold() {
        for (data in listOf(product("InStock", count(0)), product("OutOfStock", count(5)))) {
            val result = PublicProductParser.parse(url, 200, page(data))
            assertEquals("unknown", result.status); assertNull(result.stockQuantity)
            assertNull(ProductSubscriptionDecision.apply(JSONObject().put("status", "active").put("price", 900), result, 123))
        }
    }
    @Test fun validInventoryCanConfirmStockWithoutAvailabilityButNotPreorders() {
        assertEquals("active", PublicProductParser.parse(url, 200, page(product("", count(2)))).status)
        assertEquals("sold", PublicProductParser.parse(url, 200, page(product("", count(0)))).status)
        val preorder = PublicProductParser.parse(url, 200, page(product("PreOrder", count(5))))
        assertEquals("unknown", preorder.status); assertNull(preorder.stockQuantity)
    }
    @Test fun unknownRefreshRetainsBaselineButNeverDisplaysItAsCurrentCount() {
        val saved = JSONObject().put("status", "active").put("stockQuantity", 12).put("price", 900)
        ProductSubscriptionDecision.apply(saved, PublicProductSnapshot(error = "連線失敗"), 123)
        assertEquals(12L, saved.getLong("stockQuantity"))
        assertEquals("上次有庫存（待確認）", ProductStock.label(saved))
        val missing = JSONObject().put("status", "active").put("stockQuantity", 0)
        assertEquals("有庫存", ProductStock.label(missing))
    }
}

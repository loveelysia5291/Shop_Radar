package com.example.shopradar

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProductSubscriptionTest {
    private val url = "https://tw.carousell.com/p/phone-1234567890/"
    private fun html(availability: String, productUrl: String = url, extra: String = "") =
        """<a>登入</a><script type="application/ld+json">{"@type":"Product","url":"$productUrl","name":"測試手機","offers":{"@type":"Offer","availability":"https://schema.org/$availability","price":"900","priceCurrency":"TWD"}}</script>$extra"""
    private fun baseline() = JSONObject().put("status", "active").put("price", 1000)
        .put("title", "測試手機").put("shop", "小王").put("stockEvidenceVersion", 2)

    @Test fun numericCarousellRouteMatchesFinalSlugOfSameListing() {
        val final = "https://tw.carousell.com/p/%E6%8F%92%E5%8D%A1%E7%89%88-ipad-air-5-1466478450/"
        val raw = html("InStock", final)
        val snapshot = PublicProductParser.parse("https://tw.carousell.com/p/1466478450/", 200, raw)
        assertEquals("active", snapshot.status)
        assertEquals("測試手機", snapshot.title)
        assertFalse(PublicProductParser.sameProductUrl(final, "https://tw.carousell.com/p/1466478451/"))
    }
    @Test fun observedShopeePublicNameAndImageDoNotPretendStockIsConfirmed() {
        val url = "https://shopee.tw/product/161121855/24672618478"
        val page = """<meta property="og:url" content="https://shopee.tw/華碩手機維修-i.161121855.24672618478">
            <meta property="og:title" content="華碩手機維修 | 蝦皮購物">
            <meta property="og:image" content="https://down-tw.img.susercontent.com/file/product">"""
        val snapshot = PublicProductParser.parse(url, 200, page)
        assertEquals("華碩手機維修", snapshot.title)
        assertNotNull(snapshot.image)
        assertEquals("unknown", snapshot.status)
        assertNull(snapshot.price)
        assertNotNull(snapshot.error)
        assertFalse(snapshot.auctionTypeKnown)
        val record = JSONObject().put("url", url).put("title", "待取得商品資料").put("status", "unknown")
        ProductSubscriptionDecision.apply(record, snapshot, 1)
        assertEquals("華碩手機維修", record.getString("title"))
        assertEquals(snapshot.resolvedUrl, record.getString("resolvedUrl"))
    }
    @Test fun anotherProductsMetadataAndLoginTitlesCannotSupplyAName() {
        val page = "<meta property='og:url' content='https://shopee.tw/product/1/999'><meta property='og:title' content='別的商品'>"
        assertNull(PublicProductParser.parse("https://shopee.tw/product/1/2", 200, page).title)
        assertNull(PublicProductParser.parse("https://shopee.tw/product/1/999", 200, page.replace("別的商品", "登入")).title)
    }

    @Test fun errorsNeverMeanSoldOrEraseBaseline() {
        for (code in listOf(404, 403, 429, 500)) {
            val item = baseline()
            val result = PublicProductParser.parse(url, code, "商品已售出")
            assertEquals("unknown", result.status)
            assertNull(ProductSubscriptionDecision.apply(item, result, 123))
            assertEquals("active", item.getString("status"))
            assertEquals(1000, item.getInt("price"))
            assertFalse(item.has("checkedAt"))
        }
    }
    @Test fun carousell410MeansRemovedNotProofOfSaleAndOnlyAlertsOnce() {
        val item = baseline()
        val snapshot = PublicProductParser.parse(url, 410, "")
        assertEquals("removed", snapshot.status)
        assertEquals("已下架／失效", ProductSubscriptionDecision.apply(item, snapshot, 123)?.kind)
        assertEquals(1000, item.getInt("price"))
        assertNull(ProductSubscriptionDecision.apply(item, snapshot, 124))
        assertEquals("active", PublicProductParser.parse(url, 200, html("InStock")).status)
        assertEquals("unknown", PublicProductParser.parse("https://www.ruten.com.tw/item/12345678901234/", 410, "").status)
    }
    @Test fun navigationLoginTextAndOtherSoldItemsDoNotContaminateProduct() {
        val result = PublicProductParser.parse(url, 200, html("InStock", extra = "<aside>其他商品已售出 sold out</aside>"))
        assertEquals("active", result.status)
        assertEquals(900L, result.price)
    }
    @Test fun anotherProductCannotChangeStock() {
        val result = PublicProductParser.parse(url, 200, html("OutOfStock", "https://tw.carousell.com/p/other-9988776655/"))
        assertEquals("unknown", result.status)
        assertNull(result.price)
    }
    @Test fun missingIdentityDoesNotMatchAnEmptyId() {
        val page = html("SoldOut").replace("\"url\":\"$url\",", "")
        assertEquals("unknown", PublicProductParser.parse("https://shopee.tw/product/123/456", 200, page).status)
    }
    @Test fun observedCarousellOfferUrlAndDecimalPrice() {
        val page = """<script type="application/ld+json">{"@context":"https://schema.org","@type":"Product","name":"故障硬碟","offers":{"@type":"Offer","availability":"https://schema.org/InStock","price":"400.00","priceCurrency":"TWD","url":"https://tw.carousell.com/p/故障硬碟-1465049980/"}}</script>"""
        val snapshot = PublicProductParser.parse("https://tw.carousell.com/p/%E6%95%85%E9%9A%9C%E7%A1%AC%E7%A2%9F-1465049980/", 200, page)
        assertEquals("active", snapshot.status)
        assertEquals(400L, snapshot.price)
        assertNull(snapshot.error)
    }
    @Test fun loginAndUnstructuredTextAreUnknown() {
        for (text in listOf("<h1>請登入</h1>", "商品已下架", "<script>var soldOutLabel='sold out';</script>", ""))
            assertEquals("unknown", PublicProductParser.parse(url, 200, text).status)
    }
    @Test fun soldEvidenceNotifiesOnce() {
        val item = baseline()
        val snapshot = PublicProductParser.parse(url, 200, html("SoldOut"))
        assertEquals("已售出／缺貨", ProductSubscriptionDecision.apply(item, snapshot, 123)?.kind)
        assertNull(ProductSubscriptionDecision.apply(item, snapshot, 124))
    }
    @Test fun migrationClearsOnlyLegacyUnverifiedSoldStatus() {
        val old = baseline().put("status", "sold"); old.remove("stockEvidenceVersion")
        ProductSubscriptionDecision.migrate(old)
        assertEquals("unknown", old.getString("status"))
        assertEquals(1000, old.getInt("price"))
        val verified = baseline().put("status", "sold")
        ProductSubscriptionDecision.migrate(verified)
        assertEquals("sold", verified.getString("status"))
    }
    @Test fun matchingProductPriceDropNotifiesOnce() {
        val item = baseline()
        val snapshot = PublicProductParser.parse(url, 200, html("InStock"))
        assertEquals("降價", ProductSubscriptionDecision.apply(item, snapshot, 123)?.kind)
        assertNull(ProductSubscriptionDecision.apply(item, snapshot, 124))
    }
    @Test fun multipleOffersDoNotClaimAllVariantsSold() {
        val page = html("OutOfStock").replace("\"offers\":{", "\"offers\":[{")
            .replace("\"TWD\"}}", "\"TWD\"},{\"availability\":\"https://schema.org/InStock\"}]}")
        assertEquals("unknown", PublicProductParser.parse(url, 200, page).status)
    }
}

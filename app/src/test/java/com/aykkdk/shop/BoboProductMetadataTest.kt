package com.aykkdk.shop

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BoboProductMetadataTest {
    private val url = "https://www.bbbobo.com.tw/shop/0/Goods.asp?AID=86&GID=GTT2605190005148"
    private fun page(date: String = "2026/10/3", id: String = "GTT2605190005148") = """
        <ul><li>商品所在： <a>嘉義玉山舖</a></li><li>商品分類： 手機通訊</li>
        <li>商品編號： $id</li><li>上架時間： $date</li></ul>
        <h2>您可能喜歡的商品</h2><ul><li>商品所在：其他店</li><li>商品分類：其他</li>
        <li>商品編號：GTT2605190005999</li><li>上架時間：2026/10/2</li></ul>
    """.trimIndent()

    @Test fun suppliedLabelsArePreservedWithoutDerivingDateFromId() {
        val item = BoboProductMetadata.parse(url, page())!!
        assertEquals("嘉義玉山舖", item.shop)
        assertEquals("手機通訊", item.category)
        assertEquals("2026/10/3", item.listedAt)
    }
    @Test fun unrelatedProductsMissingDatesAndInvalidDatesCannotSupplyMetadata() {
        assertNull(BoboProductMetadata.parse(url, page(id = "GTT2605190005999")))
        assertNull(BoboProductMetadata.parse(url, page("2026/2/30")))
        assertNull(BoboProductMetadata.parse(url, page().replace("上架時間： 2026/10/3", "上架時間：")))
        assertNull(BoboProductMetadata.parse("https://evil.example/shop/0/Goods.asp?AID=86&GID=GTT2605190005148", page()))
        assertNull(BoboProductMetadata.parse(url, "<script>${page()}</script>"))
    }
    @Test fun categorySurvivesProductSerializationAndLegacyRecordsStayReadable() {
        val product = Product("手機", 100, Market.BBOBO.label, url, shop = "嘉義玉山舖", listedAt = "2026/10/3", category = "手機通訊")
        assertEquals(product, Product.parse(product.json()))
        assertEquals("", Product.parse(product.json().apply { remove("category") })!!.category)
    }
    @Test fun subscriptionRefreshKeepsExplicitMetadataEvenWhenStockIsUnknown() {
        val snapshot = PublicProductParser.parse(url, 200, page())
        assertEquals("unknown", snapshot.status)
        assertEquals("嘉義玉山舖", snapshot.shop)
        val item = JSONObject().put("status", "active").put("price", 100).put("title", "手機")
        assertNull(ProductSubscriptionDecision.apply(item, snapshot, 123))
        assertEquals("active", item.getString("status"))
        assertEquals("手機通訊", item.getString("category"))
        assertEquals("2026/10/3", item.getString("listedAt"))
        assertEquals("嘉義玉山舖", item.getString("shop"))
    }
    @Test fun legacyBig5AndUtf8PagesDecodeTheirDeclaredCharset() {
        val big5 = java.nio.charset.Charset.forName("Big5")
        val html = "<meta charset='big5'>${page()}"
        assertEquals(html, BoboProductMetadata.decode(html.toByteArray(big5), "text/html"))
        assertEquals(page(), BoboProductMetadata.decode(page().toByteArray(), "text/html; charset=utf-8"))
    }
}

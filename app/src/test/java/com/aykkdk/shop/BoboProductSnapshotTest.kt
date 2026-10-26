package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class BoboProductSnapshotTest {
    private val url = "https://www.bbbobo.com.tw/shop/0_1/Goods.asp?AID=86&GID=GTT2606120006790"
    private fun page(stock: String = "庫存數：1", gid: String = "GTT2606120006790", aid: String = "86") = """
        <div class="goods-description2"><h2 class="gd-name-txt">斜背包</h2>
        <div class="gd-otinfo-list"><ul><li>商品所在：<a class="goshop-url" AID="$aid">嘉義玉山舖</a></li>
        <li>商品分類：包包．提袋</li><li>商品編號：$gid</li><li>上架時間：2026/10/3</li></ul></div>
        <div class="gd-addinfo-list"><div class="pr-price-sale">$258</div><div class="pr-price"><span>$</span><span>178</span></div>
        <div class="gooddntxt">產品簡述：庫存數：0</div><div class="good-addcar"><div class="webad-in-goods"><i></i>$stock</div></div></div></div>
        <aside><div class="webad-in-goods">庫存數：0</div>您可能喜歡的商品 已售出</aside>
    """.trimIndent()
    @Test fun explicitProductStockIsActiveAndUsesSpecialPrice() {
        val result = PublicProductParser.parse(url, 200, page())
        assertEquals("active", result.status); assertNull(result.error)
        assertEquals(1L, result.stockQuantity)
        assertEquals(178L, result.price); assertEquals("斜背包", result.title)
        assertEquals("嘉義玉山舖", result.shop); assertEquals("2026/10/3", result.listedAt)
    }
    @Test fun zeroStockMeansSoldButOtherProductsAndFailuresDoNot() {
        val sold = PublicProductParser.parse(url, 200, page("庫存數：0"))
        assertEquals("sold", sold.status); assertEquals(0L, sold.stockQuantity)
        for (html in listOf(page("無法確認"), page(gid = "GTT2606120006000"), page(aid = "85"), "找不到網頁", page().substringBefore("</div></div></div>")))
            assertEquals("unknown", PublicProductParser.parse(url, 200, html).status)
        assertEquals("unknown", PublicProductParser.parse(url, 404, page("庫存數：0")).status)
    }
    @Test fun explicitMissingItemResponseMeansSoldNotNetworkFailure() {
        for (mall in listOf("0", "0_1")) {
            val link = url.replace("/0_1/", "/$mall/")
            assertEquals("sold", PublicProductParser.parse(link, 200, "<div>查無此商品資料！</div>").status)
            assertEquals("unknown", PublicProductParser.parse(link, 404, "查無此商品資料！").status)
            assertEquals("unknown", PublicProductParser.parse(link, 200, "<script>查無此商品資料！</script>").status)
            assertEquals("unknown", PublicProductParser.parse(link, 200, "您可能喜歡的商品 查無此商品資料！").status)
        }
        assertEquals("active", PublicProductParser.parse(url, 200, page() + "查無此商品資料！").status)
        val sold = PublicProductParser.parse(url, 200, "查無此商品資料！")
        val saved = org.json.JSONObject().put("status", "active").put("price", 178)
            .put("title", "斜背包").put("shop", "嘉義玉山舖")
        val alert = ProductSubscriptionDecision.apply(saved, sold, 123)!!
        assertEquals("斜背包", alert.title); assertEquals("嘉義玉山舖", alert.shop)
        assertEquals("sold", saved.getString("status")); assertEquals(178, saved.getInt("price"))
        assertNull(ProductSubscriptionDecision.apply(saved, sold, 124))
    }
    @Test fun bothMallsUseTheSameIdentifiedStockFields() {
        assertEquals("active", PublicProductParser.parse(url.replace("/0_1/", "/0/"), 200, page()).status)
        for (mall in listOf("0", "0_1")) {
            val result = PublicProductParser.parse(url.replace("/0_1/", "/$mall/"), 200, page("庫存數：1,000"))
            assertEquals("active", result.status); assertEquals(1000L, result.stockQuantity)
            val saved = org.json.JSONObject().put("status", "active").put("price", 178)
            ProductSubscriptionDecision.apply(saved, result, 123)
            assertEquals("有庫存（剩餘 1000 件）", ProductStock.label(saved))
        }
    }
}

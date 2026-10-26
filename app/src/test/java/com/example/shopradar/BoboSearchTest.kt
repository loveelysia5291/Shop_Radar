package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class BoboSearchTest {
    @Test fun categoryFilterUsesVerifiedProductCategoryAndLeavesOtherPlatformsAlone() {
        val p = Product("手機", 100, Market.BBOBO.label, "https://www.bbbobo.com.tw/shop/0/Goods.asp?AID=86&GID=GTT2606120006790")
        assertTrue(BoboSearch.matchesCategory(p, ""))
        assertFalse(BoboSearch.matchesCategory(p, "手機通訊"))
        assertTrue(BoboSearch.matchesCategory(p.copy(category = "手機通訊"), "手機通訊"))
        assertFalse(BoboSearch.matchesCategory(p.copy(category = "玩具"), "手機通訊"))
        assertTrue(BoboSearch.matchesCategory(p.copy(platform = Market.IOPEN.label), "手機通訊"))
    }
    @Test fun chosenMallSurvivesSearchAndPagination() {
        for (mall in listOf("0", "0_1")) {
            val url = BoboSearch.url("手機 & 配件", "100", "2000", 2, mall)
            assertTrue(url.startsWith("https://www.bbbobo.com.tw/shop/$mall/Goods_List.asp?"))
            assertTrue(url.contains("SearchZone=$mall")); assertTrue(url.contains("Page=2"))
            assertTrue(url.contains("MinM=100")); assertTrue(url.contains("MaxM=2000"))
            assertTrue(url.contains("%26")); assertFalse(url.contains("KeyWord=手機 &"))
        }
        assertEquals("0", BoboSearch.zone("eshop"))
    }
    @Test fun specialProductLinksRemainInTheirMall() {
        val url = "https://www.bbbobo.com.tw/shop/0_1/Goods.asp?AID=86&GID=GTT2606120006790"
        assertEquals(url, Market.normalizePublicProductUrl(url + "&Page=2"))
        assertEquals(Market.BBOBO, Market.fromPublicProductUrl(url))
        assertNull(Market.fromPublicProductUrl(url.replace("/0_1/", "/0_2/")))
    }
}

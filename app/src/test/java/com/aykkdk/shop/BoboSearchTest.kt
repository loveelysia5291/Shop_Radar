package com.aykkdk.shop

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
            assertTrue(url.contains("SelectArea=0")); assertTrue(url.contains("SelectAgent=0"))
            assertTrue(url.contains("MinM=100")); assertTrue(url.contains("MaxM=2000"))
            assertTrue(url.contains("%26")); assertFalse(url.contains("KeyWord=手機 &"))
        }
        assertEquals("0", BoboSearch.zone("eshop"))
    }

    @Test fun areaAndStoreParametersMatchSuppliedExamples() {
        // 所有店鋪
        val allStoresUrl = BoboSearch.url("", "", "", 1, "0", area = "0", agent = "0")
        assertTrue(allStoresUrl.contains("SelectArea=0"))
        assertTrue(allStoresUrl.contains("SelectAgent=0"))

        // 北北基
        val area1Url = BoboSearch.url("", "", "", 1, "0", area = "1", agent = "0")
        assertTrue(area1Url.contains("SelectArea=1"))
        assertTrue(area1Url.contains("SelectAgent=0"))

        // 北市延平舖 (area 1, agent 62)
        val yanpingUrl = BoboSearch.url("", "", "", 1, "0", area = "1", agent = "62")
        assertTrue(yanpingUrl.contains("SelectArea=1"))
        assertTrue(yanpingUrl.contains("SelectAgent=62"))

        // 屏東復興舖 (area 6, agent 98)
        val fuxingUrl = BoboSearch.url("", "", "", 1, "0", area = "6", agent = "98")
        assertTrue(fuxingUrl.contains("SelectArea=6"))
        assertTrue(fuxingUrl.contains("SelectAgent=98"))

        assertEquals("北市延平舖", BoboSearch.agentName("1", "62"))
        assertEquals("屏東復興舖", BoboSearch.agentName("6", "98"))
    }

    @Test fun storeOptionsPrefixMatchesSelectedArea() {
        assertEquals("所有店鋪", BoboSearch.agentOptions("0").first().second)
        assertEquals("北北基所有店鋪", BoboSearch.agentOptions("1").first().second)
        assertEquals("桃竹苗所有店鋪", BoboSearch.agentOptions("2").first().second)
        assertEquals("中彰投所有店鋪", BoboSearch.agentOptions("3").first().second)
        assertEquals("雲嘉南所有店鋪", BoboSearch.agentOptions("4").first().second)
        assertEquals("宜花東所有店鋪", BoboSearch.agentOptions("5").first().second)
        assertEquals("高屏所有店鋪", BoboSearch.agentOptions("6").first().second)

        assertTrue(BoboSearch.agentOptions("1").any { it.first == "62" && it.second == "北市延平舖" })
        assertTrue(BoboSearch.agentOptions("6").any { it.first == "98" && it.second == "屏東復興舖" })
        assertFalse(BoboSearch.agentOptions("1").any { it.first == "98" })
    }

    @Test fun specialProductLinksRemainInTheirMall() {
        val url = "https://www.bbbobo.com.tw/shop/0_1/Goods.asp?AID=86&GID=GTT2606120006790"
        assertEquals(url, Market.normalizePublicProductUrl(url + "&Page=2"))
        assertEquals(Market.BBOBO, Market.fromPublicProductUrl(url))
        assertNull(Market.fromPublicProductUrl(url.replace("/0_1/", "/0_2/")))
    }
}

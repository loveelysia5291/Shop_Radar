package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class ExtraMarketUrlTest {
    @Test fun essentialProductQueriesSurviveNormalization() {
        assertEquals("https://www.bbbobo.com.tw/shop/0/Goods.asp?AID=82&GID=GTT2610020008729",
            Market.normalizePublicProductUrl("https://www.bbbobo.com.tw/shop/82/Goods.asp?AID=82&GID=GTT2610020008729&Page=2"))
        assertNull(Market.normalizePublicProductUrl("https://www.easysell.com.tw/Goods/Detail?cibSNo=7167624&ref=search"))
        assertEquals("https://mall.iopenmall.tw/017737/index.php?action=product_detail&prod_no=P1773702460748",
            Market.normalizePublicProductUrl("https://mall.iopenmall.tw/017737/index.php?action=product_detail&prod_no=P1773702460748&ref=search"))
    }
    @Test fun searchAndOtherHostsCannotBecomeProducts() {
        assertNull(Market.fromPublicProductUrl("https://www.easysell.com.tw/Goods/Search?Key=phone"))
        assertNull(Market.fromPublicProductUrl("https://www.bbbobo.com.tw/shop/0/Goods.asp?AID=82&GID=invalid"))
        assertNull(Market.fromPublicProductUrl("https://www.easysell.com.tw.evil.example/Goods/Detail?cibSNo=7167624"))
        assertFalse(Market.IOPEN.supportsShopSubscriptions)
    }
    @Test fun iOpenUsesTheSuppliedAnonymousSearchRoute() {
        assertEquals("https://mall.iopenmall.tw/iopen/index.php?action=store_product_search&prod_keyword=%E9%BB%91%E9%AF%8A",
            IOpenSearch.url("黑鯊"))
        assertEquals(IOpenSearch.url("黑鯊"), Market.IOPEN.search("黑鯊", "100", "10000"))
        assertTrue(IOpenSearch.url("A&B 手機").contains("A%26B+%E6%89%8B%E6%A9%9F"))
    }
}

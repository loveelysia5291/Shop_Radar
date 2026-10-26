package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class RutenShopPagingTest {
    @Test fun undatedUnseenListingsAreNotLostAndPagingStopsAtKnownItem() {
        val item = product("new", "")
        assertTrue(RutenShopPaging.isNewSince(item, 1800000000000, setOf("old")))
        assertTrue(RutenShopPaging.needsNextPage(1, listOf(item), 0, setOf("old")))
        assertFalse(RutenShopPaging.needsNextPage(2, listOf(product("old", "")), 0, setOf("old")))
        assertFalse(RutenShopPaging.needsNextPage(1, listOf(item), 0))
    }
    private fun product(url: String, at: String) = Product("商品", 1, "露天", url, listedAt = at)

    @Test fun createsExpectedOfficialStoreUrl() {
        assertEquals("https://www.ruten.com.tw/store/tiger0719tw/list?sort=new%2Fdc&p=1",
            Market.RUTEN.rutenStorePage("tiger0719tw"))
        assertEquals("tiger0719tw", Market.RUTEN.rutenStoreUsername("https://www.ruten.com.tw/store/tiger0719tw/list?sort=new/dc&p=1"))
    }

    @Test fun continuesAcrossShortPagesWhileTheyStillReachCheckpoint() {
        val newest = List(12) { product("https://www.ruten.com.tw/item/${1000000000 + it}/", "2026-09-30 12:00:00") }
        assertTrue(RutenShopPaging.needsNextPage(1, newest, RutenShopPaging.listedAtMillis("2026-09-29 00:00:00")!!))
        val older = List(6) { product("https://www.ruten.com.tw/item/${2000000000 + it}/", "2026-09-28 12:00:00") }
        assertFalse(RutenShopPaging.needsNextPage(2, older, RutenShopPaging.listedAtMillis("2026-09-29 00:00:00")!!))
    }

    @Test fun equalTimestampAndUnseenListingIsNotNew() {
        val item = product("https://www.ruten.com.tw/item/1234567890/", "2026-09-30 12:00:00")
        assertFalse(RutenShopPaging.isNewSince(item, RutenShopPaging.listedAtMillis(item.listedAt)!!, emptySet()))
        assertFalse(RutenShopPaging.isNewSince(item, 0, setOf(item.url)))
    }
}

package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class ShopCheckpointTest {
    private fun item(id: String, at: String) = Product("商品", 100, "旋轉拍賣", "https://tw.carousell.com/p/phone-$id/", listedAt = at)
    private val old = ShopCheckpoint.millis("2026-10-01 12:00:00", 0)!!
    @Test fun stopsAtEqualOrOlderAndOnlyCollectsNewerPrefix() {
        val newer = item("1234567890", "2026-10-02 12:00:00")
        for (boundary in listOf("2026-10-01 12:00:00", "2026-09-30 12:00:00")) {
            val scan = ShopCheckpoint.scan(listOf(newer, item("1234567891", boundary), newer.copy(url = "not-scanned")), old, emptySet())
            assertTrue(scan.reachedOld)
            assertEquals(listOf(newer), scan.products)
            assertFalse(ShopCheckpoint.isNew(item("equal", boundary), old, emptySet()))
        }
    }
    @Test fun newerPageContinuesAndNewestTimestampIsPreserved() {
        val products = listOf(item("a", "2026-10-02 12:00:00"), item("b", "2026-10-01 13:00:00"))
        assertFalse(ShopCheckpoint.scan(products, old, emptySet()).reachedOld)
        assertEquals(ShopCheckpoint.timestamp(products.first()), ShopCheckpoint.newest(products))
    }
    @Test fun relativeTimeIsFrozenAndSurvivesPersistence() {
        val captured = ShopCheckpoint.freeze(listOf(item("1234567890", "58 分鐘前")), old + 3600000).single()
        assertEquals(old + 120000, captured.listedAtEpochMillis)
        val restored = Product.parse(captured.json())!!
        assertEquals(captured.listedAtEpochMillis, ShopCheckpoint.freeze(listOf(restored), old + 86400000).single().listedAtEpochMillis)
        assertNull(ShopCheckpoint.timestamp(item("b", "58 分鐘前")))
    }
    @Test fun missingTimeDoesNotInventOneAndStopsAtKnownIdentity() {
        val known = item("a", "")
        val scan = ShopCheckpoint.scan(listOf(item("b", ""), known), old, setOf(known.url))
        assertTrue(scan.reachedOld)
        assertEquals(1, scan.products.size)
        assertEquals(0L, ShopCheckpoint.newest(scan.products))
        assertNull(ShopCheckpoint.millis("2026-10-02 junk", old))
    }
    @Test fun knownRelativeListingCannotDriftForwardIntoNewItems() {
        val known = ShopCheckpoint.freeze(listOf(item("a", "剛剛")), old + 86400000).single()
        assertTrue(ShopCheckpoint.scan(listOf(known), old, setOf(known.url)).reachedOld)
        assertEquals(0L, ShopCheckpoint.newest(listOf(known.copy(isAdvertisement = true))))
    }
    @Test fun storeUrlsKeepExistingParametersAndUseNewestOrder() {
        val url = Market.CAROUSELL.shopPage("https://tw.carousell.com/u/shop/?tab=marketplace&sort_by=1", 3)
        assertEquals("https://tw.carousell.com/u/shop/?tab=marketplace&sort_by=3", url)
        assertEquals("https://tw.bid.yahoo.com/booth/shop?foo=bar&sort=-ptime&pg=2",
            Market.YAHOO.shopPage("https://tw.bid.yahoo.com/booth/shop?sort=popular&foo=bar", 2))
    }
}

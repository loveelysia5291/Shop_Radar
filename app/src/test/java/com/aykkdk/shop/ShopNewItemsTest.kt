package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShopNewItemsTest {
    @Test fun newItemsPageUsesSellerNameWithoutDuplicatingMarketplaceSuffix() {
        assertEquals("測試用戶賣場的新品", ShopNewItems.pageTitle("測試用戶"))
        assertEquals("測試用戶的賣場的新品", ShopNewItems.pageTitle("測試用戶的賣場"))
        assertEquals("Y2369592298賣場的新品", ShopNewItems.pageTitle("Y2369592298實名驗證"))
        assertEquals("訂閱賣場的新品", ShopNewItems.pageTitle(""))
        assertEquals("測試賣場的新品", ShopNewItems.pageTitle("", listOf(item(1).copy(shop = "測試"))))
    }
    private fun item(id: Int, time: Long = id * 100L) = Product("商品$id", 100, Market.RUTEN.label,
        "https://www.ruten.com.tw/item/2264080771841$id", listedAtEpochMillis = time, shop = "測試店家")

    @Test fun oldAppendOrderedHistoryIsDisplayedNewestFirstWithoutMigration() {
        val record = JSONObject().put("newItems", JSONArray(listOf(item(1).json(), item(3).json(), item(2).json())))
        assertEquals(listOf(3, 2, 1).map { item(it).url }, ShopNewItems.read(record).map { it.url })
        assertTrue(ShopNewItems.latestUrls(record).isEmpty())
    }
    @Test fun currentCheckPrecedesRetainedItemsAndOrdersItsDatesNewestFirst() {
        val record = JSONObject().put("newItems", JSONArray(listOf(item(1).json())))
        ShopNewItems.merge(record, listOf(item(2), item(4), item(3)))
        assertEquals(listOf(4, 3, 2, 1).map { item(it).url }, ShopNewItems.read(record).map { it.url })
        assertEquals(setOf(2, 3, 4).map { item(it).url }.toSet(), ShopNewItems.latestUrls(record))
        assertEquals(3, record.optInt("newCount"))
    }
    @Test fun unknownDateUsesCurrentBatchAndStableCaptureOrderWithoutInventingTimestamp() {
        val record = JSONObject().put("newItems", JSONArray(listOf(item(1).json(), item(2, 0).json())))
        ShopNewItems.merge(record, listOf(item(4, 0), item(3, 0)))
        val reopened = ShopNewItems.read(JSONObject(record.toString()))
        assertEquals(listOf(4, 3, 1, 2).map { item(it).url }, reopened.map { it.url })
        assertEquals(0L, reopened.first().listedAtEpochMillis)
        assertEquals("", reopened.first().listedAt)
    }
    @Test fun replacingMetadataDoesNotDuplicateHistoryOrMarkOldItemsAsCurrent() {
        val record = JSONObject()
        ShopNewItems.merge(record, listOf(item(2), item(1)))
        ShopNewItems.merge(record, listOf(item(3), item(3).copy(price = 999), item(2).copy(price = 222)))
        assertEquals(3, ShopNewItems.read(record).size)
        assertEquals(222L, ShopNewItems.read(record).first { it.url == item(2).url }.price)
        assertEquals(setOf(item(3).url, item(2).url), ShopNewItems.latestUrls(record))
        assertFalse(item(1).url in ShopNewItems.latestUrls(record))
    }
    @Test fun latestMarkersSurviveRestartAndAreClearedOnANoNewItemsCheck() {
        val record = JSONObject().put("listingCheckpointAt", 500)
        ShopNewItems.merge(record, listOf(item(2), item(1)))
        val reopened = JSONObject(record.toString())
        assertEquals(setOf(item(2).url, item(1).url), ShopNewItems.latestUrls(reopened))
        ShopNewItems.merge(reopened, emptyList())
        assertTrue(ShopNewItems.latestUrls(reopened).isEmpty())
        assertEquals(2, ShopNewItems.read(reopened).size)
        assertEquals(0, reopened.optInt("newCount"))
        assertEquals(500L, reopened.optLong("listingCheckpointAt"))
    }
    @Test fun clearingRetainedItemsAlsoClearsBadgesButNotTheWatermark() {
        val record = JSONObject().put("listingCheckpointAt", 500).put("seen", JSONArray(listOf(item(1).url)))
        ShopNewItems.merge(record, listOf(item(1)))
        val cleared = clearShopNewItems("https://www.ruten.com.tw/store/fixture/list", record.toString())
        assertTrue(ShopNewItems.read(cleared).isEmpty())
        assertTrue(ShopNewItems.latestUrls(cleared).isEmpty())
        assertEquals(500L, cleared.optLong("listingCheckpointAt"))
        assertEquals(item(1).url, cleared.getJSONArray("seen").getString(0))
    }
    @Test fun sourceScanKeepsItsOrderButPublishedPageAndCarryAreNewestFirst() {
        val incoming = listOf(item(2), item(4), item(3), item(1))
        val observed = SubscriptionNewItemsPaging(50, emptySet()).observe(incoming)
        assertEquals(incoming, observed.collected)
        val page = observed.publish()
        assertEquals(listOf(4, 3, 2).map { item(it) }, page.displayed)
        assertEquals(listOf(item(1)), page.pending)
        val done = page.next().observe(listOf(item(0, 50))).publish()
        assertEquals(listOf(item(1)), done.displayed)
        assertTrue(done.reachedOld)
        assertFalse(done.canNext)
    }
    @Test fun backgroundContinuationMarksWholeCurrentScanNotOnlyTheLastSourcePage() {
        val url = "https://www.ruten.com.tw/store/fixture/list"
        val raw = JSONObject().put("listingCheckpointAt", 50).put("seen", JSONArray()).toString()
        val first = ShopRefreshUpdate.build(url, raw, Market.RUTEN, 50, emptySet(), listOf(item(2)),
            listOf(item(2)), false, false, 2, 600)!!
        val next = ShopRefreshUpdate.build(url, first.record.toString(), Market.RUTEN, 50, emptySet(), listOf(item(1)),
            listOf(item(2), item(1)), false, true, 3, 700)!!
        assertEquals(setOf(item(1).url, item(2).url), ShopNewItems.latestUrls(next.record))
        assertEquals(listOf(item(1).url), next.alerts.map { it.productUrl })
    }
}

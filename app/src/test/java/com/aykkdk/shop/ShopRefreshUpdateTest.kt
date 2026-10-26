package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShopRefreshUpdateTest {
    private val url = "https://www.ruten.com.tw/store/fixture/list?sort=new/dc&p=1"
    private val ids = mapOf("one" to "22640807718410", "two" to "22640807718411", "retained" to "22640807718412")
    private fun product(id: String, time: Long = 200) = Product("商品$id", 50, Market.RUTEN.label,
        "https://www.ruten.com.tw/item/${ids.getValue(id)}", shop = "測試店家", listedAtEpochMillis = time)
    private fun saved() = JSONObject().put("name", "測試店家").put("listingCheckpointAt", 100)
        .put("seen", JSONArray(listOf("old"))).put("newItems", JSONArray()).put("checkedAt", 42)
    private fun update(raw: String?, found: List<Product>, complete: Boolean = true, initialize: Boolean = false) =
        ShopRefreshUpdate.build(url, raw, Market.RUTEN, 100, setOf("old"), found, found, initialize, complete, 4, 500)

    @Test fun deletedSubscriptionIsNeverRecreated() {
        assertNull(update(null, listOf(product("one"))))
    }
    @Test fun newerManualCheckpointCannotBeOverwrittenByALateBackgroundResponse() {
        assertNull(update(saved().put("listingCheckpointAt", 300).toString(), listOf(product("one"))))
    }
    @Test fun partialScanKeepsOldWatermarkAndPersistsItsContinuation() {
        val result = update(saved().toString(), listOf(product("one")), complete = false)!!
        assertEquals(100L, result.record.getLong("listingCheckpointAt"))
        assertEquals(42L, result.record.getLong("checkedAt"))
        assertEquals(4, result.record.getJSONObject("refreshScan").getInt("page"))
        assertEquals(1, result.record.getJSONObject("refreshScan").getJSONArray("products").length())
        assertEquals(1, result.alerts.size)
    }
    @Test fun confirmedBoundaryAdvancesWatermarkAndClearsContinuation() {
        val result = update(saved().put("refreshScan", JSONObject()).toString(), listOf(product("one"), product("two", 250)))!!
        assertEquals(250L, result.record.getLong("listingCheckpointAt"))
        assertEquals(500L, result.record.getLong("checkedAt"))
        assertFalse(result.record.has("refreshScan"))
        assertEquals(2, result.record.getInt("newCount"))
        assertEquals(3, result.record.getJSONArray("seen").length())
    }
    @Test fun noNewItemsKeepsRetainedHistoryAndOldWatermark() {
        val original = saved().put("newItems", JSONArray(listOf(product("retained").json())))
        val result = update(original.toString(), emptyList())!!
        assertEquals(1, result.record.getJSONArray("newItems").length())
        assertEquals(100L, result.record.getLong("listingCheckpointAt"))
        assertEquals(0, result.record.getInt("newCount"))
        assertTrue(result.alerts.isEmpty())
    }
    @Test fun resumedPartialProductsAreNotNotifiedTwice() {
        val original = saved().put("newItems", JSONArray(listOf(product("one").json())))
        val result = update(original.toString(), listOf(product("one"), product("two")))!!
        assertEquals(2, result.record.getJSONArray("newItems").length())
        assertEquals(listOf("商品two"), result.alerts.map { it.title })
    }
    @Test fun firstBaselineDoesNotNotifyAllOldStockAsNew() {
        val result = update(saved().toString(), listOf(product("one")), initialize = true)!!
        assertTrue(result.alerts.isEmpty())
        assertEquals(0, result.record.getJSONArray("newItems").length())
        assertEquals(200L, result.record.getLong("listingCheckpointAt"))
    }
    @Test fun alertContainsStoreNameAndTitleWithoutUrl() {
        val result = update(saved().toString(), listOf(product("one")))!!
        assertEquals("測試店家", result.alerts.single().shop)
        assertEquals("商品one", result.alerts.single().title)
        assertEquals(url, result.record.getString("url"))
    }
    @Test fun initialBaselineRetainsCoverWithoutInventingNewItems() {
        val image = "https://img.ruten.com.tw/fixture.jpg"
        val result = update(saved().toString(), listOf(product("one").copy(image = image)), initialize = true)!!
        assertEquals(image, result.record.getString("image"))
        assertTrue(result.alerts.isEmpty())
        assertEquals(0, result.record.getJSONArray("newItems").length())
    }
    @Test fun partialPrefixSurvivesRestartWithoutMovingPageOrNotifyingTwice() {
        val at = 1791000000000L
        val checkpoint = at - 86400000
        val original = saved().put("listingCheckpointAt", checkpoint)
        val known = product("one", at)
        val unknown = product("two", 0)
        val state = SubscriptionNewItemsPaging(checkpoint, setOf("old")).observe(listOf(known, unknown))
        assertTrue(state.awaitingTime)
        assertEquals(listOf(known), state.collected)
        val prefix = ShopRefreshUpdate.build(url, original.toString(), Market.RUTEN, checkpoint, setOf("old"),
            listOf(known, unknown), state.collected, false, false, 1, at + 1000)!!
        BackgroundAlertJournal.append(prefix.record, prefix.alerts, at + 1000)
        val restored = JSONObject(prefix.record.toString())
        assertEquals(checkpoint, restored.getLong("listingCheckpointAt"))
        assertEquals(42L, restored.getLong("checkedAt"))
        assertEquals(1, restored.getJSONObject("refreshScan").getInt("page"))
        assertEquals(1, BackgroundAlertJournal.alerts(restored).size)
        val resumed = ShopRefreshUpdate.build(url, restored.toString(), Market.RUTEN, checkpoint, setOf("old"),
            listOf(known), state.collected, false, true, 2, at + 2000)!!
        assertTrue(resumed.alerts.isEmpty())
        assertEquals(1, BackgroundAlertJournal.alerts(resumed.record).size)
        assertEquals(at, resumed.record.getLong("listingCheckpointAt"))
    }
    @Test fun missingInvalidOrAdultImagesDoNotErasePreviousCover() {
        val record = saved().put("image", "https://img.ruten.com.tw/old.jpg")
        ShopSubscriptionCover.update(record, listOf(product("one").copy(image = "javascript:alert(1)"),
            product("two").copy(image = "https://img.ruten.com.tw/adult.jpg", isAdult = true)))
        assertEquals("https://img.ruten.com.tw/old.jpg", record.getString("image"))
    }
}

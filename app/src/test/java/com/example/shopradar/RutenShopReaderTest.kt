package com.example.shopradar

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RutenShopReaderTest {
    private val username = "fixture_shop"
    private val seller = "19883682"
    private val id1 = "22640786717437"
    private val id2 = "22640786694240"
    private val time = 1_790_777_794L
    private fun item(id: String, at: Long = time) = JSONObject()
        .put("id", id).put("user", username).put("ctrl_rowid", seller).put("platform", "RUTEN")
        .put("available", true).put("currency", "TWD").put("name", "測試手機")
        .put("goods_price_range", JSONObject().put("min", 3000)).put("post_time", at.toString())
        .put("class", "00210001").put("mode", "B").put("images", JSONObject()
            .put("url", JSONArray(listOf("https://gcs.rimg.com.tw/g5/fixture.jpg"))))
    private fun request(checkpoint: Long = time * 1000, page: Int = 1) = SearchCapture(Market.RUTEN,
        Market.RUTEN.rutenStorePage(username, page), token = 99,
        subscriptionCheckpoint = checkpoint, scanSubscription = checkpoint > 0)
    private fun fixture(calls: MutableList<String>, headAt: Long = time, total: Int = 2,
                        ids: List<String> = listOf(id1, id2)): suspend (String) -> JSONObject = { url ->
        calls += url
        when {
            url.endsWith("/storeinfo") -> JSONObject().put("status", "success").put("data", JSONObject()
                .put("user_id", seller).put("store_name", "測試店家"))
            url.contains("/core/seller/") -> JSONObject().put("TotalRows", total)
                .put("Rows", JSONArray(ids.map { JSONObject().put("Id", it) }))
            else -> {
                val asked = java.net.URI(url).rawQuery.split('&').first { it.startsWith("gno=") }.substringAfter('=').split(',')
                JSONObject().put("status", "success").put("data", JSONArray(asked.reversed().map { item(it, if (it == id1) headAt else time - 10) }))
            }
        }
    }
    @Test fun equalOrOlderFirstItemReadsOnlyOneDetailAndStops() = runBlocking {
        for (at in listOf(time, time - 1)) {
            val calls = mutableListOf<String>()
            val result = RutenShopReader.read(request(), fixture(calls, at))
            assertEquals(1, result.products.size)
            assertEquals(time * 1000 - (time - at) * 1000, result.products.single().listedAtEpochMillis)
            assertEquals(3, calls.size)
            assertTrue(calls.last().contains("gno=$id1&"))
            assertTrue(ShopCheckpoint.scan(result.products, time * 1000, emptySet()).reachedOld)
        }
    }
    @Test fun newFirstItemContinuesAndPreservesTheActualOldBoundaryAndDates() = runBlocking {
        val calls = mutableListOf<String>()
        val result = RutenShopReader.read(request(), fixture(calls, time + 10))
        assertEquals(listOf(id1, id2), result.products.map { RutenProductMetadata.productId(it.url) })
        assertEquals((time + 10) * 1000, result.products.first().listedAtEpochMillis)
        assertTrue(result.products.all { it.listedAt.contains("2026-") && it.shop == "測試店家" })
        assertEquals(4, calls.size)
        assertEquals(1, ShopCheckpoint.scan(result.products, time * 1000, emptySet()).products.size)
        assertTrue(result.exhausted)
    }
    @Test fun nativeRowsUseSellerIdAndOneBasedOffsetFromPageNotCaptureToken() = runBlocking {
        val calls = mutableListOf<String>()
        RutenShopReader.read(request(page = 2), fixture(calls, total = 40))
        assertTrue(calls[1].contains("/seller/$seller/prod?sort=new%2Fdc&offset=31&limit=30"))
        assertFalse(calls[1].contains(username))
    }
    @Test fun missingRowsAreNotMistakenForAnEmptyShop() = runBlocking {
        try { RutenShopReader.read(request(), fixture(mutableListOf(), total = 5, ids = emptyList())); fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(RutenShopReader.read(request(), fixture(mutableListOf(), total = 0, ids = emptyList())).exhausted)
    }
    @Test fun mismatchedSellerOrMissingDateCannotAdvanceTheWatermark() {
        for (broken in listOf(item(id1).put("user", "other_shop"), item(id1).put("ctrl_rowid", "123"),
            item(id1).put("id", id2), item(id1).removeTime(), item(id1).put("available", false))) {
            try { RutenShopReader.product(broken, id1, username, seller, "店家"); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }
    private fun JSONObject.removeTime() = apply { remove("post_time") }
    @Test fun ordinaryBuyModeIsNotMisidentifiedAsAnAuctionAndAdultCategoriesStayHidden() {
        assertFalse(RutenShopReader.product(item(id1), id1, username, seller, "店家").isAuction)
        assertTrue(RutenShopReader.product(item(id1).put("class", "00250005"), id1, username, seller, "店家").isAdult)
    }
    @Test fun formattedStoreDateHasTheSameWatermarkAsNumericDate() {
        val at = 1_791_003_961_000L
        val product = RutenShopReader.product(item(id1).put("post_time", "2026/10/03 13:06:01"), id1, username, seller, "店家")
        assertEquals("2026-10-03 13:06:01", product.listedAt)
        assertEquals(at, product.listedAtEpochMillis)
        assertTrue(ShopCheckpoint.scan(listOf(product), at, emptySet()).reachedOld)
    }
}

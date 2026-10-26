package com.example.shopradar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ShopSubscriptionCoverTest {
    @Test fun firstTimeoutDoesNotPreventNextRefreshFromSelectingCover() {
        val record = JSONObject().put("lastRefreshError", "逾時").put("listingCheckpointAt", 42)
        assertFalse(ShopSubscriptionCover.update(record, emptyList()))
        assertTrue(ShopSubscriptionCover.update(record, listOf(product(1))))
        assertEquals(product(1).image, record.getString("image"))
        assertEquals(42, record.getInt("listingCheckpointAt"))
    }
    private fun product(id: Int) = Product("商品$id", 50, Market.RUTEN.label,
        "https://www.ruten.com.tw/item/2264080771841$id", image = "https://img.ruten.com.tw/$id.jpg")

    @Test fun missingAvatarIsSelectedFromAllEligibleImages() {
        val products = (0..3).map(::product)
        val selected = (0..99).map { seed ->
            val record = JSONObject()
            assertTrue(ShopSubscriptionCover.update(record, products, Random(seed)))
            record.getString("image")
        }.toSet()
        assertEquals(products.map { it.image }.toSet(), selected)
    }

    @Test fun existingStoreAvatarIsNeverReplaced() {
        val record = JSONObject().put("image", "https://img.ruten.com.tw/store.jpg")
        assertFalse(ShopSubscriptionCover.update(record, listOf(product(0))))
        assertEquals("https://img.ruten.com.tw/store.jpg", record.getString("image"))
    }

    @Test fun selectionSurvivesReloadAndLaterRefresh() {
        val first = JSONObject()
        ShopSubscriptionCover.update(first, listOf(product(0), product(1)), Random(42))
        val reloaded = JSONObject(first.toString())
        assertFalse(ShopSubscriptionCover.update(reloaded, listOf(product(2))))
        assertEquals(first.getString("image"), reloaded.getString("image"))
    }

    @Test fun invalidAvatarCanBeRepairedButUnsafeCandidatesAreExcluded() {
        val record = JSONObject().put("image", "javascript:alert(1)")
        val products = listOf(product(0).copy(image = "http://img.ruten.com.tw/0.jpg"),
            product(1).copy(isAdvertisement = true), product(2).copy(isAdult = true),
            product(3).copy(title = "成人用品"), product(4).copy(shop = "成人專區"),
            product(5).copy(category = "成人玩具"), product(6))
        assertTrue(ShopSubscriptionCover.update(record, products))
        assertEquals(product(6).image, record.getString("image"))
    }

    @Test fun noImageDoesNotInventAnAvatarOrEraseOtherData() {
        val record = JSONObject().put("name", "測試店家")
        assertFalse(ShopSubscriptionCover.update(record, listOf(product(0).copy(image = ""))))
        assertFalse(record.has("image"))
        assertEquals("測試店家", record.getString("name"))
    }

    @Test fun retainedHistoryCanRepairLegacyStoreWithoutChangingCheckpointsOrOrder() {
        val record = JSONObject().put("listingCheckpointAt", 123L).put("checkedAt", 456L)
            .put("newItems", JSONArray(listOf(product(0).json(), product(1).json())))
            .put("seen", JSONArray(listOf("old"))).put("newCount", 2)
        val before = JSONObject(record.toString())
        assertTrue(ShopSubscriptionCover.update(record, ShopNewItems.read(record), Random(1)))
        record.remove("image")
        assertTrue(before.similar(record))
    }
}

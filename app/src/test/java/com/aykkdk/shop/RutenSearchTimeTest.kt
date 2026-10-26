package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RutenSearchTimeTest {
    private val epoch = 1_791_003_961_000L
    private fun detail() = JSONObject().put("ProdId", "22640807718410").put("ProdName", "測試手機")
        .put("PriceRange", JSONArray().put(100)).put("Currency", "TWD").put("PostTime", "2026/10/03 13:06:01")

    @Test fun searchStoresBothReadableAndComparableCreationTime() {
        val product = RutenSearch.metadataProduct(detail())!!
        assertEquals("2026-10-03 13:06:01", product.listedAt)
        assertEquals(epoch, product.listedAtEpochMillis)
        assertEquals(epoch, ShopCheckpoint.newest(listOf(product)))
        assertTrue(ShopCheckpoint.scan(listOf(product), epoch, emptySet()).reachedOld)
    }
    @Test fun enrichmentReplacesTextAndEpochTogetherRatherThanKeepingAStaleWatermark() {
        val old = RutenSearch.metadataProduct(detail())!!.copy(listedAt = "2026-10-01 13:06:01", listedAtEpochMillis = epoch - 172800000)
        val product = RutenSearch.enrichProduct(old, detail())
        assertEquals(epoch, product.listedAtEpochMillis)
        assertEquals("2026-10-03 13:06:01", product.listedAt)
        assertTrue(ShopCheckpoint.isNew(product, old.listedAtEpochMillis, emptySet()))
    }
    @Test fun failedDateEnrichmentKeepsTheLastConfirmedCreationTime() {
        val old = RutenSearch.metadataProduct(detail())!!
        for (missing in listOf(null, detail().put("PostTime", "invalid"))) {
            val product = RutenSearch.enrichProduct(old, missing)
            assertEquals(old.listedAt, product.listedAt)
            assertEquals(old.listedAtEpochMillis, product.listedAtEpochMillis)
        }
        assertEquals(0L, RutenSearch.metadataProduct(detail().put("PostTime", "invalid"))!!.listedAtEpochMillis)
    }
}

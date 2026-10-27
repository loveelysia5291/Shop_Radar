package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class RutenFiltersTest {
    @Test
    fun supportedSortsMatchSuppliedUrls() {
        val expected = listOf(
            "" to "熱門",
            "prc/ac" to "價格低",
            "prc/dc" to "價格高",
            "ords/dc" to "銷量",
            "new/dc" to "最新"
        )
        assertEquals(expected, RutenFilters.sorts)
    }

    @Test
    fun sortNormalizationAndValidation() {
        assertEquals("", RutenFilters.sort(""))
        assertEquals("prc/ac", RutenFilters.sort("prc/ac"))
        assertEquals("prc/dc", RutenFilters.sort("prc/dc"))
        assertEquals("ords/dc", RutenFilters.sort("ords/dc"))
        assertEquals("new/dc", RutenFilters.sort("new/dc"))
        assertEquals("", RutenFilters.sort("unknown"))
        assertEquals("", RutenFilters.sort("ctime"))
    }

    @Test
    fun marketRutenSearchUrlGeneration() {
        val popularUrl = Market.RUTEN.search("手機", "", "", rutenSort = "")
        assertTrue(popularUrl.contains("/search/%E6%89%8B%E6%A9%9F/"))
        assertFalse(popularUrl.contains("sort="))

        val priceAscUrl = Market.RUTEN.search("手機", "", "", rutenSort = "prc/ac")
        assertTrue(priceAscUrl.contains("sort=prc%2Fac") || priceAscUrl.contains("sort=prc/ac"))

        val priceDescUrl = Market.RUTEN.search("手機", "", "", rutenSort = "prc/dc")
        assertTrue(priceDescUrl.contains("sort=prc%2Fdc") || priceDescUrl.contains("sort=prc/dc"))

        val ordersUrl = Market.RUTEN.search("手機", "", "", rutenSort = "ords/dc")
        assertTrue(ordersUrl.contains("sort=ords%2Fdc") || ordersUrl.contains("sort=ords/dc"))

        val newestUrl = Market.RUTEN.search("手機", "", "", rutenSort = "new/dc")
        assertTrue(newestUrl.contains("sort=new%2Fdc") || newestUrl.contains("sort=new/dc"))
    }

    @Test
    fun rutenSortRoundTripInSearchHistory() {
        for ((sortValue, label) in RutenFilters.sorts) {
            val criteria = SearchHistoryCriteria(Market.RUTEN, "手機", sort = sortValue)
            val recorded = SearchHistory.record(emptyList(), criteria)
            val decoded = SearchHistory.decode(SearchHistory.encode(recorded))
            assertEquals(1, decoded.size)
            assertEquals(sortValue, decoded.single().criteria.sort)
            assertTrue(decoded.single().criteria.summary().startsWith(label))
        }
    }
}

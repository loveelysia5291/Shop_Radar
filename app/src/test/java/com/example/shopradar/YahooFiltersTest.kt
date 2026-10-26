package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class YahooFiltersTest {
    @Test fun stateAndSortParametersMatchSuppliedLinks() {
        assertFalse(YahooFilters.parameters("0", "-ptime").toMap().containsKey("refine"))
        listOf("1" to "buy_now", "2" to "bidding", "3" to "con_used").forEach { (state, refine) ->
            val params = YahooFilters.parameters(state, "etime").toMap()
            assertEquals(state, params["acu"])
            assertEquals(refine, params["refine"])
        }
        listOf("-curp", "curp", "-ptime", "etime", "popular", "auc_ranking").forEach {
            assertEquals(it, YahooFilters.parameters("0", it).toMap()["sort"])
        }
    }
    @Test fun relevanceHasNoSortAndNewListingsMatchesSuppliedQuery() {
        assertTrue(YahooFilters.parameters("0", "").isEmpty())
        assertFalse(YahooFilters.parameters("0", "unknown").toMap().containsKey("sort"))
        assertEquals(mapOf("sort" to "-ptime"), YahooFilters.parameters("0", "-ptime").toMap())
        assertEquals(mapOf("sort" to "etime"), YahooFilters.parameters("0", "etime").toMap())
        assertEquals("新刊登", YahooFilters.sorts.first { it.first == "-ptime" }.second)
        assertEquals("即將結標", YahooFilters.sorts.first { it.first == "etime" }.second)
        assertFalse(YahooFilters.sorts.any { it.first == "ontime" || it.second == "最新更新" })
        assertTrue(YahooFilters.parameters("0", "ontime").isEmpty())
        assertTrue(YahooFilters.parameters("0", "promo_new").isEmpty())
    }
    @Test fun newListingsPreservesSelectedStateWithoutPromoRefinement() {
        assertEquals(mapOf("acu" to "2", "refine" to "bidding", "sort" to "-ptime"),
            YahooFilters.parameters("2", "-ptime").toMap())
    }
    @Test fun auctionFieldsSurviveProductHistoryRoundTrip() {
        val item = Product("Phone", 1200, "Y拍", "https://tw.bid.yahoo.com/item/101764768005",
            isAuction = true, auctionEndsAt = 1800000000000, highestBidder = "Y123***",
            auctionPriceKnown = true)
        assertEquals(item, Product.parse(item.json()))
        val old = item.json().apply {
            remove("isAuction"); remove("auctionEndsAt"); remove("highestBidder"); remove("auctionPriceKnown")
        }
        assertFalse(Product.parse(old)!!.isAuction)
    }
}

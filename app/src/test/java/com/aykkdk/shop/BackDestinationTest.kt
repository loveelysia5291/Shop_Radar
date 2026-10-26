package com.aykkdk.shop

import org.junit.Assert.assertEquals
import org.junit.Test

class BackDestinationTest {
    @Test fun topmostVisiblePageWinsForEveryCombination() {
        for (bits in 0 until 64) {
            val flags = (0..5).map { bits and (1 shl it) != 0 }
            val expected = when {
                flags[0] -> BackDestination.NEW_ITEMS
                flags[1] -> BackDestination.SUBSCRIPTION_PRODUCTS
                flags[2] -> BackDestination.SHOP
                flags[3] -> BackDestination.SEARCH_RESULTS
                flags[5] -> BackDestination.SHOP_LOADING
                flags[4] -> BackDestination.SEARCH_LOADING
                else -> BackDestination.ROOT
            }
            assertEquals("flags=$bits", expected, BackDestination.resolve(
                flags[0], flags[1], flags[2], flags[3], flags[4], flags[5]))
        }
    }

    @Test fun streamingSearchCanAlwaysReturn() {
        assertEquals(BackDestination.SEARCH_RESULTS,
            BackDestination.resolve(false, false, false, true, true, false))
    }

    @Test fun shopProductsReturnToShopBeforeRoot() {
        assertEquals(BackDestination.SUBSCRIPTION_PRODUCTS,
            BackDestination.resolve(false, true, true, false, false, true))
        assertEquals(BackDestination.SHOP,
            BackDestination.resolve(false, false, true, false, false, false))
    }
}

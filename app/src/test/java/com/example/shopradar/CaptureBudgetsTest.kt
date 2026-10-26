package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class CaptureBudgetsTest {
    @Test fun slowPublicPagesGetExtraTimeWithoutReducingResultCount() {
        assertEquals(15_000L, CaptureBudgets.searchMillis(Market.SHOPEE.label))
        assertEquals(15_000L, CaptureBudgets.reader(Market.SHOPEE, quick = true, more = false))
        assertEquals(15_000L, CaptureBudgets.searchMillis(Market.YAHOO.label))
        assertEquals(15_000L, CaptureBudgets.reader(Market.YAHOO, quick = true, more = false))
        for (market in Market.values().filter { it !in setOf(Market.SHOPEE, Market.YAHOO) }) {
            assertEquals(15_000L, CaptureBudgets.searchMillis(market.label))
            assertEquals(15_000L, CaptureBudgets.reader(market, quick = true, more = false))
        }
        assertEquals(15_000L, CaptureBudgets.searchMillis("全部平台"))
    }
    @Test fun earlyFourOrSixteenShopeeCardsCannotCloseAnUnfinishedPage() {
        for (count in listOf(0, 1, 4, 16, 60)) assertTrue(CaptureBudgets.waitForReader(Market.SHOPEE.label, count))
        assertTrue(CaptureBudgets.waitForReader(Market.YAHOO.label, 0))
        assertTrue(CaptureBudgets.waitForReader(Market.YAHOO.label, 7))
        assertFalse(CaptureBudgets.waitForReader(Market.YAHOO.label, 15))
        assertFalse(CaptureBudgets.waitForReader(Market.FACEBOOK.label, 0))
        assertFalse(CaptureBudgets.waitForReader("全部平台", 0))
    }
    @Test fun loadMoreIsBoundedButBackgroundSubscriptionBudgetsAreUnchanged() {
        assertEquals(15_000L, CaptureBudgets.reader(Market.CAROUSELL, quick = false, more = true))
        assertEquals(30_000L, CaptureBudgets.reader(Market.SHOPEE, quick = false, more = false))
        assertEquals(35_000L, CaptureBudgets.reader(Market.FACEBOOK, quick = false, more = false))
        assertEquals(55_000L, CaptureBudgets.reader(Market.YAHOO, quick = false, more = false))
    }
}

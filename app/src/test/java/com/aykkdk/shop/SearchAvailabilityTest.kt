package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class SearchAvailabilityTest {
    @Test fun pickerRestoresFacebookWithoutRemovingOtherPlatforms() {
        assertEquals(listOf(Market.SHOPEE, Market.RUTEN, Market.CAROUSELL, Market.YAHOO, Market.FACEBOOK, Market.BBOBO, Market.IOPEN), Market.searchMarkets)
        assertTrue(Market.FACEBOOK.supportsSearch)
        assertTrue(Market.searchMarkets.all { it.supportsSearch })
    }

    @Test fun restoredFacebookSelectionWorksAndUnknownSelectionsFallBack() {
        assertEquals(Market.FACEBOOK, Market.searchSelection(Market.FACEBOOK.label))
        assertEquals(Market.CAROUSELL, Market.searchSelection("不存在的平台"))
        Market.searchMarkets.forEach { assertEquals(it, Market.searchSelection(it.label)) }
    }

    @Test fun existingFacebookSubscriptionsAndPublicLinksRemainRecognizable() {
        val url = "https://www.facebook.com/marketplace/item/1120292097099668/?ref=search"
        assertEquals(Market.FACEBOOK, Market.fromPublicProductUrl(url))
        assertEquals(Market.FACEBOOK, Market.fromSubscription("FACEBOOK", url))
        assertEquals(Market.FACEBOOK, Market.fromSubscription("Facebook", url))
        assertEquals("https://www.facebook.com/marketplace/item/1120292097099668/", Market.normalizePublicProductUrl(url))
        assertTrue(Market.FACEBOOK.supportsShopSubscriptions)
    }

    @Test fun oldFacebookHistorySurvivesUpdatesToOtherPlatforms() {
        val original = SearchHistory.record(emptyList(), SearchHistoryCriteria(Market.FACEBOOK, "電腦"))
        val updated = SearchHistory.record(SearchHistory.decode(SearchHistory.encode(original)), SearchHistoryCriteria(Market.SHOPEE, "手機"))
        assertEquals(original.single(), updated.single { it.criteria.market == Market.FACEBOOK })
        assertEquals(updated, SearchHistory.decode(SearchHistory.encode(updated)))
    }
}

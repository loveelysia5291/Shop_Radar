package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class ShopPreviewPresentationTest {
    @Test fun emptySlowStoreKeepsTheSearchDialog() {
        for (elapsed in listOf(0L, 5_000L, 10_000L, 14_999L, 15_000L)) {
            assertFalse(ShopPreviewPresentation.showResults(elapsed, 0))
        }
    }

    @Test fun firstVisibleCardsPublishAfterFiveSeconds() {
        assertFalse(ShopPreviewPresentation.showResults(4_999, 4))
        assertTrue(ShopPreviewPresentation.showResults(5_000, 1))
        assertTrue(ShopPreviewPresentation.showResults(8_000, 4))
        assertTrue(ShopPreviewPresentation.showResults(14_000, 16))
    }
}

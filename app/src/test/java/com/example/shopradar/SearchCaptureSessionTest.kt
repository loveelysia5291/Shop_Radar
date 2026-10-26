package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class SearchCaptureSessionTest {
    @Test fun shopeeNetworkRetryUsesTheSameSession() {
        val first = SearchCapture(Market.SHOPEE, "https://shopee.tw/search?keyword=phone&page=1", 7,
            quickSearch = true, previousPageUrls = setOf("https://shopee.tw/product/1/2"))
        assertEquals(first.readerSession(), first.copy(retryAttempt = 1).readerSession())
        assertEquals(first, first.readerSession())
    }

    @Test fun storeRetryKeepsPreviewIdentityAndParameters() {
        val first = SearchCapture(Market.SHOPEE, "https://shopee.tw/shop/123", 8, shopPreview = true)
        assertEquals(first.readerSession(), first.copy(retryAttempt = 1).readerSession())
        assertTrue(first.copy(retryAttempt = 1).readerSession().shopPreview)
    }

    @Test fun newSearchPageTokenAndRecoveryStillCreateNewSessions() {
        val first = SearchCapture(Market.SHOPEE, "https://shopee.tw/search?page=0", 9, quickSearch = true)
        assertNotEquals(first.readerSession(), first.copy(token = 10).readerSession())
        assertNotEquals(first.readerSession(), first.copy(url = "https://shopee.tw/search?page=1").readerSession())
        assertNotEquals(first.readerSession(), first.copy(officialRecoveryAttempt = 1).readerSession())
        assertNotEquals(first.readerSession(), first.copy(previousPageUrls = setOf("old")).readerSession())
    }

    @Test fun yahooContinuousStoreFeedKeepsItsRendererOnRetryAndScroll() {
        val first = SearchCapture(Market.YAHOO, "https://tw.bid.yahoo.com/booth/Y2369592298", 1, shopPreview = true)
        assertEquals(first.readerSession(), first.copy(retryAttempt = 1, consumedProducts = 10).readerSession())
        assertNotEquals(first.readerSession(), first.copy(token = 2).readerSession())
        val numbered = SearchCapture(Market.IOPEN, "https://www.iopenmall.tw/", 1)
        assertNotEquals(numbered.readerSession(), numbered.copy(retryAttempt = 1).readerSession())
    }
}

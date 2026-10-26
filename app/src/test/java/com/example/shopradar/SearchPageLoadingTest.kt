package com.example.shopradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPageLoadingTest {
    @Test fun boboWaitsSevenSecondsAndNeverPublishesAnUnenrichedPreview() {
        assertEquals(7000L, SearchPageLoading.prepareRemaining(Market.BBOBO, 0))
        assertEquals(4000L, SearchPageLoading.prepareRemaining(Market.BBOBO, 3000))
        assertEquals(0L, SearchPageLoading.prepareRemaining(Market.BBOBO, 7000))
        assertEquals(0L, SearchPageLoading.prepareRemaining(Market.BBOBO, 9000))
        assertEquals(0L, SearchPageLoading.prepareRemaining(Market.IOPEN, 100))
        assertFalse(SearchPageLoading.showPreview(100, 24, Market.BBOBO))
        assertFalse(SearchPageLoading.showPreview(6999, 24, Market.BBOBO))
        assertFalse(SearchPageLoading.showPreview(8000, 24, Market.BBOBO))
        assertTrue(SearchPageLoading.showPreview(100, 24, Market.IOPEN))
    }
    @Test fun slowFeedsFillFirstTwoSmallBatchesAtFastPollRate() {
        for (market in listOf(Market.SHOPEE, Market.YAHOO)) {
            assertTrue(SearchPageLoading.fastFirstBatch(market, 0))
            assertEquals(250L, SearchPageLoading.feedPollMillis(market, 11))
            assertFalse(SearchPageLoading.fastFirstBatch(market, 12))
            assertEquals(650L, SearchPageLoading.feedPollMillis(market, 12))
            assertEquals(6, SearchPageLoading.firstBatch(market))
        }
        for (market in listOf(Market.RUTEN, Market.CAROUSELL, Market.FACEBOOK, Market.BBOBO, Market.IOPEN))
            assertEquals(650L, SearchPageLoading.feedPollMillis(market, 0))
    }
    @Test fun facebookAndCarousellPublishFifteenThenTenAtATime() {
        listOf(Market.FACEBOOK, Market.CAROUSELL).forEach { market ->
            assertEquals(0, SearchPageLoading.batchCount(14, market))
            assertEquals(15, SearchPageLoading.batchCount(15, market))
            assertEquals(15, SearchPageLoading.batchCount(24, market))
            assertEquals(25, SearchPageLoading.batchCount(25, market))
            assertEquals(35, SearchPageLoading.batchCount(39, market))
            assertFalse(SearchPageLoading.showPreview(9_999, 14, market))
            assertTrue(SearchPageLoading.showPreview(1, 15, market))
            assertEquals(10_000L, SearchPageLoading.emptyDeadline(market, true))
        }
    }
    @Test fun shopeeAndYahooPublishSmallBatchesWithBoundedPartialPreview() {
        listOf(Market.SHOPEE, Market.YAHOO).forEach { market ->
            assertEquals(0, SearchPageLoading.batchCount(5, market))
            assertEquals(6, SearchPageLoading.batchCount(6, market))
            assertEquals(6, SearchPageLoading.batchCount(11, market))
            assertEquals(12, SearchPageLoading.batchCount(12, market))
            assertEquals(18, SearchPageLoading.batchCount(23, market))
            assertFalse(SearchPageLoading.showPreview(2999, 5, market))
            assertTrue(SearchPageLoading.showPreview(3000, 5, market))
            assertTrue(SearchPageLoading.showPreview(1, 6, market))
            assertFalse(SearchPageLoading.showPreview(10000, 0, market))
            assertEquals(10000L, SearchPageLoading.emptyDeadline(market, true))
        }
    }
    @Test fun rutenBufferUsesHysteresisAndResetsStallClockAfterResume() {
        assertFalse(SearchPageLoading.pauseFeed(false, 50))
        assertTrue(SearchPageLoading.pauseFeed(false, 51))
        assertTrue(SearchPageLoading.pauseFeed(true, 15))
        assertFalse(SearchPageLoading.pauseFeed(true, 14))
        val end = SearchPageLoading.FeedEnd()
        end.observe("old", false, true, false, 0)
        assertTrue(end.stalled(60_000))
        end.reset()
        assertFalse(end.stalled(60_000))
    }
    @Test fun onlyPartialTimeoutOffersLoadMoreInsteadOfAnError() {
        assertTrue(SearchPageLoading.loadMoreAfterTimeout(10, SearchPageLoading.PARTIAL_MESSAGE))
        assertTrue(SearchPageLoading.loadMoreAfterTimeout(10, "蝦皮抓取逾時，請按重試。"))
        assertTrue(SearchPageLoading.loadMoreAfterTimeout(3, SearchWaitPolicy.TIMEOUT_MESSAGE))
        assertFalse(SearchPageLoading.loadMoreAfterTimeout(0, SearchPageLoading.PARTIAL_MESSAGE))
        assertFalse(SearchPageLoading.loadMoreAfterTimeout(10, "HTTP 404"))
        assertFalse(SearchPageLoading.loadMoreAfterTimeout(10, "需要完成官方登入／驗證"))
        assertFalse(SearchPageLoading.loadMoreAfterTimeout(10, "SSL 連線錯誤"))
    }
    @Test fun fourCardsAtTheTopAreNotACompletePageEvenAfterLongWait() {
        val gate = SearchPageLoading.Completion()
        assertFalse(gate.ready("four", false, 0))
        assertFalse(gate.ready("four", false, 20_000))
        assertFalse(gate.ready("four", true, 20_500))
        assertFalse(gate.ready("four", true, 22_999))
        assertTrue(gate.ready("four", true, 23_000))
    }

    @Test fun newLazyRowsAndHydrationGapsRestartCompletionWait() {
        val gate = SearchPageLoading.Completion()
        assertFalse(gate.ready("six", true, 0))
        assertFalse(gate.ready("sixteen", true, 2_000))
        assertFalse(gate.ready("sixty", false, 4_000))
        assertFalse(gate.ready("sixty", true, 5_000))
        gate.reset()
        assertFalse(gate.ready("sixty", true, 8_000))
        assertTrue(gate.ready("sixty", true, 10_500))
        assertFalse(gate.ready("", true, 14_000))
    }

    @Test fun partialPageKeepsReadingButEmptyPageRetainsOriginalDeadline() {
        assertEquals(10_000L, SearchPageLoading.deadline(0))
        assertEquals(10_000L, SearchPageLoading.emptyDeadline(Market.SHOPEE, true))
        assertEquals(10_000L, SearchPageLoading.emptyDeadline(Market.SHOPEE, false))
        assertEquals(60_000L, SearchPageLoading.deadline(4))
        assertEquals(60_000L, SearchPageLoading.deadline(6))
        assertEquals(60_000L, SearchPageLoading.deadline(60))
    }

    @Test fun twoColumnsWaitForWholeRowsUntilPageCompletes() {
        assertEquals(6, SearchPageLoading.readyCount(7, 2, true))
        assertEquals(8, SearchPageLoading.readyCount(8, 2, true))
        assertEquals(7, SearchPageLoading.readyCount(7, 2, false))
        assertEquals(3, SearchPageLoading.readyCount(3, 2, false))
        assertEquals(0, SearchPageLoading.readyCount(0, 2, true))
    }

    @Test fun singleColumnPublishesEachNewProduct() {
        assertEquals(7, SearchPageLoading.readyCount(7, 1, true))
        assertEquals(7, SearchPageLoading.nextRow(6, 12, 1))
        assertEquals(8, SearchPageLoading.nextRow(7, 12, 1))
    }

    @Test fun revealStartsWithTenAndAddsOneCompleteRow() {
        var shown = SearchPageLoading.FIRST_BATCH
        val rows = mutableListOf(shown)
        while (shown < 12) {
            shown = SearchPageLoading.nextRow(shown, 12, 2)
            rows.add(shown)
        }
        assertEquals(listOf(10, 12), rows)
        assertEquals(13, SearchPageLoading.nextRow(12, 13, 2))
    }

    @Test fun switchingToGridDoesNotExposeAnIncompleteRowDuringLoading() {
        assertEquals(6, SearchPageLoading.readyCount(7, 2, true))
        assertEquals(8, SearchPageLoading.nextRow(7, 12, 2))
        assertEquals(0, SearchPageLoading.readyCount(-1, 2, true))
    }

    @Test fun showTenImmediatelyOrPartialAtTenSeconds() {
        assertFalse(SearchPageLoading.showPreview(9_999, 9))
        assertTrue(SearchPageLoading.showPreview(200, 10))
        assertTrue(SearchPageLoading.showPreview(10_000, 3))
        assertFalse(SearchPageLoading.showPreview(15_000, 0))
    }

    @Test fun mobileFeedsAndNumberedSitesRemainDistinct() {
        listOf(Market.SHOPEE, Market.YAHOO, Market.CAROUSELL, Market.RUTEN, Market.FACEBOOK).forEach {
            assertTrue(SearchPageLoading.infinite(it))
        }
        listOf(Market.BBOBO, Market.IOPEN).forEach { assertFalse(SearchPageLoading.infinite(it)) }
    }

    @Test fun scrollingAndNewRowsDelayExhaustion() {
        val end = SearchPageLoading.FeedEnd()
        assertFalse(end.observe("10:top", false, true, false, 0))
        assertFalse(end.observe("10:bottom", true, true, false, 1_000))
        assertFalse(end.observe("10:bottom", true, true, false, 10_999))
        assertFalse(end.observe("20:bottom", true, true, false, 11_000))
        assertFalse(end.observe("20:bottom", true, true, false, 20_999))
        assertTrue(end.observe("20:bottom", true, true, false, 21_000))
    }

    @Test fun busyOrUnreadyPagesAreNotSuccessfulEmptyResults() {
        val end = SearchPageLoading.FeedEnd()
        assertFalse(end.observe("10:bottom", true, true, true, 0))
        assertFalse(end.observe("10:bottom", true, false, false, 10_000))
        assertFalse(end.observe("10:bottom", true, true, false, 20_000))
        assertFalse(end.observe("10:bottom", true, true, false, 29_999))
        assertTrue(end.stalled(30_000))
        assertFalse(end.observe("20:bottom", true, true, false, 30_001))
        assertFalse(end.stalled(30_002))
    }
}

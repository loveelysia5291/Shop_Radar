package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class SubscriptionNewItemsPagingTest {
    private val baseline = ShopCheckpoint.millis("2026-10-02 11:00:00", 0)!!
    private fun item(n: Int, at: Long = baseline + n * 60000) = Product("商品$n", 100, "露天", "url-$n", listedAtEpochMillis = at)
    @Test fun equalOrOlderFirstItemStopsWithoutNewItems() {
        for (at in listOf(baseline, baseline - 60000)) {
            val state = SubscriptionNewItemsPaging(baseline, emptySet()).observe(listOf(item(0, at), item(1))).publish()
            assertTrue(state.reachedOld)
            assertFalse(state.hasNew)
            assertFalse(state.canNext)
            assertFalse(state.canPrevious)
            assertEquals(emptyList<Product>(), state.displayed)
        }
    }
    @Test fun unfinishedFiveSecondPageReservesExactlyOneRealItem() {
        val items = (10 downTo 1).map { item(it) }
        val state = SubscriptionNewItemsPaging(baseline, emptySet()).observe(items).publish()
        assertEquals(items.take(9), state.displayed)
        assertEquals(listOf(items.last()), state.pending)
        assertTrue(state.canNext)
        assertFalse(state.canPrevious)
        assertEquals(baseline, state.checkpoint)
    }
    @Test fun nextPageStartsWithCarryAndStopsAtOriginalCheckpointWithoutDuplicates() {
        val first = (20 downTo 11).map { item(it) }
        var state = SubscriptionNewItemsPaging(baseline, emptySet()).observe(first).publish().next()
        state = state.observe(first + (10 downTo 1).map { item(it) } + item(0)).publish()
        assertEquals(first.last(), state.displayed.first())
        assertEquals(11, state.displayed.size)
        assertEquals(20, state.pages.flatten().distinctBy { it.url }.size)
        assertTrue(state.reachedOld)
        assertFalse(state.canNext)
        assertTrue(state.canPrevious)
        assertEquals(baseline, state.checkpoint)
        assertEquals(first.take(9), state.previous().displayed)
    }
    @Test fun completedBeforeDeadlineDoesNotReserveAndBothButtonsAreDisabled() {
        val items = (10 downTo 1).map { item(it) }
        val state = SubscriptionNewItemsPaging(baseline, emptySet()).observe(items + item(0)).publish()
        assertEquals(items, state.displayed)
        assertTrue(state.pending.isEmpty())
        assertFalse(state.canNext)
        assertFalse(state.canPrevious)
    }
    @Test fun backgroundCompletionKeepsPublishedPageAndCarryAvailable() {
        val state = SubscriptionNewItemsPaging(baseline, emptySet()).observe((10 downTo 1).map { item(it) }).publish()
            .observe((10 downTo 1).map { item(it) } + item(0))
        assertEquals(9, state.displayed.size)
        assertTrue(state.canNext)
        assertEquals(listOf(item(1)), state.next().publish().displayed)
    }
    @Test fun missingTimeCannotSilentlyAdvanceBaselineOrClaimNoNewItems() {
        val state = SubscriptionNewItemsPaging(baseline, emptySet()).observe(listOf(item(1, 0), item(0)))
        assertTrue(state.awaitingTime)
        assertFalse(state.reachedOld)
        assertFalse(state.hasNew)
        assertEquals(baseline, state.checkpoint)
    }
    @Test fun updatesKeepFirstKnownTimestampAndNeverDuplicatePublishedItems() {
        val state = SubscriptionNewItemsPaging(baseline, emptySet()).observe(listOf(item(3), item(2))).publish()
            .observe(listOf(item(3).copy(listedAtEpochMillis = baseline + 999999), item(2)))
        assertEquals(item(3).listedAtEpochMillis, state.collected.first().listedAtEpochMillis)
        assertEquals(1, state.pending.size)
    }
    @Test fun confirmingBoundaryAfterRetryDoesNotAddAnEmptyPage() {
        val initial = SubscriptionNewItemsPaging(baseline, emptySet()).observe(listOf(item(2), item(1)))
            .fail("讀取失敗")
        val resumed = initial.copy(collecting = true, error = null)
            .observe(listOf(item(2), item(1), item(0))).publish()
        assertEquals(1, resumed.pages.size)
        assertEquals(2, resumed.displayed.size)
        assertTrue(resumed.reachedOld)
        assertFalse(resumed.canPrevious)
        assertFalse(resumed.canNext)
    }
    @Test fun missingDateCanBeResolvedWithoutSkippingAnItem() {
        val state = SubscriptionNewItemsPaging(baseline, emptySet())
            .observe(listOf(item(2), item(1, 0), item(0)))
        assertEquals(listOf(item(2)), state.collected)
        assertTrue(state.awaitingTime)
        val resolved = state.observe(listOf(item(2), item(1), item(0))).publish()
        assertEquals(listOf(item(2), item(1)), resolved.displayed)
        assertTrue(resolved.reachedOld)
        assertFalse(resolved.awaitingTime)
    }
    @Test fun seenIdentityStopsEvenWhenRelativeTimestampIsMissing() {
        val state = SubscriptionNewItemsPaging(baseline, setOf("url-0"))
            .observe(listOf(item(0, 0), item(1))).publish()
        assertTrue(state.reachedOld)
        assertFalse(state.hasNew)
        assertFalse(state.awaitingTime)
    }
}

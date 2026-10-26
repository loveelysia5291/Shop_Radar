package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class ScreenBackBindingTest {
    @Test fun unboundAndDisabledMappingsCannotConsumeBack() {
        var enabled = true
        val binding = ScreenBackBinding { enabled = it }
        assertFalse(binding.dispatch())
        binding.bind(Any(), false) { fail("disabled action must not run") }
        assertFalse(enabled)
        assertFalse(binding.dispatch())
    }
    @Test fun switchingScreensInvokesLatestActionExactlyOnce() {
        var enabled = false
        val binding = ScreenBackBinding { enabled = it }
        val token = Any()
        var previousCalls = 0
        var currentCalls = 0
        binding.bind(token, true) { previousCalls++ }
        assertTrue(enabled)
        assertTrue(binding.dispatch())
        binding.bind(token, true) { currentCalls++ }
        assertTrue(binding.dispatch())
        assertEquals(1, previousCalls)
        assertEquals(1, currentCalls)
    }
    @Test fun oldCompositionDisposalCannotUnbindTheNewScreen() {
        var enabled = false
        var calls = 0
        val binding = ScreenBackBinding { enabled = it }
        val old = Any()
        val current = Any()
        binding.bind(old, true) { fail("stale screen") }
        binding.bind(current, true) { calls++ }
        binding.clear(old)
        assertTrue(enabled)
        assertTrue(binding.dispatch())
        assertEquals(1, calls)
        binding.clear(current)
        assertFalse(enabled)
        assertFalse(binding.dispatch())
    }
    @Test fun backgroundAndResumeDoNotReuseTheOldAction() {
        val binding = ScreenBackBinding {}
        val token = Any()
        var page = "search-results"
        binding.bind(token, true) { page = "search" }
        binding.bind(token, false) { fail("background callback") }
        assertFalse(binding.dispatch())
        binding.bind(token, true) { page = "subscriptions" }
        assertTrue(binding.dispatch())
        assertEquals("subscriptions", page)
    }
    @Test fun mappingExecutesThePageClosureNotJustTheDestinationEnum() {
        val binding = ScreenBackBinding {}
        var results = true
        var reading = true
        binding.bind(Any(), true) {
            when (BackDestination.resolve(false, false, false, results, reading, false)) {
                BackDestination.SEARCH_RESULTS -> { reading = false; results = false }
                else -> fail("wrong mapped destination")
            }
        }
        assertTrue(binding.dispatch())
        assertFalse(results)
        assertFalse(reading)
    }
}

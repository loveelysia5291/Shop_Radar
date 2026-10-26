package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class RootBackPolicyTest {
    @Test fun subscriptionsAndAuctionsReturnToSearchWithoutArmingExit() {
        for (page in listOf(1, 2)) {
            val policy = RootBackPolicy()
            assertEquals(RootBackPolicy.Action.SEARCH, policy.back(page, 100))
            assertEquals(RootBackPolicy.Action.PROMPT, policy.back(0, 200))
            assertEquals(RootBackPolicy.Action.BACKGROUND, policy.back(0, 300))
        }
    }

    @Test fun onlyTwoSearchBacksWithinTwoSecondsMoveToBackground() {
        val policy = RootBackPolicy()
        assertEquals(2_000L, RootBackPolicy.WINDOW_MILLIS)
        assertEquals(RootBackPolicy.Action.PROMPT, policy.back(0, 0))
        assertEquals(RootBackPolicy.Action.BACKGROUND, policy.back(0, 2_000))
        assertEquals(RootBackPolicy.Action.PROMPT, policy.back(0, 2_001))
    }

    @Test fun expiredFirstBackStartsANewWindow() {
        val policy = RootBackPolicy()
        assertEquals(RootBackPolicy.Action.PROMPT, policy.back(0, 100))
        assertEquals(RootBackPolicy.Action.PROMPT, policy.back(0, 2_101))
        assertEquals(RootBackPolicy.Action.BACKGROUND, policy.back(0, 2_200))
    }

    @Test fun returningFromAnotherPageDoesNotReuseAPreviousSearchBack() {
        val policy = RootBackPolicy()
        policy.back(0, 100)
        assertEquals(RootBackPolicy.Action.SEARCH, policy.back(2, 200))
        assertEquals(RootBackPolicy.Action.PROMPT, policy.back(0, 300))
    }

    @Test fun lifecycleKeyboardAndModalResetPreventAccidentalExit() {
        val policy = RootBackPolicy()
        policy.back(0, 100)
        policy.reset()
        assertEquals(RootBackPolicy.Action.PROMPT, policy.back(0, 200))
    }

    @Test fun backwardsClockCannotCountAsASecondBack() {
        val policy = RootBackPolicy()
        policy.back(0, 1_000)
        assertEquals(RootBackPolicy.Action.PROMPT, policy.back(0, 900))
    }
}

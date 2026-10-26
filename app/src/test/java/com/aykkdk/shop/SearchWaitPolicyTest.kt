package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class SearchWaitPolicyTest {
    @Test fun retryOnlyOnceAfterTwoFiveSecondWindowsWithoutExtendingDeadline() {
        assertEquals(5_000L, SearchWaitPolicy.SLOW_MILLIS)
        assertEquals(15_000L, SearchWaitPolicy.DEADLINE_MILLIS)
        assertFalse(SearchWaitPolicy.retry(0, 0, false))
        assertFalse(SearchWaitPolicy.retry(5_000, 0, false))
        assertFalse(SearchWaitPolicy.retry(9_999, 0, false))
        assertTrue(SearchWaitPolicy.retry(10_000, 0, false))
        assertFalse(SearchWaitPolicy.retry(10_100, 0, true))
        assertFalse(SearchWaitPolicy.retry(15_000, 0, false))
        assertFalse(SearchWaitPolicy.retry(20_000, 0, false))
    }
    @Test fun noRetryWhenProductsAlreadyArrived() {
        for (count in listOf(1, 4, 15, 80)) assertFalse(SearchWaitPolicy.retry(10_000, count, false))
    }
    @Test fun explicitHttpFailuresNeverEnterSlowNetworkRetry() {
        for (code in listOf(400, 401, 403, 404, 410, 429, 500, 503)) {
            val error = SearchHttpException(code, "平台")
            assertEquals(code, error.statusCode)
            assertTrue(error.message!!.contains("HTTP $code"))
            assertFalse(SearchWaitPolicy.retry(10_000, 0, false, terminalError = true))
        }
    }
    @Test fun schedulerVersionMigratesBatteryConstrainedRequests() {
        assertTrue(RefreshSchedulePolicy.needsReplacement(3, listOf(setOf("radar-refresh-generation-3"))))
        assertFalse(RefreshSchedulePolicy.needsReplacement(3, listOf(setOf(RefreshSchedulePolicy.tag(3)))))
    }
}

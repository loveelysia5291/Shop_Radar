package com.aykkdk.shop

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshTimeDisplayTest {
    private val at = 1_790_000_000_000L

    @Test fun missingAndInvalidTimestampsDoNotBecomeHugeAges() {
        assertEquals("尚未刷新", RefreshTimeDisplay.text(0, at))
        assertEquals("尚未刷新", RefreshTimeDisplay.text(-1, at))
        assertEquals("剛剛", RefreshTimeDisplay.text(at + 60_000, at))
    }

    @Test fun unitsChangeOnlyAfterACompleteMinuteHourOrDay() {
        for ((elapsed, expected) in listOf(0L to "剛剛", 59_999L to "剛剛", 60_000L to "1 分鐘前",
            3_599_999L to "59 分鐘前", 3_600_000L to "1 小時前", 86_399_999L to "23 小時前",
            86_400_000L to "1 天前", 2_592_000_000L to "30 天前")) {
            assertEquals(expected, RefreshTimeDisplay.text(at, at + elapsed))
        }
    }
}

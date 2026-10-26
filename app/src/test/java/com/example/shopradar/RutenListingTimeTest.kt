package com.example.shopradar

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class RutenListingTimeTest {
    private val epoch = 1_791_003_961_000L // Public PostTime: 2026/10/03 13:06:01, Asia/Taipei.

    @Test fun publicFormattedSecondsAndMillisecondsHaveTheSameTaiwanTime() {
        for (raw in listOf("2026/10/03 13:06:01", "2026-10-03 13:06:01", epoch / 1000, epoch)) {
            val time = RutenListingTime.read(JSONObject().put("PostTime", raw))!!
            assertEquals(epoch, time.epochMillis)
            assertEquals("2026-10-03 13:06:01", time.text)
        }
    }
    @Test fun renderingDoesNotDependOnDeviceTimezone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(epoch, RutenListingTime.read(JSONObject().put("post_time", "2026/10/03 13:06:01"))!!.epochMillis)
            assertEquals("2026-10-03 13:06:01", RutenListingTime.read(JSONObject().put("PostTime", epoch))!!.text)
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun invalidPrimaryFieldDoesNotHideAValidCreationField() {
        for (invalid in listOf("null", "", "0", "not a date", "2026/02/30 13:06:01", Long.MAX_VALUE)) {
            assertEquals(epoch, RutenListingTime.read(JSONObject().put("PostTime", invalid).put("post_time", epoch / 1000))!!.epochMillis)
        }
    }
    @Test fun missingOrInvalidDatesDoNotInventAListingTime() {
        assertNull(RutenListingTime.read(JSONObject().put("CloseTime", epoch).put("UpdateTime", epoch)
            .put("PreOrderShipDate", "2026/10/03 13:06:01").put("checkedAt", epoch)))
        for (raw in listOf("1 小時前", "2026/13/03 13:06:01", "1970-01-01", Long.MAX_VALUE))
            assertNull(RutenListingTime.read(JSONObject().put("PostTime", raw)))
    }
}

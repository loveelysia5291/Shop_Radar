package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RefreshPolicyTest {
    @Test fun failedReadRetriesButOfficialAccessAndHttpErrorsDoNotLoop() {
        assertTrue(RefreshSchedulePolicy.retryRead("背景刷新逾時；保留原有資料"))
        assertTrue(RefreshSchedulePolicy.retryRead("未取得可讀商品"))
        listOf(null, "HTTP 404", "需要登入", "官方驗證", "SSL 錯誤", "安全連線失敗").forEach {
            assertFalse(RefreshSchedulePolicy.retryRead(it))
        }
    }
    @Test fun missingOrStaleBackgroundWorkerCannotBeKeptAsTheCurrentSchedule() {
        assertTrue(RefreshSchedulePolicy.needsReplacement(2, emptyList()))
        assertTrue(RefreshSchedulePolicy.needsReplacement(2, listOf(setOf("radar-automatic-refresh"), setOf(RefreshSchedulePolicy.tag(1)))))
        assertFalse(RefreshSchedulePolicy.needsReplacement(2, listOf(setOf(RefreshSchedulePolicy.tag(1)), setOf(RefreshSchedulePolicy.tag(2)))))
    }
    @Test fun retriesAreExactlyThirtySecondsApartAndStopAfterThree() {
        var plan = RefreshPlan(15, 1_000_000)
        repeat(3) { retry ->
            val attemptedAt = plan.dueAt
            plan = plan.disconnected(attemptedAt)
            assertEquals(retry + 1, plan.retry)
            assertEquals(attemptedAt + 30_000, plan.dueAt)
            assertEquals(1_000_000L, plan.cycleAt)
        }
        plan = plan.disconnected(plan.dueAt)
        assertEquals(0, plan.retry)
        assertEquals(1_900_000L, plan.dueAt)
        assertEquals(plan.cycleAt, plan.dueAt)
    }
    @Test fun nextIntervalResetsTheRetryBudget() {
        val exhausted = RefreshPlan(15, 0, 90_000, 3).disconnected(90_000)
        assertEquals(900_000L, exhausted.cycleAt)
        assertEquals(1, exhausted.disconnected(900_000).retry)
    }
    @Test fun successAfterARetryKeepsTheOriginalIntervalAnchor() {
        assertEquals(900_000L, RefreshPlan(15, 0, 30_000, 1).nextCycle(40_000).cycleAt)
    }
    @Test fun delayedWorkNeverCatchesUpWithManyImmediateRequests() {
        val plan = RefreshPlan(15, 0).nextCycle(2_700_100)
        assertEquals(3_600_000L, plan.dueAt)
        assertEquals(0, plan.retry)
    }
    @Test fun retriesDoNotCrossIntoTheNextScheduledInterval() {
        val plan = RefreshPlan(15, 0).disconnected(880_000)
        assertEquals(900_000L, plan.dueAt)
        assertEquals(0, plan.retry)
    }
    @Test fun exactBoundaryMovesToTheFollowingInterval() {
        assertEquals(1_800_000L, RefreshPlan(15, 0).nextCycle(900_000).dueAt)
    }
    @Test fun revisionSurvivesRetryAndNextCycle() {
        val plan = RefreshPlan(30, 0, revision = "configured-1")
        assertEquals("configured-1", plan.disconnected(0).revision)
        assertEquals("configured-1", plan.nextCycle(0).revision)
    }
    @Test fun intervalAndRetryLimitsAreValidated() {
        for (minutes in listOf(-1, 0, 14, 10081)) assertThrows(IllegalArgumentException::class.java) { RefreshPlan(minutes, 0) }
        for (retry in listOf(-1, 4)) assertThrows(IllegalArgumentException::class.java) { RefreshPlan(15, 0, retry = retry) }
        assertEquals(604_800_000L, RefreshPlan(10080, 0).intervalMillis)
    }
    @Test fun targetKeysSeparateStoreProductAndAuctionEvenAtTheSameUrl() {
        assertNotEquals(RefreshTarget(RefreshKind.SHOP, "https://fixture" ).key,
            RefreshTarget(RefreshKind.PRODUCT, "https://fixture").key)
        assertNotEquals(RefreshTarget(RefreshKind.PRODUCT, "https://fixture").key,
            RefreshTarget(RefreshKind.AUCTION, "https://fixture").key)
    }
    @Test fun disabledSleepNeverBlocksAnyMinute() {
        for (minute in 0..1439) assertFalse(RefreshSleep().coversMinute(minute))
    }
    @Test fun overnightSleepIncludesStartButNotEnd() {
        val sleep = RefreshSleep(true, 23 * 60, 7 * 60)
        assertTrue(sleep.coversMinute(23 * 60)); assertTrue(sleep.coversMinute(0))
        assertTrue(sleep.coversMinute(6 * 60 + 59)); assertFalse(sleep.coversMinute(7 * 60))
        assertFalse(sleep.coversMinute(22 * 60 + 59))
    }
    @Test fun daytimeSleepOnlyBlocksInsideTheWindow() {
        val sleep = RefreshSleep(true, 12 * 60, 14 * 60)
        assertFalse(sleep.coversMinute(719)); assertTrue(sleep.coversMinute(720))
        assertTrue(sleep.coversMinute(839)); assertFalse(sleep.coversMinute(840))
        assertFalse(sleep.coversMinute(0))
    }
    @Test fun malformedEqualWindowFailsClosed() {
        for (minute in 0..1439) assertTrue(RefreshSleep(true, 600, 600).coversMinute(minute))
    }
    @Test fun overnightWakeAfterStartIsOnTheNextDay() = inTaipei {
        val sleep = RefreshSleep(true)
        assertEquals(at(5, 7, 0), sleep.nextAwake(at(4, 23, 30)))
    }
    @Test fun overnightWakeBeforeEndIsOnTheSameDay() = inTaipei {
        assertEquals(at(5, 7, 0), RefreshSleep(true).nextAwake(at(5, 2, 15)))
    }
    @Test fun daytimeWakeIsAtTheSameDaysEnd() = inTaipei {
        assertEquals(at(5, 14, 0), RefreshSleep(true, 720, 840).nextAwake(at(5, 13, 30)))
    }
    @Test fun awakeTimesAreNotDelayed() = inTaipei {
        val now = at(5, 10, 15)
        assertEquals(now, RefreshSleep(true).nextAwake(now))
    }
    @Test fun sleepEndHasZeroSecondsAndMillis() = inTaipei {
        val end = RefreshSleep(true).nextAwake(at(5, 6, 59) + 42_456)
        val calendar = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(0, calendar.get(Calendar.SECOND)); assertEquals(0, calendar.get(Calendar.MILLISECOND))
    }
    @Test fun sleepSkipsRatherThanMakingUpTheMissedInterval() = inTaipei {
        val due = at(5, 6, 30)
        val sleep = RefreshSleep(true)
        val woke = sleep.nextAwake(due)
        assertTrue(sleep.contains(due)); assertFalse(sleep.contains(woke))
        assertEquals(at(5, 7, 15), RefreshPlan(15, due).nextCycle(woke).dueAt)
    }
    @Test fun activeReadHasADeadlineAtSleepStart() = inTaipei {
        assertEquals(at(5, 23, 0), RefreshSleep(true).nextSleep(at(5, 22, 59)))
        assertEquals(at(6, 12, 0), RefreshSleep(true, 720, 840).nextSleep(at(5, 15, 0)))
    }
    @Test fun alreadySleepingMustNotStartARead() = inTaipei {
        val now = at(5, 23, 30)
        assertEquals(now, RefreshSleep(true).nextSleep(now))
        assertNull(RefreshSleep(false).nextSleep(now))
    }
    private fun at(day: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        clear(); set(2026, Calendar.OCTOBER, day, hour, minute)
    }.timeInMillis
    private fun inTaipei(block: () -> Unit) {
        val original = TimeZone.getDefault()
        try { TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei")); block() }
        finally { TimeZone.setDefault(original) }
    }
}

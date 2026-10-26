package com.example.shopradar

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackgroundAlertJournalTest {
    private fun alert(title: String = "新手機") = ProductSubscriptionAlert("測試店家", title, "新品")
    @Test fun checkpointAndPendingAlertsSurviveRestartTogether() {
        val saved = JSONObject().put("checkedAt", 100).put("listingCheckpointAt", 200)
        BackgroundAlertJournal.append(saved, listOf(alert()))
        val restored = JSONObject(saved.toString())
        assertEquals(200L, restored.getLong("listingCheckpointAt"))
        assertEquals(listOf(alert()), BackgroundAlertJournal.alerts(restored))
        // A failed/blocked notify leaves the journal untouched for the next worker.
        BackgroundAlertJournal.append(restored, listOf(alert()))
        assertEquals(1, BackgroundAlertJournal.rows(restored).size)
    }
    @Test fun onlySuccessfullyDeliveredIdsAreRemovedWithoutDeletingNewerUpdates() {
        val saved = JSONObject()
        BackgroundAlertJournal.append(saved, listOf(alert()))
        val delivered = BackgroundAlertJournal.rows(saved).map { it.getString("id") }.toSet()
        BackgroundAlertJournal.append(saved, listOf(alert("新平板")))
        BackgroundAlertJournal.clear(saved, delivered)
        assertEquals(listOf("新平板"), BackgroundAlertJournal.alerts(saved).map { it.title })
        BackgroundAlertJournal.clear(saved, BackgroundAlertJournal.rows(saved).map { it.getString("id") }.toSet())
        assertTrue(BackgroundAlertJournal.rows(saved).isEmpty())
        assertFalse(saved.has("pendingBackgroundAlerts"))
    }
    @Test fun adultOrMarkedPrivateListingsNeverAppearInSystemAlerts() {
        val saved = JSONObject()
        BackgroundAlertJournal.append(saved, listOf(alert(), alert("情趣內衣"), alert("商品").copy(isAdult = true)))
        assertEquals(listOf(alert()), BackgroundAlertJournal.alerts(saved))
    }
    @Test fun backlogIsBounded() {
        val saved = JSONObject()
        BackgroundAlertJournal.append(saved, (1..100).map { alert("手機$it") })
        assertEquals(64, BackgroundAlertJournal.rows(saved).size)
    }
    @Test fun failedAttemptRecordsATimeButDoesNotPretendTheBaselineWasRefreshed() {
        val saved = JSONObject().put("checkedAt", 100).put("listingCheckpointAt", 80)
        RefreshAttemptRecord.begin(saved, "one", 200)
        assertTrue(RefreshAttemptRecord.finish(saved, "one", RefreshResult(error = "未讀到商品"), 250))
        assertEquals(100L, saved.getLong("checkedAt"))
        assertEquals(80L, saved.getLong("listingCheckpointAt"))
        assertEquals(200L, saved.getLong("lastRefreshAttemptAt"))
        assertEquals("failed", saved.getString("lastRefreshAttemptState"))
        assertEquals("未讀到商品", saved.getString("lastRefreshError"))
    }
    @Test fun olderAttemptCannotOverwriteANewerAttemptAndSuccessClearsFailure() {
        val saved = JSONObject().put("lastRefreshError", "舊錯誤")
        RefreshAttemptRecord.begin(saved, "one", 200)
        RefreshAttemptRecord.begin(saved, "two", 300)
        assertFalse(RefreshAttemptRecord.finish(saved, "one", RefreshResult(error = "遲來的錯誤"), 400))
        assertTrue(RefreshAttemptRecord.finish(saved, "two", RefreshResult(), 500))
        assertFalse(saved.has("lastRefreshError"))
        assertEquals("complete", saved.getString("lastRefreshAttemptState"))
    }
}

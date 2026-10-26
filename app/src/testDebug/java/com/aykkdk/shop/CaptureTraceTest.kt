package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class CaptureTraceTest {
    @Test fun streamingSearchDoesNotObscureResultsWithADebugBrowser() {
        val request = SearchCapture(Market.SHOPEE, "https://shopee.tw/search?keyword=phone", 1, quickSearch = true)
        assertFalse(CaptureDebugPolicy.visibleReader(request))
        assertFalse(CaptureDebugPolicy.visibleReader(null))
        assertFalse(CaptureDebugPolicy.visibleReader(request.copy(market = Market.RUTEN)))
        assertFalse(CaptureDebugPolicy.visibleReader(request.copy(quickSearch = false)))
        assertFalse(CaptureDebugPolicy.visibleReader(request.copy(scanSubscription = true)))
    }
    @Test fun terminalShowsWhereExecutionStoppedWithTimes() {
        var now = 0L
        val trace = CaptureTrace { now }
        trace.step(CaptureStep.LOADING)
        now = 1000; trace.step(CaptureStep.EXTRACTING)
        now = 20000; trace.step(CaptureStep.TIMEOUT)
        val report = trace.report()
        assertTrue(report.contains("1000 ms：執行商品擷取腳本／等待回應"))
        assertTrue(report.contains("結束前步驟：執行商品擷取腳本／等待回應"))
        assertTrue(report.contains("20000 ms：到達搜尋等待上限"))
    }
    @Test fun boundedTraceRetainsImportantInterventionAfterPolling() {
        var now = 0L
        val trace = CaptureTrace { now }
        trace.step(CaptureStep.LOADING)
        trace.step(CaptureStep.EVALUATION_FAILED)
        repeat(100) { now++; trace.step(if (it % 2 == 0) CaptureStep.EXTRACTING else CaptureStep.WAITING_PRODUCTS) }
        trace.step(CaptureStep.STOPPED)
        val report = trace.report()
        assertEquals(40, report.lineSequence().count { it.contains(" ms：") })
        assertTrue(report.contains("0 ms：開啟搜尋網址"))
        assertTrue(report.contains("最近關鍵操作：WebView 無法執行擷取腳本"))
        assertTrue(report.contains("使用者結束診斷"))
    }
    @Test fun duplicateStepsDoNotFloodTrace() {
        val trace = CaptureTrace { 10L }
        repeat(50) { trace.step(CaptureStep.WAITING_PRODUCTS) }
        assertEquals(1, trace.report().lineSequence().count { it.contains(" ms：") })
    }
    @Test fun officialAccessRequirementIsTerminalAndNotATimeout() {
        val trace = CaptureTrace { 0L }
        trace.step(CaptureStep.WAITING_PRODUCTS)
        trace.step(CaptureStep.AUTH_REQUIRED)
        trace.step(CaptureStep.PAGE_FINISHED)
        trace.step(CaptureStep.TIMEOUT)
        assertEquals(CaptureStep.AUTH_REQUIRED, trace.current)
        assertFalse(trace.report().contains("到達搜尋等待上限"))
        assertTrue(trace.report().contains("結束前步驟：等待可讀取的商品卡片"))
    }
    @Test fun latePageCallbacksCannotReplaceTerminalReason() {
        val trace = CaptureTrace { 0L }
        trace.step(CaptureStep.EXTRACTING)
        trace.step(CaptureStep.TIMEOUT)
        trace.step(CaptureStep.PAGE_FINISHED)
        trace.step(CaptureStep.PARSED)
        assertEquals(CaptureStep.TIMEOUT, trace.current)
        assertTrue(trace.report().contains("結束前步驟：執行商品擷取腳本／等待回應"))
    }
    @Test fun liveDisplayContainsCountersButNotPageFields() {
        val diagnostics = CaptureDiagnostics("蝦皮", "https://shopee.tw/search?keyword=secret", "test")
        diagnostics.polls = 5
        diagnostics.acceptSnapshot(org.json.JSONObject().put("readyState", "complete").put("shopeeProductLinks", 2)
            .put("password", "secretPassword").put("cookie", "secretCookie"))
        diagnostics.step(CaptureStep.WAITING_PRODUCTS)
        assertTrue(diagnostics.liveText.contains("擷取：5 次"))
        assertTrue(diagnostics.liveText.contains("商品候選：2"))
        assertFalse(diagnostics.liveText.contains("secret"))
    }
}

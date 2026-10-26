package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class ReleaseCaptureDiagnosticsTest {
    @Test fun debugHtmlBudgetsDoNotChangeReleaseSearch() {
        assertEquals(5000L, CaptureDebugPolicy.searchMillis("蝦皮", 5000))
        assertEquals(4800L, CaptureDiagnostics("蝦皮", "https://shopee.tw/search", "test").readerMillis(4800))
    }
    @Test fun releaseDoesNotRetainDiagnosticCountersOrProduceReports() {
        val diagnostics = CaptureDiagnostics("蝦皮", "https://shopee.tw/search", "test")
        diagnostics.polls = 10
        diagnostics.event("pageFinished", "https://shopee.tw/search?keyword=private")
        diagnostics.step(CaptureStep.AUTH_REQUIRED)
        diagnostics.consoleError("TypeError: private-account", "private-UA Chrome/138.0")
        assertEquals(0, diagnostics.javascriptErrors)
        assertEquals("", diagnostics.liveText)
        assertEquals(0, diagnostics.polls)
        assertEquals("", diagnostics.report(20000, "https://shopee.tw/search", "loading", 0, 0))
    }
    @Test fun releaseNeverShowsSearchReader() {
        assertFalse(CaptureDebugPolicy.visibleReader(SearchCapture(Market.SHOPEE,
            "https://shopee.tw/search", 1, quickSearch = true)))
        assertFalse(CaptureDebugPolicy.visibleReader(null))
    }
}

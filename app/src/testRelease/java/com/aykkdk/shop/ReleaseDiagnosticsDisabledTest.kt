package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class ReleaseDiagnosticsDisabledTest {
    @Test fun noReportsCountersOrEventsAreRetained() {
        val diagnostics = CaptureDiagnostics("Facebook", "https://www.facebook.com/private?token=secret", "1.27")
        diagnostics.event("HTTP 400", "https://www.facebook.com/private")
        diagnostics.polls++; diagnostics.scriptCount = 33; diagnostics.readyState = "complete"
        assertEquals(0, diagnostics.polls)
        assertEquals(0, diagnostics.scriptCount)
        assertEquals("", diagnostics.readyState)
        assertEquals("", diagnostics.report(4000, "secret-url", "loading", 0, 0))
        assertEquals(4800L, diagnostics.readerMillis(4800))
    }
    @Test fun releaseNeverExtendsTheSearchForHtmlCollection() {
        assertEquals(5000L, CaptureDebugPolicy.searchMillis("Facebook", 5000))
        assertEquals(5000L, CaptureDebugPolicy.searchMillis("全部平台", 5000))
    }
}

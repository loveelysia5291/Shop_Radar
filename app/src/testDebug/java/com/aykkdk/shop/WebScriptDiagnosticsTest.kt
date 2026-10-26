package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class WebScriptDiagnosticsTest {
    @Test fun errorsAreFixedCategoriesNotRawMessages() {
        val diagnostics = CaptureDiagnostics("蝦皮", "https://shopee.tw/search", "test")
        diagnostics.consoleError("SyntaxError: unexpected token private-token", "private-account Chrome/138.0.0.0")
        diagnostics.consoleError("TypeError: private-account is not a function", "private-account Chrome/138.0.0.0")
        val report = diagnostics.report(9000, "https://shopee.tw/search", "loading", 0, 0)
        assertTrue(report.contains("語法錯誤 1；型別錯誤 1"))
        assertTrue(report.contains("UA 引擎主版本：138"))
        assertFalse(report.contains("private-token"))
        assertFalse(report.contains("private-account"))
        assertEquals(2, diagnostics.javascriptErrors)
    }
    @Test fun classifiesScriptNetworkSecurityAndReferenceErrors() {
        assertEquals("未定義參照", WebScriptDiagnostics.category("ReferenceError: x is not defined"))
        assertEquals("腳本分段載入失敗", WebScriptDiagnostics.category("ChunkLoadError: Loading chunk failed"))
        assertEquals("網路載入失敗", WebScriptDiagnostics.category("Failed to fetch"))
        assertEquals("安全限制", WebScriptDiagnostics.category("Content Security Policy"))
        assertEquals("其他錯誤", WebScriptDiagnostics.category("unknown private message"))
    }
    @Test fun providerVersionIsNotAnEngineVersion() {
        assertNull(WebScriptDiagnostics.engineMajor("WebView 15.0.3.333"))
        assertNull(WebScriptDiagnostics.engineMajor("Chrome/0.0"))
        assertEquals(153, WebScriptDiagnostics.engineMajor("Mozilla Chrome/153.0.8010.36"))
    }
    @Test fun closedDiagnosticsIgnoreFurtherErrors() {
        val diagnostics = CaptureDiagnostics("蝦皮", "https://shopee.tw/search", "test")
        diagnostics.close()
        diagnostics.consoleError("SyntaxError: private", "Chrome/138.0")
        assertEquals(0, diagnostics.javascriptErrors)
    }
}

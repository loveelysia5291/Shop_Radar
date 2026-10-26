package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class CaptureDiagnosticsTest {
    @Test fun removesCredentialsTokensFragmentsAndPrivatePaths() {
        val value = CaptureDiagnostics.safeUrl("https://username:password@shopee.tw/checkpoint/secret?token=private#cookie")
        assertEquals("https://shopee.tw/checkpoint/[隱藏]?[參數已隱藏]", value)
        listOf("username", "password", "secret", "private", "cookie").forEach { assertFalse(value.contains(it)) }
    }
    @Test fun retainsPublicSearchRoute() {
        assertEquals("https://shopee.tw/search?[參數已隱藏]",
            CaptureDiagnostics.safeUrl("https://shopee.tw/search?keyword=computer"))
        assertEquals("unknown", CaptureDiagnostics.safeUrl("not a url"))
    }
    @Test fun unmeasuredShopeeFieldsAreNotReportedAsZero() {
        val report = CaptureDiagnostics("蝦皮", "https://shopee.tw/search", "test")
            .report(4800, "https://shopee.tw/search", "loading", 0, 0)
        assertTrue(report.contains("商品候選數（連結／卡片）：未量測"))
        assertTrue(report.contains("頁面腳本數：未量測；圖片數：未量測"))
    }
    @Test fun shopeeSnapshotsPopulateRealPageCountersAndState() {
        val diagnostics = CaptureDiagnostics("蝦皮", "https://shopee.tw/search", "test")
        diagnostics.acceptSnapshot(JSONObject().put("readyState", "complete").put("scriptCount", 12)
            .put("imageCount", 7).put("shopeeProductLinks", 3).put("captureState", "product_links_present")
            .put("visibility", "visible").put("viewport", JSONObject().put("width", 392).put("height", 816)))
        val report = diagnostics.report(20000, "https://shopee.tw/search", "loading", 0, 0)
        assertTrue(report.contains("頁面狀態：complete；擷取狀態：product_links_present"))
        assertTrue(report.contains("商品候選數（連結／卡片）：3"))
        assertTrue(report.contains("頁面腳本數：12；圖片數：7"))
        assertTrue(report.contains("visible；視窗大小：392 × 816"))
        assertTrue(report.contains("結構化商品數：未量測"))
    }
    @Test fun cardFieldCountersDoNotIncludeProductOrAccountContent() {
        val diagnostics = CaptureDiagnostics("蝦皮", "https://shopee.tw/search", "test")
        diagnostics.acceptSnapshot(JSONObject().put("shopeeProductLinks", 6).put("captureState", "card_fields_missing")
            .put("publicCardStats", JSONObject().put("candidates", 3).put("parsed", 0).put("missingTitle", 1)
                .put("missingPrice", 2).put("unwanted", "private-fixture")))
        val report = diagnostics.report(20000, "https://shopee.tw/search", "loading", 0, 0)
        assertTrue(report.contains("已渲染商品候選：3；本次解析：0；缺少名稱：1；缺少價格：2"))
        assertFalse(report.contains("private-fixture"))
    }
}

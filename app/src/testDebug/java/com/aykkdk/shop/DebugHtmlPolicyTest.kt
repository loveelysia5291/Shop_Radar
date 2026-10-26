package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class DebugHtmlPolicyTest {
    @Test fun diagnosticsDoNotExtendTheForegroundSearchDeadline() {
        for (platform in listOf("Facebook", "蝦皮", "全部平台", "露天")) {
            assertEquals(15000L, CaptureDebugPolicy.searchMillis(platform, SearchWaitPolicy.DEADLINE_MILLIS))
        }
    }
    @Test fun exportIdentifiersCannotReferenceOtherFiles() {
        val id = "21400597-4a7b-4480-a55d-0606a4c9ad15"
        assertEquals(id, MarketplaceHtmlArchives.sessionId("HTML 診斷編號：$id"))
        assertNull(MarketplaceHtmlArchives.sessionId("HTML 診斷編號：../../private"))
        assertNull(MarketplaceHtmlArchives.sessionId("HTML 診斷編號：------------------------------------"))
    }
    @Test fun acceptsOnlyPublicSearchPagesForTheRequestedPlatform() {
        for (host in listOf("shopee.tw", "www.shopee.tw", "m.shopee.tw")) {
            assertTrue(DebugHtmlPagePolicy.accepts("蝦皮", "https://$host/search?keyword=phone"))
            assertTrue(DebugHtmlPagePolicy.accepts("蝦皮", "https://$host/search/"))
            assertFalse(DebugHtmlPagePolicy.accepts("蝦皮", "https://$host/buyer/login"))
            assertFalse(DebugHtmlPagePolicy.accepts("蝦皮", "https://$host/verify/captcha"))
        }
        assertTrue(DebugHtmlPagePolicy.accepts("Facebook", "https://www.facebook.com/marketplace/114987875182625/search?radius=250"))
        assertFalse(DebugHtmlPagePolicy.accepts("Facebook", "https://www.facebook.com/login/"))
        assertFalse(DebugHtmlPagePolicy.accepts("蝦皮", "https://www.facebook.com/marketplace/search"))
        assertFalse(DebugHtmlPagePolicy.accepts("露天", "https://www.ruten.com.tw/search/phone"))
    }
    @Test fun rejectsSpoofedHostsCredentialsAndPorts() {
        for (url in listOf("http://shopee.tw/search", "https://shopee.tw.evil.test/search",
            "https://user:secret@shopee.tw/search", "https://shopee.tw:444/search", "not a url"))
            assertFalse(DebugHtmlPagePolicy.accepts("蝦皮", url))
    }
    @Test fun exportFileNamesUseOnlyWhitelistedPlatformNames() {
        assertEquals("shopee", MarketplaceHtmlArchives.platformSlug("HTML 診斷平台：shopee\n"))
        assertEquals("facebook", MarketplaceHtmlArchives.platformSlug("HTML 診斷平台：../../secret\n"))
        assertEquals("facebook", MarketplaceHtmlArchives.platformSlug("old Facebook report"))
    }
}

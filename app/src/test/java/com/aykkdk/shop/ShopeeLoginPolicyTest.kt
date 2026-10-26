package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class ShopeeLoginPolicyTest {
    @Test fun completedOfficialHomeAfterLoginIsSuccessfulWithoutReturningToSearch() {
        for (home in listOf("https://shopee.tw", "https://shopee.tw/", "https://www.shopee.tw/?source=login", "https://m.shopee.tw/"))
            assertTrue(home, ShopeeLoginPolicy.completedHome(home, home, accessStarted = true, failed = false))
    }
    @Test fun staleFailedAndNonOfficialHomeCallbacksCannotCompleteLogin() {
        val home = "https://shopee.tw/"
        assertFalse(ShopeeLoginPolicy.completedHome(home, home, false, false))
        assertFalse(ShopeeLoginPolicy.completedHome(home, home, true, true))
        for (other in listOf("https://shopee.tw/buyer/login", "https://shopee.tw/verify/traffic/error",
            "https://shopee.tw/search?keyword=phone", "https://account.shopee.tw/", "http://shopee.tw/",
            "https://shopee.tw.evil.test/", "https://user@shopee.tw/", "https://shopee.tw:8443/")) {
            assertFalse(other, ShopeeLoginPolicy.completedHome(home, other, true, false))
            assertFalse(other, ShopeeLoginPolicy.completedHome(other, home, true, false))
        }
    }
    @Test fun verificationDoesNotClaimTheUserHasLoggedOut() {
        assertEquals(ShopeeLoginPolicy.AccessRoute.VERIFICATION,
            ShopeeLoginPolicy.accessRoute("https://shopee.tw/verify/traffic/error?is_logged_in=false"))
        assertEquals(ShopeeLoginPolicy.AccessRoute.LOGIN, ShopeeLoginPolicy.accessRoute("https://shopee.tw/buyer/login"))
        assertTrue(ShopeeLoginPolicy.accessMessage(ShopeeLoginPolicy.AccessRoute.VERIFICATION).contains("不代表帳號已登出"))
        assertNull(ShopeeLoginPolicy.accessRoute("https://shopee.tw/search?next=/buyer/login"))
    }
    @Test fun recoveryPreservesTheExactOfficialUrlAndSearchWithoutRewritingTokens() {
        val search = "https://shopee.tw/search?keyword=phone&page=2"
        val url = "https://shopee.tw/verify/traffic/error?next=" + java.net.URLEncoder.encode(search, "UTF-8") +
            "&home_url=https%3A%2F%2Fshopee.tw&type=4"
        val recovery = ShopeeLoginPolicy.recovery(url, search)!!
        assertEquals(url, recovery.officialUrl)
        assertEquals(search, recovery.searchUrl)
        assertEquals(ShopeeLoginPolicy.AccessRoute.VERIFICATION, recovery.route)
        assertNotNull(ShopeeLoginPolicy.recovery("https://shopee.tw/buyer/login", search))
    }
    @Test fun recoveryRejectsOffsiteMalformedAndAmbiguousContinuations() {
        val search = "https://shopee.tw/search?keyword=phone"
        for (query in listOf("next=https%3A%2F%2Fevil.test", "n%65xt=https%3A%2F%2Fevil.test",
            "next=https%3A%2F%2Fshopee.tw%40evil.test", "next=https%3A%2F%2Fshopee.tw&next=https%3A%2F%2Fshopee.tw",
            "home_url=https%3A%2F%2Fevil.test", "next=%ZZ", "next=%2Fsearch"))
            assertNull(query, ShopeeLoginPolicy.recovery("https://shopee.tw/buyer/login?$query", search))
        for (url in listOf("https://evil.test/login", "http://shopee.tw/login", "https://shopee.tw/search", "%%%"))
            assertNull(ShopeeLoginPolicy.recovery(url, search))
        assertNull(ShopeeLoginPolicy.recovery("https://shopee.tw/login", "https://evil.test/search"))
        assertNull(ShopeeLoginPolicy.recovery("https://shopee.tw/login?x=" + "a".repeat(16_384), search))
    }
    @Test fun officialLoginAndVerificationRoutesAreRecognizedButNotBlocked() {
        for (path in listOf("/buyer/login", "/login", "/verify/traffic/error", "/captcha", "/signup"))
            assertTrue(ShopeeLoginPolicy.requiresAuthentication("https://shopee.tw$path"))
        assertFalse(ShopeeLoginPolicy.requiresAuthentication("https://shopee.tw/search?keyword=login"))
    }
    @Test fun onlyHttpsOfficialHostsAreLoginPages() {
        assertTrue(ShopeeLoginPolicy.officialPage("https://shopee.tw/buyer/login"))
        assertTrue(ShopeeLoginPolicy.officialPage("https://m.shopee.tw/buyer/login"))
        assertTrue(ShopeeLoginPolicy.officialPage("https://account.shopee.tw/login"))
        for (url in listOf("http://shopee.tw/login", "https://shopee.tw.evil.test/login", "https://evilshopee.tw/login",
            "https://shopee.tw@evil.test/login", "https://user@shopee.tw/login", "https://shopee.tw:8443/login", "%%%")) {
            assertFalse(ShopeeLoginPolicy.officialPage(url))
            assertFalse(ShopeeLoginPolicy.requiresAuthentication(url))
        }
    }
    @Test fun anonymousOrEmptyCookiesCannotStartSearch() {
        for (header in listOf(null, "", "csrftoken=fixture", "SPC_U=-1; SPC_EC=fixture", "SPC_U=0; SPC_ST=fixture", "SPC_U=42"))
            assertFalse(ShopeeLoginPolicy.sessionCookiesPresent(header))
    }
    @Test fun savedUserAndSessionCookieAllowServerValidatedSearch() {
        assertTrue(ShopeeLoginPolicy.sessionCookiesPresent("SPC_U=42; SPC_EC=local-fixture; other=unused"))
        assertTrue(ShopeeLoginPolicy.sessionCookiesPresent(" SPC_U =42; SPC_ST=local-fixture"))
        assertTrue(ShopeeLoginPolicy.sessionCookiesPresent("SPC_U=\"42\"; SPC_ST=\"local-fixture\""))
        assertTrue(ShopeeLoginPolicy.sessionCookiesPresent("SPC_U=42; SPC_ST=abc=def"))
    }
    @Test fun invalidAndAmbiguousSessionsAreNotAccepted() {
        for (header in listOf("SPC_U=42; SPC_EC=", "SPC_U=42; SPC_EC=undefined", "SPC_U=42; SPC_EC=null",
            "SPC_U=42; SPC_ST=-1", "SPC_U=42; SPC_U=-1; SPC_EC=fixture", "SPC_U=overflow999999999999999999999; SPC_ST=fixture"))
            assertFalse(ShopeeLoginPolicy.sessionCookiesPresent(header))
    }
    @Test fun logoutExpiresOnlyKnownShopeeSessionCookies() {
        val plan = ShopeeLoginPolicy.logoutCookies()
        assertEquals(24, plan.size)
        for ((origin, cookie) in plan) {
            assertTrue(ShopeeLoginPolicy.officialPage(origin))
            assertTrue(cookie.substringBefore('=') in setOf("SPC_U", "SPC_EC", "SPC_ST"))
            assertTrue(cookie.contains("=; Max-Age=0;"))
            assertTrue(cookie.contains("Path=/"))
            assertTrue(cookie.contains("Secure; HttpOnly"))
            assertFalse(cookie.contains("csrftoken"))
        }
        assertTrue(plan.any { !it.second.contains("Domain=") })
        assertTrue(plan.any { it.second.contains("Domain=shopee.tw") })
    }
}

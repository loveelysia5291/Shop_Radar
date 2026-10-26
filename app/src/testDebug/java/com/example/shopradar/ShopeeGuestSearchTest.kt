package com.example.shopradar

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ShopeeGuestSearchTest {
    private val search = SearchCapture(Market.SHOPEE, "https://shopee.tw/search?keyword=phone", 1, quickSearch = true)

    @After fun reset() { ShopeeGuestSearch.enabled = false }

    @Test fun anonymousExperimentRequiresExplicitSessionOptIn() {
        ShopeeGuestSearch.enabled = false
        assertFalse(ShopeeGuestSearch.allows(search))
        ShopeeGuestSearch.enabled = true
        assertTrue(ShopeeGuestSearch.allows(search))
    }

    @Test fun neverChangesBackgroundSubscriptionProductOrLoginReaders() {
        ShopeeGuestSearch.enabled = true
        for (request in listOf(search.copy(backgroundRead = true), search.copy(scanSubscription = true),
            search.copy(quickSearch = false), search.copy(market = Market.FACEBOOK),
            search.copy(url = "https://shopee.tw/product/123/456"),
            search.copy(url = ShopeeLoginPolicy.LOGIN_URL),
            search.copy(url = "https://shopee.tw.evil.test/search"),
            search.copy(url = "https://user@shopee.tw/search"))) assertFalse(ShopeeGuestSearch.allows(request))
    }

    @Test fun interceptsAutomaticLoginWithProductsOrOneEmptyPageAttempt() {
        ShopeeGuestSearch.enabled = true
        assertTrue(ShopeeGuestSearch.blockLoginRedirect(search, ShopeeLoginPolicy.LOGIN_URL, true, false))
        assertTrue(ShopeeGuestSearch.blockLoginRedirect(search, ShopeeLoginPolicy.LOGIN_URL, false, false))
        assertFalse(ShopeeGuestSearch.blockLoginRedirect(search, ShopeeLoginPolicy.LOGIN_URL, false, false, emptyAttempted = true))
        assertTrue(ShopeeGuestSearch.blockLoginRedirect(search, ShopeeLoginPolicy.LOGIN_URL, true, false, emptyAttempted = true))
        assertFalse(ShopeeGuestSearch.blockLoginRedirect(search, ShopeeLoginPolicy.LOGIN_URL, true, true))
        for (destination in listOf("https://shopee.tw/verify/traffic/error", "https://shopee.tw/captcha",
            "https://shopee.tw/signup", "https://evil.test/buyer/login", "https://shopee.tw:8443/buyer/login"))
            assertFalse(ShopeeGuestSearch.blockLoginRedirect(search, destination, true, false))
    }
}

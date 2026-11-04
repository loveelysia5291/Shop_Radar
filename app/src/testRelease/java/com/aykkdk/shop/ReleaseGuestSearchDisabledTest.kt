package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class ReleaseGuestSearchDisabledTest {
    @Test fun releaseSupportsShopeeGuestSearch() {
        val request = SearchCapture(Market.SHOPEE, "https://shopee.tw/search?keyword=phone", 1, quickSearch = true)
        assertTrue(ShopeeGuestSearch.enabled)
        assertTrue(ShopeeGuestSearch.allows(request))
        assertTrue(ShopeeGuestSearch.blockLoginRedirect(request, ShopeeLoginPolicy.LOGIN_URL, true, false))
    }
}

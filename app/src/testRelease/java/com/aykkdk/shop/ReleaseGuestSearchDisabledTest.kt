package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class ReleaseGuestSearchDisabledTest {
    @Test fun releaseAlwaysRequiresNormalShopeeAuthentication() {
        val request = SearchCapture(Market.SHOPEE, "https://shopee.tw/search?keyword=phone", 1, quickSearch = true)
        assertFalse(ShopeeGuestSearch.enabled)
        assertFalse(ShopeeGuestSearch.allows(request))
        assertFalse(ShopeeGuestSearch.blockLoginRedirect(request, ShopeeLoginPolicy.LOGIN_URL, true, false))
    }
}

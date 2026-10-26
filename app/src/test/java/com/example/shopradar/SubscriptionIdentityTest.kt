package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class SubscriptionIdentityTest {
    @Test fun shopeeSlugsMobileHostsAndSharingParametersStillIdentifyTheSameItem() {
        assertTrue(SubscriptionIdentity.sameProduct("https://shopee.tw/product/123/456",
            "https://m.shopee.tw/手機-i.123.456/?utm_source=share"))
        assertFalse(SubscriptionIdentity.sameProduct("https://shopee.tw/product/123/456",
            "https://shopee.tw/product/124/456"))
    }

    @Test fun carousellTitleChangesDoNotMakeAnotherSubscription() {
        assertTrue(SubscriptionIdentity.sameProduct("https://tw.carousell.com/p/1466478450/",
            "https://tw.carousell.com/p/ipad-air-1466478450/?_branch_match_id=123"))
        assertFalse(SubscriptionIdentity.sameProduct("https://tw.carousell.com/p/1466478450/",
            "https://tw.carousell.com/p/1466478451/"))
    }

    @Test fun rutenQueryAndPathRoutesIdentifyOneItem() {
        assertTrue(SubscriptionIdentity.sameProduct("https://www.ruten.com.tw/item/show?12345678901234&foo=bar",
            "https://www.ruten.com.tw/item/12345678901234/?utm_source=copy"))
        assertFalse(SubscriptionIdentity.sameProduct("https://www.ruten.com.tw/item/12345678901234/",
            "https://www.ruten.com.tw/item/12345678901235/"))
    }

    @Test fun yahooAndFacebookIgnoreTrackingButNotItemIds() {
        for (base in listOf("https://tw.bid.yahoo.com/item/100527914215", "https://www.facebook.com/marketplace/item/1120292097099668")) {
            assertTrue(SubscriptionIdentity.sameProduct(base, "$base/?ref=search"))
            assertFalse(SubscriptionIdentity.sameProduct(base, base + "9"))
        }
        assertFalse(SubscriptionIdentity.sameProduct("https://tw.bid.yahoo.com/item/100527914215",
            "https://www.facebook.com/marketplace/item/100527914215"))
    }

    @Test fun allObservedStoreRoutesIgnoreSortPageAndShareParameters() {
        val pairs = listOf(
            "https://www.ruten.com.tw/store/Shop123/" to "https://www.ruten.com.tw/store/shop123/list?p=2&sort=new%2Fdc",
            "https://tw.bid.yahoo.com/booth/Y2369592298" to "https://tw.bid.yahoo.com/booth/Y2369592298/?pg=2&sort=-ptime",
            "https://shopee.tw/shop/12345" to "https://m.shopee.tw/shop/12345/?page=0&sortBy=ctime",
            "https://shopee.tw/phone_shop" to "https://shopee.tw/phone_shop/?utm_source=share",
            "https://tw.carousell.com/u/phone_shop/" to "https://tw.carousell.com/phone_shop/?sort_by=3",
            "https://www.facebook.com/marketplace/profile/123456789/" to "https://m.facebook.com/marketplace/profile/123456789/?ref=share"
        )
        pairs.forEach { (a,b) -> assertTrue("$a / $b", SubscriptionIdentity.sameShop(a,b)) }
    }

    @Test fun differentStoresAndPlatformsNeverMatch() {
        assertFalse(SubscriptionIdentity.sameShop("https://tw.bid.yahoo.com/booth/Y2369592298", "https://tw.bid.yahoo.com/booth/Y8500181312"))
        assertFalse(SubscriptionIdentity.sameShop("https://shopee.tw/shop/12345", "https://shopee.tw/shop/12346"))
        assertFalse(SubscriptionIdentity.sameShop("https://shopee.tw/phone_shop", "https://tw.carousell.com/phone_shop/"))
        assertFalse(SubscriptionIdentity.sameShop("https://www.facebook.com/profile.php?id=123", "https://www.facebook.com/profile.php?id=456"))
    }

    @Test fun unsafeOrProductUrlsCannotBeShopIdentities() {
        for (url in listOf("https://shopee.tw/product/123/456", "https://shopee.tw.evil.test/store123",
            "https://user:secret@shopee.tw/store123", "http://shopee.tw/store123", "not a URL")) {
            assertFalse(SubscriptionIdentity.sameShop(url,url))
        }
        assertFalse(SubscriptionIdentity.sameProduct("not a URL", "not a URL"))
    }
}

package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class SubscriptionPlatformTest {
    @Test fun canonicalUrlDeterminesPlatformEvenForAnOldIncorrectSelection() {
        assertEquals(Market.CAROUSELL, Market.fromSubscription("RUTEN", "https://tw.carousell.com/p/1464417552/"))
        assertEquals(Market.CAROUSELL, Market.fromSubscription("", "https://tw.carousell.com/u/store.name/"))
        assertEquals(Market.RUTEN, Market.fromSubscription("旋轉拍賣", "https://www.ruten.com.tw/store/tiger0719tw/list"))
    }

    @Test fun historicalRecordsSupportEnumNamesAndDisplayLabels() {
        assertEquals(Market.CAROUSELL, Market.fromSubscription("CAROUSELL", ""))
        assertEquals(Market.CAROUSELL, Market.fromSubscription("旋轉拍賣", ""))
        assertNull(Market.fromSubscription("unknown", "https://unknown.example/"))
    }
}

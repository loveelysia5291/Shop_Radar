package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class ListingTimeDisplayTest {
    private val now = 1_800_000_000_000L
    @Test fun shopeeAndYahooHideTheWholeDateFieldForOldAndCurrentPlatformValues() {
        for (platform in listOf("蝦皮", "SHOPEE", "Y拍", "YAHOO")) assertFalse(ListingTimeDisplay.visible(platform))
        for (market in listOf(Market.BBOBO, Market.IOPEN, Market.RUTEN, Market.CAROUSELL, Market.FACEBOOK))
            assertTrue(ListingTimeDisplay.visible(market.label))
    }

    @Test fun shopeeCreationTimestampUsesTheRequestedRelativeUnits() {
        for ((seconds, text) in listOf(
            36L to "36 秒前", 44L * 60 to "44 分鐘前", 3600L to "1 小時前",
            86400L to "1 天前", 604800L to "1 週前", 6L * 2592000 to "6 個月前",
            31536000L to "1 年前")) {
            assertEquals(text, ListingTimeDisplay.text(Market.SHOPEE.label, "已取得上架時間", now - seconds * 1000, now))
        }
    }

    @Test fun storedAbsoluteShopeeDatesAlsoGetRelativePresentation() {
        val at = 1_767_323_045_000L
        assertEquals("44 分鐘前", ListingTimeDisplay.text(Market.SHOPEE.label, "2026-01-02 11:04:05", now = at + 44 * 60000))
    }

    @Test fun missingDatesCannotBorrowCaptureTimeOrSellerActivity() {
        assertEquals("平台未公開／尚未取得", ListingTimeDisplay.text(Market.SHOPEE.label, "", now, now))
        assertEquals("1 小時前上線", ListingTimeDisplay.text(Market.SHOPEE.label, "1 小時前上線", now = now))
        assertEquals("6 個月前", ListingTimeDisplay.text(Market.SHOPEE.label, "6 個月前", now = now))
        assertEquals("2099-01-01", ListingTimeDisplay.text(Market.SHOPEE.label, "2099-01-01", now = now))
    }

    @Test fun facebookAndOtherPlatformsKeepTheirOriginalDateText() {
        assertEquals("1 週前", ListingTimeDisplay.text(Market.FACEBOOK.label, "1 週前", now - 604800000, now))
        assertEquals("2026/01/02", ListingTimeDisplay.text(Market.BBOBO.label, "2026/01/02", now = now))
    }

    @Test fun invalidEpochDoesNotCreateARelativeDate() {
        assertEquals("已取得時間", ListingTimeDisplay.text(Market.SHOPEE.label, "已取得時間", 12, now))
        assertEquals("已取得時間", ListingTimeDisplay.text(Market.SHOPEE.label, "已取得時間", now + 60001, now))
        assertEquals("0 秒前", ListingTimeDisplay.text(Market.SHOPEE.label, "已取得時間", now + 500, now))
    }
}

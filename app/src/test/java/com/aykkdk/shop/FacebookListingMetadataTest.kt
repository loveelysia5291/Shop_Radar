package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class FacebookListingMetadataTest {
    private fun product() = Product("電腦主機含電腦螢幕", 3000, Market.FACEBOOK.label,
        "https://www.facebook.com/marketplace/item/" + ids.incrementAndGet() + "/")
    companion object { private val ids = AtomicLong(9980000000000000) }

    @Test fun desktopLayoutKeepsTheInstalledBrowserVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 9; Pixel Build/ABC; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/138.0.7204.169 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.169 Safari/537.36",
            FacebookWebPolicy.desktopUserAgent(mobile))
        val desktop = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/154.0.8037.57 Safari/537.36"
        assertEquals(desktop, FacebookWebPolicy.desktopUserAgent(desktop))
    }
    @Test fun publicHrefIsCanonicalWithoutAResolver() {
        val p = product()
        assertEquals(p.url, FacebookListingMetadata.directUrl(p.copy(url = p.url + "?ref=search")))
        assertNull(FacebookListingMetadata.directUrl(p.copy(url = "https://www.facebook.com/login/")))
        assertNull(FacebookListingMetadata.directUrl(p.copy(platform = Market.CAROUSELL.label)))
    }
    @Test fun datedCardsDoNotNeedExtraPageRequests() {
        assertFalse(FacebookListingMetadata.needsRead(product().copy(listedAt = "約一小時前"), 1700000000000))
    }
    @Test fun iopenUsesTheBoundedMissingDateCache() {
        val now = 1700000000000L
        for ((market, url) in listOf(
            Market.IOPEN to "https://mall.iopenmall.tw/017737/index.php?action=product_detail&prod_no=P1773702460748")) {
            val p = Product("手機", 100, market.label, url)
            assertEquals(url, FacebookListingMetadata.directUrl(p))
            assertTrue(FacebookListingMetadata.needsRead(p, now))
            FacebookListingMetadata.complete(url, FacebookListingTime("2023-11-10", now - 86400000), now)
            assertEquals("2023-11-10", FacebookListingMetadata.apply(p, now).listedAt)
            assertFalse(FacebookListingMetadata.needsRead(p, now + 1000))
            assertNull(FacebookListingMetadata.directUrl(p.copy(platform = Market.YAHOO.label)))
        }
    }
    @Test fun successFillsOnlyMissingTimeAndPreservesTheTimestamp() {
        val p = product()
        val now = 1700000000000L
        val date = FacebookListingTime("約一小時前", now - 3600000)
        assertTrue(FacebookListingMetadata.needsRead(p, now))
        FacebookListingMetadata.complete(p.url, date, now)
        val filled = FacebookListingMetadata.apply(p, now + 10_000)
        assertEquals(date.text, filled.listedAt)
        assertEquals(date.epochMillis, filled.listedAtEpochMillis)
        assertFalse(FacebookListingMetadata.needsRead(p, now + 10_000))
        assertEquals("6天前", FacebookListingMetadata.apply(p.copy(listedAt = "6天前"), now).listedAt)
        assertTrue(FacebookListingMetadata.needsRead(p, now + 60 * 60_000))
    }
    @Test fun hiddenYahooAndShopeeDatesNeverScheduleVisibleCardReads() {
        val now = 1791000000000L
        for ((market, url) in listOf(Market.YAHOO to "https://tw.bid.yahoo.com/item/101234567893",
            Market.SHOPEE to "https://shopee.tw/product/123/87654321")) {
            val p = Product("手機", 100, market.label, url)
            assertNull(FacebookListingMetadata.directUrl(p))
            assertFalse(FacebookListingMetadata.needsRead(p, now))
            FacebookListingMetadata.complete(url, FacebookListingTime("2026-10-03", now), now)
            assertEquals(p, FacebookListingMetadata.apply(p, now))
        }
    }
    @Test fun unavailableTimeIsNotInventedOrRetriedOnEveryRecomposition() {
        val p = product()
        val now = 1700000000000L
        FacebookListingMetadata.complete(p.url, null, now)
        assertEquals("", FacebookListingMetadata.apply(p, now).listedAt)
        assertFalse(FacebookListingMetadata.needsRead(p, now + 30_000))
        assertTrue(FacebookListingMetadata.needsRead(p, now + 5 * 60_000))
    }
    @Test fun badLinksAndFutureDatesCannotSeedMetadata() {
        val p = product()
        val now = 1700000000000L
        FacebookListingMetadata.complete(p.url, FacebookListingTime("明天", now + 86400000), now)
        assertEquals("", FacebookListingMetadata.apply(p, now).listedAt)
        FacebookListingMetadata.complete("https://www.facebook.com.evil.test/marketplace/item/1234567890/",
            FacebookListingTime("6天前", now - 86400000), now)
        assertEquals("", FacebookListingMetadata.apply(p, now).listedAt)
        assertEquals(2, FacebookListingMetadata.MAX_READERS)
        assertEquals(8000L, FacebookListingMetadata.READ_MILLIS)
    }
}

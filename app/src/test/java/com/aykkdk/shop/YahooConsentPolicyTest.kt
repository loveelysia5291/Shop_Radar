package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class YahooConsentPolicyTest {
    private val original = "https://tw.bid.yahoo.com/booth/Y2369592298?sort=-ptime&pg=1"

    @Test fun officialConsentRouteIsRecognizedIncludingItsFlowAndParameters() {
        assertTrue(YahooConsentPolicy.isConsentUrl("https://guce.yahoo.com/consent?sessionId=example"))
        assertTrue(YahooConsentPolicy.isConsentUrl("https://GUCE.YAHOO.COM/collectConsent"))
        assertFalse(Market.YAHOO.accepts("https://guce.yahoo.com/consent"))
        assertTrue(YahooConsentPolicy.allowsVisiblePage("https://guce.yahoo.com/consent"))
        assertTrue(YahooConsentPolicy.allowsVisiblePage(original))
    }

    @Test fun consentAllowlistDoesNotAdmitLookalikesCredentialsPortsOrUnsafeSchemes() {
        listOf("http://guce.yahoo.com/consent", "https://guce.yahoo.com.evil.test/consent",
            "https://user@guce.yahoo.com/consent", "https://guce.yahoo.com:443/consent",
            "https://evil.test/?next=https://guce.yahoo.com/consent", "javascript:alert(1)",
            "https://www.yahoo.com/", "https://shopee.tw/search", "bad").forEach {
            assertFalse(it, YahooConsentPolicy.isConsentUrl(it))
            assertFalse(it, YahooConsentPolicy.allowsVisiblePage(it))
        }
    }

    @Test fun retryOnlyReturnsFromOriginalAuctionRouteNeverConsentOrAnotherStore() {
        assertTrue(YahooConsentPolicy.canRetryFrom(original, original))
        assertTrue(YahooConsentPolicy.canRetryFrom("https://tw.bid.yahoo.com/booth/Y2369592298/?ref=test", original))
        listOf("https://guce.yahoo.com/consent", "https://tw.bid.yahoo.com/booth/Y9999999999",
            "https://tw.bid.yahoo.com/", "https://tw.bid.yahoo.com/item/100527914215",
            "https://user@tw.bid.yahoo.com/booth/Y2369592298", "broken").forEach {
            assertFalse(it, YahooConsentPolicy.canRetryFrom(it, original))
        }
        assertFalse(YahooConsentPolicy.canRetryFrom(original, "https://evil.test/booth/Y2369592298"))
    }

    @Test fun keywordSearchFiltersRemainInAppSnapshotRatherThanTheConsentUrl() {
        val search = "https://tw.bid.yahoo.com/search/auction/product?p=phone&minp=1&maxp=1000&sort=-ptime&pg=2"
        assertTrue(YahooConsentPolicy.canRetryFrom(search, search))
        assertFalse(YahooConsentPolicy.canRetryFrom("https://guce.yahoo.com/consent?done=$search", search))
    }

    @Test fun automaticRedirectCanReturnToStoreWithoutRequestingConsent() {
        val wait = YahooRedirectWait()
        assertFalse(wait.expired("https://guce.yahoo.com/consent", 100))
        assertFalse(wait.expired("https://guce.yahoo.com/collectConsent", 900))
        assertFalse(wait.expired(original, 1200))
        assertFalse(wait.expired(original, 20_000))
    }

    @Test fun repeatedPageFinishedCallbacksDoNotRestartTheRedirectBudget() {
        val wait = YahooRedirectWait()
        val consent = "https://guce.yahoo.com/consent"
        assertFalse(wait.expired(consent, 100))
        for (now in listOf(600L, 1200L, 2400L, 5000L)) assertFalse(wait.expired(consent, now))
        assertTrue(wait.expired(consent, 5100))
        assertFalse(wait.expired(original, 5200))
        assertFalse(wait.expired(consent, 5300))
        assertTrue(wait.expired(consent, 10_300))
    }

    @Test fun productReaderAllowsOfficialIntermediateButNeverAnotherProductOrShop() {
        val product = "https://tw.bid.yahoo.com/item/100527914215"
        assertTrue(YahooConsentPolicy.allowsProductPage(product, product))
        assertTrue(YahooConsentPolicy.allowsProductPage("https://guce.yahoo.com/consent", product))
        for (url in listOf(original, "https://tw.bid.yahoo.com/item/100999999999", "https://guce.yahoo.com.evil.test/consent", "https://login.yahoo.com/"))
            assertFalse(url, YahooConsentPolicy.allowsProductPage(url, product))
        assertFalse(YahooConsentPolicy.allowsProductPage("https://guce.yahoo.com/consent", original))
    }
}

package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class ShopeeSearchRecoveryTest {
    private val search = "https://shopee.tw/search?keyword=phone&page=2&sortBy=price&order=asc&fe_filter_options=fixture"
    private val request = SearchCapture(Market.SHOPEE, search, 12, quickSearch = true)
    private val recovery = ShopeeLoginPolicy.recovery("https://shopee.tw/verify/traffic/error", search)!!

    @Test fun completionMatchesKeywordPageSortOrderAndAllFilters() {
        assertTrue(ShopeeLoginPolicy.canResumeSearch(search, search))
        assertTrue(ShopeeLoginPolicy.canResumeSearch("https://shopee.tw/search/?order=asc&sortBy=price&keyword=phone&fe_filter_options=fixture&page=2&tracking=unused", search))
        for (changed in listOf(search.replace("phone", "tablet"), search.replace("page=2", "page=0"),
            search.replace("price", "sales"), search.replace("asc", "desc"), search.replace("fixture", "changed")))
            assertFalse(ShopeeLoginPolicy.canResumeSearch(changed, search))
    }
    @Test fun successfulCurrentSearchAfterVerificationCompletesAutomatically() {
        assertTrue(ShopeeLoginPolicy.completedSearch(search, search, search, accessStarted = true, failed = false))
        val reordered = "https://shopee.tw/search/?order=asc&sortBy=price&keyword=phone&fe_filter_options=fixture&page=2"
        assertTrue(ShopeeLoginPolicy.completedSearch(reordered, search, search, true, false))
    }
    @Test fun initialFailedAndStaleSearchCannotCloseVerification() {
        assertFalse(ShopeeLoginPolicy.completedSearch(search, search, search, false, false))
        assertFalse(ShopeeLoginPolicy.completedSearch(search, search, search, true, true))
        for (other in listOf(recovery.officialUrl, ShopeeLoginPolicy.LOGIN_URL,
            search.replace("phone", "tablet"), "https://evil.test/search?keyword=phone")) {
            assertFalse(other, ShopeeLoginPolicy.completedSearch(search, other, search, true, false))
            assertFalse(other, ShopeeLoginPolicy.completedSearch(other, search, search, true, false))
        }
    }
    @Test fun equivalentDefaultFirstPageIsAccepted() {
        val default = "https://shopee.tw/search?keyword=%E6%89%8B%E6%A9%9F&page=0"
        assertTrue(ShopeeLoginPolicy.canResumeSearch("https://shopee.tw/search?keyword=手機&sortBy=relevancy", default))
        assertFalse(ShopeeLoginPolicy.canResumeSearch("https://shopee.tw/search?keyword=手機&order=asc", default))
    }
    @Test fun cannotResumeOnChallengeLoginErrorOrForeignPage() {
        for (url in listOf(recovery.officialUrl, ShopeeLoginPolicy.LOGIN_URL, "https://shopee.tw/",
            "https://evil.test/search?keyword=phone", "https://shopee.tw.evil.test/search?keyword=phone",
            "https://user@shopee.tw/search?keyword=phone", "http://shopee.tw/search?keyword=phone", "%%%"))
            assertFalse(ShopeeLoginPolicy.canResumeSearch(url, search))
    }
    @Test fun ambiguousQueriesAndEmptyKeywordsCannotResume() {
        assertFalse(ShopeeLoginPolicy.canResumeSearch("$search&page=2", search))
        assertFalse(ShopeeLoginPolicy.canResumeSearch("$search&keyword=phone", search))
        assertFalse(ShopeeLoginPolicy.canResumeSearch("$search&x=%ZZ", search))
        assertFalse(ShopeeLoginPolicy.canResumeSearch("https://shopee.tw/search", "https://shopee.tw/search"))
        assertFalse(ShopeeLoginPolicy.canResumeSearch("$search&x=" + "a".repeat(16_384), search))
    }
    @Test fun automaticUiIsForegroundKeywordSearchOnlyAndNeverLoops() {
        assertTrue(ShopeeLoginPolicy.shouldAutoRecover(request, true, recovery))
        assertFalse(ShopeeLoginPolicy.shouldAutoRecover(request, false, recovery))
        assertFalse(ShopeeLoginPolicy.shouldAutoRecover(request, true, null))
        for (other in listOf(request.copy(officialRecoveryAttempt = 1), request.copy(backgroundRead = true),
            request.copy(scanSubscription = true), request.copy(quickSearch = false), request.copy(market = Market.YAHOO),
            request.copy(url = "https://shopee.tw/search?keyword=other")))
            assertFalse(ShopeeLoginPolicy.shouldAutoRecover(other, true, recovery))
        assertTrue(ShopeeLoginPolicy.shouldAutoRecover(request.copy(retryAttempt = 1), true, recovery))
    }
}

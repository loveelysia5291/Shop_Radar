package com.aykkdk.shop

/** Monotonic elapsed time belongs to the user action, never to a retry. */
internal object SearchWaitPolicy {
    const val SLOW_MILLIS = 5_000L
    const val RETRY_MILLIS = 10_000L
    const val DEADLINE_MILLIS = 15_000L
    const val SLOW_MESSAGE = "當前網速偏慢，需要花更長時間搜尋…"
    const val TIMEOUT_MESSAGE = "搜尋超時，請重試。"
    fun retry(elapsed: Long, count: Int, retried: Boolean, terminalError: Boolean = false) =
        !terminalError && !retried && count == 0 && elapsed >= RETRY_MILLIS && elapsed < DEADLINE_MILLIS
}

/** Never replace the first-read dialog with an empty store-results screen. */
internal object ShopPreviewPresentation {
    fun showResults(elapsed: Long, visibleCount: Int, market: Market? = null): Boolean =
        if (market in setOf(Market.SHOPEE, Market.YAHOO)) visibleCount >= 6 || elapsed >= 3_000L && visibleCount > 0
        else elapsed >= SearchWaitPolicy.SLOW_MILLIS && visibleCount > 0
}

internal class SearchHttpException(val statusCode: Int, platform: String) :
    java.io.IOException("$platform 回應 HTTP $statusCode，請稍後重試。")

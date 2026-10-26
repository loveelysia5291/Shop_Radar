package com.example.shopradar

/** Mobile feeds stream continuously; numbered sites finish a page before navigation. */
internal object SearchPageLoading {
    const val FIRST_BATCH = 10
    const val BOBO_PREPARE_MILLIS = 7_000L
    fun prepareRemaining(market: Market, elapsed: Long): Long =
        if (market == Market.BBOBO) (BOBO_PREPARE_MILLIS - elapsed.coerceAtLeast(0)).coerceAtLeast(0) else 0
    fun firstBatch(market: Market) = when (market) {
        Market.SHOPEE, Market.YAHOO -> 6
        Market.FACEBOOK, Market.CAROUSELL -> 15
        else -> FIRST_BATCH
    }
    fun previewMillis(market: Market): Long = if (market in setOf(Market.SHOPEE, Market.YAHOO)) 3_000L else PREVIEW_MILLIS
    // Keep filling two batches quickly, including after the visible user catches up.
    fun fastFirstBatch(market: Market, count: Int) = market in setOf(Market.SHOPEE, Market.YAHOO) && count < 12
    fun feedPollMillis(market: Market, count: Int): Long = if (fastFirstBatch(market, count)) 250L else 650L
    fun batchCount(total: Int, market: Market): Int {
        val first = firstBatch(market)
        val step = if (market in setOf(Market.SHOPEE, Market.YAHOO)) 6 else 10
        return if (total < first) 0 else first + (total - first) / step * step
    }
    fun pauseFeed(paused: Boolean, remaining: Int): Boolean = if (paused) remaining >= 15 else remaining > 50
    const val PREVIEW_MILLIS = 10_000L
    const val SLOW_MESSAGE = "正在努力加載中…"
    const val PAGE_DEADLINE_MILLIS = 60_000L
    const val PARTIAL_MESSAGE = "部分商品尚未載入完成，請按刷新重試。"
    fun loadMoreAfterTimeout(count: Int, message: String): Boolean = count > 0 &&
        (message.contains(PARTIAL_MESSAGE) || message.contains("抓取逾時") ||
            message.contains(SearchWaitPolicy.TIMEOUT_MESSAGE))
    fun infinite(market: Market) = market in setOf(Market.YAHOO, Market.SHOPEE, Market.CAROUSELL, Market.RUTEN, Market.FACEBOOK)
    fun emptyDeadline(market: Market, firstBatch: Boolean) = PREVIEW_MILLIS
    fun showPreview(elapsed: Long, count: Int, market: Market = Market.RUTEN) =
        market != Market.BBOBO && (count >= firstBatch(market) || elapsed >= previewMillis(market) && count > 0)
    fun deadline(productCount: Int, market: Market = Market.CAROUSELL): Long =
        if (productCount > 0) PAGE_DEADLINE_MILLIS else emptyDeadline(market, true)
    fun readyCount(total: Int, columns: Int, loading: Boolean): Int {
        val count = total.coerceAtLeast(0)
        val width = columns.coerceIn(1, 2)
        return if (loading) count - count % width else count
    }
    fun nextRow(current: Int, total: Int, columns: Int): Int {
        val width = columns.coerceIn(1, 2)
        return minOf(total, (current / width + 1) * width)
    }

    /** A stable bottom is evidence of exhaustion only after lazy loading has had time to run. */
    class FeedEnd {
        private var previous = ""
        private var bottomSince: Long? = null
        private var changedAt: Long? = null
        fun reset() { previous = ""; bottomSince = null; changedAt = null }
        fun observe(signature: String, atBottom: Boolean, ready: Boolean, busy: Boolean, now: Long): Boolean {
            if (previous != signature || changedAt == null) {
                previous = signature; changedAt = now; bottomSince = null
            }
            if (!atBottom || !ready || busy) { bottomSince = null; return false }
            if (bottomSince == null) bottomSince = now
            return now - bottomSince!! >= PREVIEW_MILLIS
        }
        fun stalled(now: Long): Boolean = changedAt?.let { now - it >= 30_000L } == true
    }

    /** Lazy loads can start only after scrolling; pre-scroll stability is not completion. */
    class Completion {
        private var signature = ""
        private var readySince: Long? = null

        fun reset() { signature = ""; readySince = null }

        fun ready(current: String, atEnd: Boolean, now: Long): Boolean {
            if (!atEnd || current.isEmpty()) { reset(); return false }
            if (signature != current || readySince == null) {
                signature = current; readySince = now
                return false
            }
            return now - readySince!! >= 2_500L
        }
    }
}

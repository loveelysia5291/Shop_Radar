package com.aykkdk.shop

import android.content.Context

/** Shared budgets keep UI deadlines and reader lifetimes in sync. */
internal object CaptureBudgets {
    const val SEARCH_MILLIS = 5_000L
    const val MORE_MILLIS = SearchWaitPolicy.DEADLINE_MILLIS
    @Suppress("UNUSED_PARAMETER") fun searchMillis(platform: String): Long = SearchWaitPolicy.DEADLINE_MILLIS
    fun waitForReader(platform: String, productCount: Int): Boolean =
        platform == Market.SHOPEE.label || platform == Market.YAHOO.label && productCount < 15
    fun reader(market: Market, quick: Boolean, more: Boolean): Long = when {
        quick -> SearchWaitPolicy.DEADLINE_MILLIS
        more -> MORE_MILLIS
        market == Market.FACEBOOK -> 35_000L
        market == Market.YAHOO -> 55_000L
        else -> 30_000L
    }
}

/** Reuse bundled script text instead of reopening assets for each capture/page. */
internal object CaptureAssets {
    private val cache = mutableMapOf<String, String>()
    private val scripts = mutableMapOf<Market, String>()
    @Synchronized fun read(context: Context, name: String): String =
        cache.getOrPut(name) { context.applicationContext.assets.open(name).bufferedReader().use { it.readText() } }
    @Synchronized fun script(context: Context, market: Market): String = scripts.getOrPut(market) {
        (when (market) {
            Market.BBOBO -> read(context, "bobo-metadata.js") + ";\n"
            Market.FACEBOOK, Market.SHOPEE, Market.IOPEN -> read(context, "facebook-public-metadata.js") + ";\n" +
                read(context, "public-listing-time.js") + ";\n"
            else -> ""
        }) +
            read(context, when (market) {
                Market.SHOPEE -> "extract-shopee-products.js"
                Market.FACEBOOK -> "extract-facebook.js"
                Market.BBOBO, Market.IOPEN -> "extract-extra-markets.js"
                else -> "extract-products.js"
            })
    }
}

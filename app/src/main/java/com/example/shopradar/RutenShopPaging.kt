package com.example.shopradar

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A Ruten store is newest-first.  On refresh, continue through pages while a
 * page has not reached the previous checkpoint. Equal timestamps are not new.
 */
internal object RutenShopPaging {
    private val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy-MM-dd", "yyyy/MM/dd")

    fun listedAtMillis(value: String): Long? {
        val clean = value.trim()
        val numeric = clean.toLongOrNull()
        if (numeric != null && numeric > 1_000_000_000L) return if (numeric > 10_000_000_000L) numeric else numeric * 1000
        return formats.asSequence().mapNotNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.TAIWAN).apply { isLenient = false; timeZone = TimeZone.getTimeZone("Asia/Taipei") }.parse(clean)?.time }.getOrNull()
        }.firstOrNull()
    }

    fun newestMillis(products: List<Product>): Long = products.mapNotNull { listedAtMillis(it.listedAt) }.maxOrNull() ?: 0L

    /**
     * Stops only at an empty page or after every dated item is older than the
     * checkpoint. Store page sizes differ by Ruten layout, so a short page is
     * not proof that there is no next page.
     */
    fun needsNextPage(page: Int, products: List<Product>, checkpointMillis: Long, seen: Set<String> = emptySet()): Boolean {
        if (page >= 50 || products.isEmpty()) return false
        if (checkpointMillis <= 0L) return seen.isNotEmpty() && products.none { it.url in seen }
        val dates = products.mapNotNull { listedAtMillis(it.listedAt) }
        // No reliable listing time means continuing could turn into an unbounded crawl.
        if (dates.isEmpty()) return seen.isNotEmpty() && products.none { it.url in seen }
        return !ShopCheckpoint.scan(products, checkpointMillis, seen).reachedOld
    }

    fun isNewSince(product: Product, checkpointMillis: Long, seen: Set<String>): Boolean {
        if (product.url in seen) return false
        // An unseen listing is still new to this subscription when the public
        // card omits its date; never silently discard it or invent a timestamp.
        val listed = listedAtMillis(product.listedAt) ?: return true
        return checkpointMillis <= 0L || listed > checkpointMillis
    }
}

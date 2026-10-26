package com.aykkdk.shop

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Freeze relative dates at capture time; never recompute stored "hours ago" at startup. */
internal object ShopCheckpoint {
    private val formats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm", "yyyy-MM-dd", "yyyy/MM/dd")
    // SimpleDateFormat is mutable: reuse only within the same worker thread.
    private val parsers = object : ThreadLocal<List<SimpleDateFormat>>() {
        override fun initialValue() = formats.map { pattern ->
            SimpleDateFormat(pattern, Locale.TAIWAN).apply { isLenient = false; timeZone = TimeZone.getTimeZone("Asia/Taipei") }
        }
    }
    private val immediate = Regex("^(剛剛|just now|不到\\s*1\\s*分鐘前|未滿\\s*1\\s*分鐘前)$", RegexOption.IGNORE_CASE)
    private val relativeTime = Regex("^(\\d+)\\s*(分鐘|分|小時|時|天|週)前$")
    fun millis(value: String, capturedAt: Long): Long? {
        val text = value.trim()
        text.toLongOrNull()?.takeIf { it > 1_000_000_000L }?.let { return if (it > 10_000_000_000L) it else it * 1000 }
        if (text.isEmpty()) return null
        if (immediate.matches(text)) return capturedAt
        val relative = relativeTime.matchEntire(text)
        if (relative != null) {
            val unit = when (relative.groupValues[2]) { "分鐘", "分" -> 60_000L; "小時", "時" -> 3_600_000L; "天" -> 86_400_000L; else -> 604_800_000L }
            return relative.groupValues[1].toLongOrNull()?.takeIf { it <= 100_000 }?.let { capturedAt - it * unit }
        }
        parsers.get()!!.forEach { parser ->
            val position = ParsePosition(0)
            val date = parser.parse(text, position)
            if (date != null && position.index == text.length) return date.time
        }
        return null
    }
    fun freeze(products: List<Product>, capturedAt: Long): List<Product> = products.map { p ->
        if (p.listedAtEpochMillis > 0) p else millis(p.listedAt, capturedAt)?.let { p.copy(listedAtEpochMillis = it) } ?: p
    }
    fun timestamp(p: Product): Long? = p.listedAtEpochMillis.takeIf { it > 0 } ?: millis(p.listedAt, 0)?.takeIf { it > 0 }
    fun newest(products: List<Product>): Long = products.filterNot { it.isAdvertisement }.mapNotNull(::timestamp).maxOrNull() ?: 0
    data class Scan(val products: List<Product>, val reachedOld: Boolean)
    /** Input is a newest-first store page. Stop at the first equal or older listing. */
    fun scan(products: List<Product>, checkpoint: Long, seen: Set<String>): Scan {
        val scanned = mutableListOf<Product>()
        for (p in products) {
            if (p.isAdvertisement) continue
            val at = timestamp(p)
            // Known IDs also stop relative-date rounding from moving the baseline forward.
            if ((checkpoint > 0 && at != null && at <= checkpoint) || p.url in seen) return Scan(scanned, true)
            scanned.add(p)
        }
        return Scan(scanned, false)
    }
    fun isNew(p: Product, checkpoint: Long, seen: Set<String>): Boolean = p.url !in seen && (timestamp(p)?.let { checkpoint <= 0 || it > checkpoint } ?: true)
}

package com.example.shopradar

/** Display only. Never replace stored creation timestamps or subscription checkpoints. */
internal object ListingTimeDisplay {
    fun visible(platform: String) = platform !in setOf(Market.SHOPEE.label, Market.SHOPEE.name, Market.YAHOO.label, Market.YAHOO.name)
    fun text(platform: String, original: String, epochMillis: Long = 0, now: Long = System.currentTimeMillis()): String {
        if (original.isBlank()) return "平台未公開／尚未取得"
        if (platform != Market.SHOPEE.label) return original
        val at = epochMillis.takeIf { it > 0 } ?: ShopCheckpoint.millis(original, 0) ?: return original
        if (at < 1_072_915_200_000L || at > now + 60_000) return original
        val seconds = (now - at).coerceAtLeast(0) / 1000
        val (count, unit) = when {
            seconds < 60 -> seconds to "秒"
            seconds < 3600 -> seconds / 60 to "分鐘"
            seconds < 86400 -> seconds / 3600 to "小時"
            seconds < 604800 -> seconds / 86400 to "天"
            seconds < 2592000 -> seconds / 604800 to "週"
            seconds < 31536000 -> seconds / 2592000 to "個月"
            else -> seconds / 31536000 to "年"
        }
        return "$count $unit" + "前"
    }
}

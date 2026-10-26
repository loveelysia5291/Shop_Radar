package com.aykkdk.shop

/** Display only: failed attempts must never advance a successful refresh watermark. */
internal object RefreshTimeDisplay {
    fun text(at: Long, now: Long): String {
        if (at <= 0) return "尚未刷新"
        val minutes = (now.coerceAtLeast(0) - at).coerceAtLeast(0) / 60_000
        return when {
            minutes == 0L -> "剛剛"
            minutes < 60 -> "$minutes 分鐘前"
            minutes < 1440 -> "${minutes / 60} 小時前"
            else -> "${minutes / 1440} 天前"
        }
    }
}

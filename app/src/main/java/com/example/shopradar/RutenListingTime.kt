package com.example.shopradar

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Creation time only. Never substitute refresh, close, modification or shipping dates. */
internal object RutenListingTime {
    data class Value(val text: String, val epochMillis: Long)

    fun read(item: JSONObject): Value? {
        for (key in listOf("PostTime", "post_time", "PostTimeStr", "CreateTime", "CreateTimeStr", "StartTime")) {
            val at = ShopCheckpoint.millis(item.optString(key), 0) ?: continue
            // Reject placeholders, overflowed epochs and malformed dates, then try the next creation field.
            if (at !in 1_072_915_200_000L until 4_102_444_800_000L) continue
            val text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).apply {
                timeZone = TimeZone.getTimeZone("Asia/Taipei")
            }.format(Date(at))
            return Value(text, at)
        }
        return null
    }
}

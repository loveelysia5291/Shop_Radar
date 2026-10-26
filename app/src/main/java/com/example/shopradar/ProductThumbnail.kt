package com.example.shopradar

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Only read image fields belonging to the already identified product. */
internal object ProductThumbnail {
    fun url(raw: String, base: String? = null): String? = runCatching {
        if (raw.isBlank() || raw.trim() in setOf("null", "undefined") || raw.length > 8192) return null
        val uri = base?.let { URI(it).resolve(raw.trim()) } ?: URI(raw.trim())
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.port !in setOf(-1, 443)) null
        else uri.toASCIIString()
    }.getOrNull()

    fun fromJson(value: Any?, base: String): String? = fromJson(value, base, 0)

    private fun fromJson(value: Any?, base: String, depth: Int): String? {
        if (depth > 3) return null
        return when (value) {
        is String -> url(value, base)
        is JSONArray -> (0 until minOf(value.length(), 10)).firstNotNullOfOrNull { fromJson(value.opt(it), base, depth + 1) }
        is JSONObject -> listOf("contentUrl", "url").firstNotNullOfOrNull { url(value.optString(it), base) }
        else -> null
        }
    }
}

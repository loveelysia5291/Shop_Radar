package com.aykkdk.shop

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Only read image fields belonging to the already identified product. */
internal object ProductThumbnail {
    fun url(raw: String, base: String? = null): String? = runCatching {
        var text = raw.trim()
        if (text.isBlank() || text in setOf("null", "undefined") || text.length > 8192) return null
        if (text.startsWith("//")) text = "https:$text"
        val resolved = if (base != null && !text.startsWith("http://") && !text.startsWith("https://")) {
            runCatching {
                val b = URI(base)
                val baseScheme = b.scheme ?: "https"
                val baseHost = b.host ?: ""
                if (text.startsWith("/")) "$baseScheme://$baseHost$text"
                else b.resolve(text).toString()
            }.getOrDefault(text)
        } else text

        val ascii = if (resolved.any { it.code > 127 || it == ' ' }) {
            buildString(resolved.length + 16) {
                for (ch in resolved) {
                    if (ch == ' ') append("%20")
                    else if (ch.code > 127) {
                        for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                            append(String.format("%%%02X", b.toInt() and 0xFF))
                        }
                    } else append(ch)
                }
            }
        } else resolved

        val uri = URI(ascii)
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

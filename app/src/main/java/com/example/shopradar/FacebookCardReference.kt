package com.example.shopradar

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Local search-card reference. Never treat its UI handle/photo ID as a listing ID. */
internal data class FacebookCardReference(val source: String, val label: String, val image: String) {
    companion object {
        private const val PREFIX = "shopradar-fb-card="
        fun parse(raw: String): FacebookCardReference? = runCatching {
            if (raw.length > 8000) return null
            val uri = URI(raw)
            if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
                uri.host !in setOf("www.facebook.com", "m.facebook.com", "web.facebook.com") ||
                !Regex("^/marketplace/(?:\\d+/)?search/?$").matches(uri.path)) return null
            val fragment = uri.rawFragment ?: return null
            if (!fragment.startsWith(PREFIX)) return null
            val value = JSONObject(URLDecoder.decode(fragment.removePrefix(PREFIX), "UTF-8"))
            val label = value.optString("label")
            val image = value.optString("image")
            if (label.length !in 2..2000 || !label.contains(Regex("\\s+for sale\\s*-\\s*", RegexOption.IGNORE_CASE)) ||
                image.length !in 1..1500 || !image.startsWith("/") || image.contains('?') || image.contains('#')) return null
            FacebookCardReference(raw.substringBefore('#'), label, image)
        }.getOrNull()
    }
}

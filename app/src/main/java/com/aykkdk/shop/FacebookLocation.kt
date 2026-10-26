package com.aykkdk.shop

import java.net.URI
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

internal data class FacebookPlace(val id: String, val name: String)

/** A place ID is a Facebook identifier, not a geocode or a hash of its name. */
internal object FacebookLocation {
    const val DEFAULT_ID = "114987875182625"
    const val LIMIT = 50
    val defaultPlace = FacebookPlace(DEFAULT_ID, "原本預設位置")
    private val hosts = setOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com")
    private val numeric = Regex("[1-9][0-9]{5,19}")

    fun validId(raw: String?): String? = raw?.trim()?.takeIf { numeric.matches(it) }
    fun id(raw: String?): String = validId(raw) ?: DEFAULT_ID
    fun label(raw: String?, id: String): String = raw.orEmpty().trim().filterNot(Char::isISOControl).take(80)
        .takeUnless { id != DEFAULT_ID && it == defaultPlace.name }.orEmpty()
        .ifBlank { if (id == DEFAULT_ID) defaultPlace.name else "位置 $id" }
    private fun uri(raw: String): URI? = runCatching { URI(raw.trim()) }.getOrNull()?.takeIf {
        it.scheme.equals("https", true) && it.host?.lowercase() in hosts && it.userInfo == null && it.port == -1
    }
    fun browserAllowed(raw: String): Boolean = uri(raw) != null
    fun marketplacePage(raw: String): Boolean = uri(raw)?.rawPath?.let { it == "/marketplace" || it.startsWith("/marketplace/") } == true
    fun fromUrl(raw: String): String? = uri(raw)?.rawPath?.let {
        Regex("^/marketplace/([1-9][0-9]{5,19})(?:/search)?/?$").matchEntire(it)?.groupValues?.get(1)
    }
    fun fromInput(raw: String): String? = validId(raw) ?: fromUrl(raw)
    fun changedId(initial: String, url: String): String? = fromUrl(url)?.takeIf { it != id(initial) }
    fun place(id: String?, name: String?): FacebookPlace? = validId(id)?.let { FacebookPlace(it, label(name, it)) }
    fun searchUrl(keyword: String, min: String, max: String, sort: String, placeId: String = DEFAULT_ID): String {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        val values = listOf("minPrice" to min.ifBlank { "1" }, "maxPrice" to max.ifBlank { "2000" },
            "sortBy" to FacebookFilters.sort(sort), "query" to keyword, "exact" to "false", "radius" to "250")
        return "https://www.facebook.com/marketplace/${id(placeId)}/search?" +
            values.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }
    fun remember(records: List<FacebookPlace>, value: FacebookPlace): List<FacebookPlace> =
        place(value.id, value.name)?.let { listOf(it) + records.filterNot { old -> old.id == it.id }.take(LIMIT - 1) } ?: records
    fun candidates(records: List<FacebookPlace>, query: String): List<FacebookPlace> {
        fun normalized(value: String) = value.trim().replace('臺', '台').lowercase()
        val key = normalized(query)
        // Never automatically choose a fuzzy match: identical names may identify different cities.
        return records.filter { key.isBlank() || normalized(it.name).contains(key) || it.id == key }
    }
    fun encode(records: List<FacebookPlace>): String = JSONArray(records.take(LIMIT).map {
        JSONObject().put("id", it.id).put("name", it.name)
    }).toString()
    fun decode(raw: String?): List<FacebookPlace> {
        if (raw.isNullOrBlank() || raw.length > 32 * 1024) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length().coerceAtMost(LIMIT)).mapNotNull { index ->
                array.optJSONObject(index)?.let { place(it.optString("id"), it.optString("name")) }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }
}

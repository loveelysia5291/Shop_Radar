package com.aykkdk.shop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SearchSubscription(
    val id: String,
    val keyword: String,
    val platform: String = "ALL",
    val min: String = "",
    val max: String = "",
    val url: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeenId: String = "",
    val listingCheckpointAt: Long = 0L,
    val lightningRefresh: Boolean = false
)

class SearchSubscriptionStore(context: Context) {
    val prefs = context.getSharedPreferences("search_subscriptions_v1", Context.MODE_PRIVATE)

    fun list(): List<SearchSubscription> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                SearchSubscription(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    keyword = obj.optString("keyword"),
                    platform = obj.optString("platform", "ALL"),
                    min = obj.optString("min"),
                    max = obj.optString("max"),
                    url = obj.optString("url"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    lastSeenId = obj.optString("lastSeenId"),
                    listingCheckpointAt = obj.optLong("listingCheckpointAt", 0L),
                    lightningRefresh = obj.optBoolean("lightningRefresh", false)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun add(keyword: String, platform: String, min: String = "", max: String = "", url: String = ""): Boolean {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isBlank()) return false
        val current = list().toMutableList()
        if (current.any { it.keyword.equals(cleanKeyword, ignoreCase = true) && it.platform.equals(platform, ignoreCase = true) }) {
            return false
        }
        val sub = SearchSubscription(
            id = "sub_search_" + UUID.randomUUID().toString().take(8),
            keyword = cleanKeyword,
            platform = platform.ifBlank { "ALL" },
            min = min,
            max = max,
            url = url
        )
        current.add(sub)
        save(current)
        return true
    }

    fun remove(id: String) {
        val current = list().filter { it.id != id }
        save(current)
    }

    fun updateSeen(id: String, lastSeenId: String, checkpoint: Long) {
        val current = list().map {
            if (it.id == id) it.copy(lastSeenId = lastSeenId, listingCheckpointAt = maxOf(it.listingCheckpointAt, checkpoint))
            else it
        }
        save(current)
    }

    fun restore(subs: List<SearchSubscription>) {
        save(subs)
    }

    private fun save(items: List<SearchSubscription>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("keyword", s.keyword)
                put("platform", s.platform)
                put("min", s.min)
                put("max", s.max)
                put("url", s.url)
                put("createdAt", s.createdAt)
                put("lastSeenId", s.lastSeenId)
                put("listingCheckpointAt", s.listingCheckpointAt)
                put("lightningRefresh", s.lightningRefresh)
            })
        }
        prefs.edit().putString("items", arr.toString()).commit()
    }
}

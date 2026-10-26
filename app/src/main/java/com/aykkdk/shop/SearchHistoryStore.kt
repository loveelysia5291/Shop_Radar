package com.aykkdk.shop

import android.content.Context

/** Kept separate from subscriptions, cookies, and image caches. */
internal class SearchHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences("search_history_v1", Context.MODE_PRIVATE) }
    fun load(): List<SearchHistoryEntry> = SearchHistory.decode(prefs.getString("records", null))
    fun save(entries: List<SearchHistoryEntry>) {
        if (entries.isEmpty()) prefs.edit().remove("records").apply()
        else prefs.edit().putString("records", SearchHistory.encode(entries)).apply()
    }
}

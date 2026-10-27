package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Form values only: never store sessions, page results, or pagination state. */
internal data class SearchHistoryCriteria(
    val market: Market,
    val keyword: String = "",
    val min: String = "",
    val max: String = "",
    val sort: String = "",
    val condition: String = "",
    val mall: Boolean = false,
    val preferred: Boolean = false,
    val mainCategory: String = "",
    val category: String = "",
    val yahooState: String = "0",
    val boboMall: String = "0",
    val facebookLocationId: String = FacebookLocation.DEFAULT_ID,
    val facebookLocationName: String = FacebookLocation.defaultPlace.name
) {
    /** Drop unrelated platform fields so restoring one platform cannot leak filters. */
    fun normalized(): SearchHistoryCriteria {
        val base = SearchHistoryCriteria(market, keyword.trim().take(500),
            min.filter(Char::isDigit).take(9), max.filter(Char::isDigit).take(9))
        val categoryPair = categories().firstOrNull { it.id == mainCategory }?.let { group ->
            group.id to category.takeIf { it == group.id || group.children.any { child -> child.id == it } }.orEmpty()
        } ?: ("" to "")
        return when (market) {
            Market.SHOPEE -> base.copy(sort = sort.takeIf { value -> ShopeeFilters.sorts.any { it.first == value } } ?: "relevancy",
                condition = condition.takeIf { value -> ShopeeFilters.conditions.any { it.first == value } }.orEmpty(),
                mall = mall, preferred = preferred)
            Market.FACEBOOK -> base.copy(sort = FacebookFilters.sort(sort),
                facebookLocationId = FacebookLocation.id(facebookLocationId),
                facebookLocationName = FacebookLocation.label(
                    facebookLocationName.takeIf { FacebookLocation.validId(facebookLocationId) != null }, FacebookLocation.id(facebookLocationId)))
            Market.CAROUSELL -> base.copy(sort = sort.takeIf { it in CAROUSELL_SORTS } ?: "近期",
                mainCategory = categoryPair.first, category = categoryPair.second)
            Market.YAHOO -> base.copy(sort = sort.takeIf { value -> YahooFilters.sorts.any { it.first == value } }.orEmpty(),
                yahooState = yahooState.takeIf { value -> YahooFilters.states.any { it.first == value } } ?: "0",
                mainCategory = categoryPair.first, category = categoryPair.second)
            Market.RUTEN -> base.copy(sort = RutenFilters.sort(sort),
                mainCategory = categoryPair.first, category = categoryPair.second)
            Market.BBOBO -> base.copy(boboMall = BoboSearch.zone(boboMall), category = BoboSearch.category(category))
            Market.IOPEN -> base
        }
    }

    fun canSave(): Boolean = !AdultVisibility.isUnlockInput(keyword) &&
        (keyword.isNotBlank() || (market in setOf(Market.CAROUSELL, Market.YAHOO, Market.RUTEN) && category.isNotBlank())) &&
        (min.isBlank() || max.isBlank() || min.toLongOrNull()?.let { low -> max.toLongOrNull()?.let { low <= it } } == true)

    fun visible(unlocked: Boolean): Boolean = unlocked || !(AdultVisibility.adultText(keyword) ||
        AdultVisibility.adultCategory(market, mainCategory) || AdultVisibility.adultCategory(market, category) ||
        categories().any { group ->
            (group.id == mainCategory && AdultVisibility.adultText(group.name)) ||
                group.children.any { it.id == category && AdultVisibility.adultText(it.name) }
        })

    val title: String get() = keyword.ifBlank { categoryName().ifBlank { "分類搜尋" } }

    fun summary(): String = buildList {
        when (market) {
            Market.SHOPEE -> {
                add(ShopeeFilters.sorts.firstOrNull { it.first == sort }?.second ?: "綜合排名")
                add(ShopeeFilters.conditions.firstOrNull { it.first == condition }?.second ?: "全部")
                add(ShopeeFilters.shopTypeLabel(mall, preferred))
            }
            Market.FACEBOOK -> {
                add(FacebookFilters.sorts.firstOrNull { it.first == sort }?.second ?: "距離最近")
            }
            Market.CAROUSELL -> add(sort)
            Market.YAHOO -> {
                add(YahooFilters.sorts.firstOrNull { it.first == sort }?.second ?: "最相關")
                add(YahooFilters.states.firstOrNull { it.first == yahooState }?.second ?: "全部")
            }
            Market.RUTEN -> add(RutenFilters.sorts.firstOrNull { it.first == sort }?.second ?: "熱門")
            Market.BBOBO -> add(BoboSearch.malls.firstOrNull { it.first == boboMall }?.second ?: "二手商城")
            Market.IOPEN -> Unit
        }
        categoryName().takeIf { it.isNotBlank() }?.let { add(it) }
        if (min.isNotBlank() || max.isNotBlank()) add("價格 ${min.ifBlank { "不限" }}–${max.ifBlank { "不限" }}")
    }.joinToString(" · ")

    private data class Category(val id: String, val name: String, val children: List<Category> = emptyList())
    private fun categories(): List<Category> = when (market) {
        Market.CAROUSELL -> CarousellCategories.groups.map { group ->
            Category(group.url, group.name, group.children.map { Category(it.url, it.name) }) }
        Market.YAHOO -> YahooCategories.groups.map { group ->
            Category(group.cid, group.name, group.children.map { Category(it.cid, it.name) }) }
        Market.RUTEN -> RutenCategories.groups.map { group ->
            Category(group.id, group.name, group.children.map { Category(it.id, it.name) }) }
        else -> emptyList()
    }
    private fun categoryName(): String = if (market == Market.BBOBO) BoboSearch.category(category) else categories().firstOrNull { it.id == mainCategory }?.let { group ->
        val child = group.children.firstOrNull { it.id == category }
        if (child == null) group.name else "${group.name}／${child.name}"
    }.orEmpty()

    companion object { val CAROUSELL_SORTS = listOf("最佳匹配", "近期", "價位低至高", "價位高至低") }
}

internal data class SearchHistoryEntry(val id: String, val criteria: SearchHistoryCriteria, val savedAt: Long)

/** Pure bounded history operations, shared by Debug and Release and testable without Android. */
internal object SearchHistory {
    const val LIMIT_PER_PLATFORM = 50
    private const val MAX_JSON_LENGTH = 512 * 1024

    fun forMarket(entries: List<SearchHistoryEntry>, market: Market, unlocked: Boolean): List<SearchHistoryEntry> =
        entries.filter { it.criteria.market == market && it.criteria.visible(unlocked) }

    fun record(entries: List<SearchHistoryEntry>, criteria: SearchHistoryCriteria, now: Long = System.currentTimeMillis()): List<SearchHistoryEntry> {
        val value = criteria.normalized()
        if (!value.canSave()) return entries
        val previous = entries.firstOrNull { it.criteria == value }
        val newest = SearchHistoryEntry(previous?.id ?: UUID.randomUUID().toString(), value, now)
        return bounded(listOf(newest) + entries.filterNot { it.criteria == value })
    }

    fun delete(entries: List<SearchHistoryEntry>, id: String): List<SearchHistoryEntry> = entries.filterNot { it.id == id }

    private fun bounded(entries: List<SearchHistoryEntry>): List<SearchHistoryEntry> {
        val counts = mutableMapOf<Market, Int>()
        val seen = mutableSetOf<SearchHistoryCriteria>()
        val ids = mutableSetOf<String>()
        return entries.filter { entry ->
            val count = counts[entry.criteria.market] ?: 0
            if (count >= LIMIT_PER_PLATFORM || !seen.add(entry.criteria) || !ids.add(entry.id)) false
            else { counts[entry.criteria.market] = count + 1; true }
        }
    }

    fun encode(entries: List<SearchHistoryEntry>): String {
        val records = JSONArray()
        bounded(entries).forEach { entry ->
            val c = entry.criteria
            records.put(JSONObject().put("id", entry.id).put("savedAt", entry.savedAt)
                .put("market", c.market.name).put("keyword", c.keyword).put("min", c.min).put("max", c.max)
                .put("sort", c.sort).put("condition", c.condition).put("mall", c.mall).put("preferred", c.preferred)
                .put("mainCategory", c.mainCategory).put("category", c.category)
                .put("yahooState", c.yahooState).put("boboMall", c.boboMall)
                .put("facebookLocationId", c.facebookLocationId).put("facebookLocationName", c.facebookLocationName))
        }
        return JSONObject().put("version", 1).put("entries", records).toString()
    }

    fun decode(raw: String?): List<SearchHistoryEntry> {
        if (raw.isNullOrBlank() || raw.length > MAX_JSON_LENGTH) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("version") != 1) return emptyList()
            val records = root.optJSONArray("entries") ?: return emptyList()
            val entries = buildList {
                for (index in 0 until records.length().coerceAtMost(LIMIT_PER_PLATFORM * Market.values().size)) {
                    val item = records.optJSONObject(index) ?: continue
                    val market = Market.values().firstOrNull { it.name == item.optString("market") } ?: continue
                    val id = item.optString("id").takeIf { it.length in 1..64 } ?: continue
                    val criteria = SearchHistoryCriteria(market, item.optString("keyword"), item.optString("min"), item.optString("max"),
                        item.optString("sort"), item.optString("condition"), item.optBoolean("mall"), item.optBoolean("preferred"),
                        item.optString("mainCategory"), item.optString("category"), item.optString("yahooState", "0"),
                        item.optString("boboMall", "0"), item.optString("facebookLocationId", FacebookLocation.DEFAULT_ID),
                        item.optString("facebookLocationName", FacebookLocation.defaultPlace.name)).normalized()
                    if (criteria.canSave()) add(SearchHistoryEntry(id, criteria, item.optLong("savedAt")))
                }
            }
            bounded(entries)
        }.getOrDefault(emptyList())
    }
}

package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject

/** Official public search parameters; store subscriptions keep their newest-first order. */
internal object ShopeeFilters {
    val sorts = listOf(
        "relevancy" to "綜合排名", "ctime" to "最新", "sales" to "月銷熱賣",
        "price_asc" to "價格：低到高", "price_desc" to "價格：高到低"
    )
    val conditions = listOf("" to "全部", "NEW_ITEM" to "全新", "USED_ITEM" to "二手")

    fun shopTypeLabel(mall: Boolean, preferred: Boolean): String = when {
        mall && preferred -> "商城＋優選"
        mall -> "蝦皮商城"
        preferred -> "蝦皮優選"
        else -> "不限賣場"
    }

    /** All groups must share one fe_filter_options parameter; never overwrite price filters. */
    fun filterOptions(min: String = "", max: String = "", mall: Boolean = false,
        preferred: Boolean = false, condition: String = ""): String? {
        val groups = JSONArray()
        fun add(name: String, values: List<String>) {
            groups.put(JSONObject().put("group_name", name).put("values", JSONArray(values)))
        }
        if (min.isNotBlank() || max.isNotBlank()) {
            add("PRICE_RANGE", listOf("${min.ifBlank { "undefined" }}▶◀${max.ifBlank { "undefined" }}"))
        }
        val shops = buildList {
            if (preferred) add("PREFERRED")
            if (mall) add("OFFICIAL_MALL")
        }
        if (shops.isNotEmpty()) add("SHOP_TYPE", shops)
        if (condition in setOf("NEW_ITEM", "USED_ITEM")) add("CONDITION", listOf(condition))
        return if (groups.length() == 0) null else groups.toString()
    }

    fun parameters(value: String): Map<String, String> = when (value) {
        "ctime", "sales" -> mapOf("sortBy" to value)
        "price_asc" -> mapOf("sortBy" to "price", "order" to "asc")
        "price_desc" -> mapOf("sortBy" to "price", "order" to "desc")
        else -> emptyMap() // The site's default is comprehensive/relevance ranking.
    }
}

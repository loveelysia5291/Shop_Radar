package com.example.shopradar

internal object YahooFilters {
    val states = listOf("0" to "全部", "1" to "立即買", "2" to "競標", "3" to "二手")
    val sorts = listOf("" to "最相關", "-ptime" to "新刊登", "etime" to "即將結標", "popular" to "最佳人氣", "auc_ranking" to "全站排名", "curp" to "價格低到高", "-curp" to "價格高到低")
    fun parameters(state: String, sort: String): List<Pair<String, String>> {
        val acu = state.takeIf { value -> states.any { it.first == value } } ?: "0"
        val refine = mapOf("1" to "buy_now", "2" to "bidding", "3" to "con_used")[acu]
        val order = sort.takeIf { value -> sorts.any { it.first == value } }.orEmpty()
        return buildList {
            if (acu != "0") add("acu" to acu)
            if (refine != null) add("refine" to refine)
            if (order.isNotBlank()) add("sort" to order)
        }
    }
}

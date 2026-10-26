package com.aykkdk.shop

/** Public Marketplace sort values. Keep the existing location/radius unchanged. */
internal object FacebookFilters {
    const val DEFAULT_SORT = "distance_ascend"
    val sorts = listOf(
        DEFAULT_SORT to "距離最近",
        "creation_time_descend" to "最新",
        "price_ascend" to "價格：低到高",
        "price_descend" to "價格：高到低"
    )
    fun sort(value: String): String = value.takeIf { selected -> sorts.any { it.first == selected } } ?: DEFAULT_SORT
}

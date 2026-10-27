package com.aykkdk.shop

internal object RutenFilters {
    val sorts = listOf(
        "" to "熱門",
        "prc/ac" to "價格低",
        "prc/dc" to "價格高",
        "ords/dc" to "銷量",
        "new/dc" to "最新"
    )

    fun sort(value: String): String =
        value.takeIf { selected -> sorts.any { it.first == selected } }.orEmpty()
}

package com.example.shopradar

import org.json.JSONObject

/** Counts are evidence, not purchase limits, sold counts or quantities in descriptions. */
internal object ProductStock {
    fun quantity(value: Any?): Long? = value?.toString()?.trim()?.toBigDecimalOrNull()
        ?.let { runCatching { it.longValueExact() }.getOrNull() }?.takeIf { it >= 0 }

    fun inventory(offer: JSONObject?): Long? {
        if (offer == null || offer.optString("@type").let { it.isNotBlank() && it != "Offer" }) return null
        val value = offer.opt("inventoryLevel") as? JSONObject ?: return null
        if (value.has("minValue") || value.has("maxValue")) return null
        val code = value.optString("unitCode").uppercase()
        val text = value.optString("unitText").lowercase()
        if (code.isNotBlank() && code !in setOf("C62", "EA", "H87")) return null
        if (text.isNotBlank() && text !in setOf("件", "個", "台", "支", "副", "piece", "pieces", "item", "items", "each")) return null
        return quantity(value.opt("value"))
    }

    fun label(item: JSONObject): String = when (item.optString("status")) {
        "sold" -> "已售出／缺貨"
        "removed" -> "已下架／失效"
        "active" -> if (item.has("lastCheckError")) "上次有庫存（待確認）"
            else quantity(item.opt("stockQuantity"))?.takeIf { it > 0 }?.let { "有庫存（剩餘 $it 件）" }
                ?: "有庫存"
        else -> "庫存待確認"
    }
}

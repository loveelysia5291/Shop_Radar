package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject

/** Recover identity from the preference key used by older subscription checks. */
internal fun shopSubscriptionRecord(key: String, raw: String?): JSONObject {
    val record = runCatching { JSONObject(raw ?: "{}") }.getOrElse { JSONObject() }
    record.put("url", key)
    val market = Market.values().firstOrNull {
        it.name == record.optString("platform") || it.label == record.optString("platform")
    } ?: Market.fromPublicUrl(key)
    if (market != null) record.put("platform", market.name)
    return record
}

/** Clear retained listings without resetting the shop's comparison baseline. */
internal fun clearShopNewItems(key: String, raw: String?): JSONObject =
    shopSubscriptionRecord(key, raw).put("newItems", JSONArray()).put("newCount", 0).put("latestNewItemUrls", JSONArray())

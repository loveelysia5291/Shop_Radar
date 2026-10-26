package com.example.shopradar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** A display preference for this foreground session, not authentication or age verification. */
internal object AdultVisibility {
    var unlocked by mutableStateOf(false)
        private set
    fun isUnlockInput(value: String): Boolean = value.trim() == "1145141919810"
    fun consumeInput(value: String): Boolean {
        if (!isUnlockInput(value)) return false
        unlocked = true
        return true
    }
    fun lock() { unlocked = false }
    private val terms = Regex("成人專區|成人用品|成人玩具|成人影音|成人漫畫|成人影片|情趣|18\\s*[禁+]|十八禁|充氣娃娃|性愛|跳蛋|自慰|按摩棒|飛機杯|飞机杯|肛塞|潤滑劑|女优|女優|猛男DIY|調情衣飾|\\b(?:nsfw|porn|dildo|vibrator|sex\\s*toy|hentai)\\b", RegexOption.IGNORE_CASE)
    fun adultText(value: String): Boolean = terms.containsMatchIn(value)
    fun adultCategory(market: Market, id: String, name: String = ""): Boolean = adultText(name) || when (market) {
        Market.RUTEN -> id.startsWith("0025") || id == "00020052"
        Market.YAHOO -> id in setOf("41179373", "41179374", "41179375", "41179376", "41179378", "41179379", "41179383", "53686009")
        else -> false
    }
    fun visible(product: Product): Boolean = unlocked || !(product.isAdult || adultText(product.title) || adultText(product.shop) || adultText(product.category))
    fun visible(item: JSONObject): Boolean = unlocked || !(item.optBoolean("isAdult") || adultText(item.optString("title")) || adultText(item.optString("shop")) || adultText(item.optString("category")))
}

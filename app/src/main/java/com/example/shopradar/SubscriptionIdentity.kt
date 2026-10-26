package com.example.shopradar

import java.net.URI
import java.util.Locale

/** Compare public identities, not captions, thumbnails or share/search parameters. */
internal object SubscriptionIdentity {
    const val DUPLICATE_MESSAGE = "重複訂閱"

    fun sameProduct(first: String, second: String): Boolean {
        val market = Market.fromPublicProductUrl(first) ?: return false
        if (market != Market.fromPublicProductUrl(second)) return false
        if (market == Market.RUTEN) {
            val id = RutenProductMetadata.productId(first) ?: return false
            return id == RutenProductMetadata.productId(second)
        }
        return PublicProductParser.sameProductUrl(first, second)
    }

    fun sameShop(first: String, second: String): Boolean {
        val a = shopKey(first) ?: return false
        return a == shopKey(second)
    }

    private fun shopKey(raw: String): String? {
        val market = Market.fromPublicUrl(raw) ?: return null
        if (market.isPublicProductUrl(raw)) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val id = when (market) {
            Market.RUTEN -> market.rutenStoreUsername(raw)
            Market.SHOPEE -> PublicShareLinkResolver.canonicalShopeeDestination(raw)
                ?.let { URI(it).path.trim('/') }?.lowercase(Locale.ROOT)
            Market.CAROUSELL -> PublicShareLinkResolver.canonicalCarousellDestination(raw)
                ?.let { URI(it).path.trim('/').removePrefix("u/") }?.lowercase(Locale.ROOT)
            Market.YAHOO -> Regex("^/booth/([a-zA-Z0-9_-]+)/?$").matchEntire(uri.path.orEmpty())
                ?.groupValues?.get(1)?.uppercase(Locale.ROOT)
            // Keep the Facebook store route intact; do not confuse a place with a profile.
            Market.FACEBOOK -> uri.path.orEmpty().trimEnd('/').takeIf { it.isNotBlank() }?.let { path ->
                val identityQuery = uri.rawQuery.orEmpty().split('&')
                    .filter { it.substringBefore('=') in setOf("id", "profile_id", "seller_id") }.sorted()
                path + identityQuery.joinToString("&").let { if (it.isBlank()) "" else "?$it" }
            }
            else -> null
        } ?: return null
        return "${market.name}:$id"
    }
}

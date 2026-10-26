package com.aykkdk.shop

import android.content.Context

/** Pasting and Android sharing use the same resolver, classification and first refresh. */
internal object SubscriptionImporter {
    suspend fun add(context: Context, input: String): ImportedSubscription {
        val clean = PublicShareLinkResolver.resolve(input)
        val market = Market.fromPublicUrl(clean)
            ?: throw IllegalArgumentException("無法辨識平台，請貼上公開店家或商品網址。")
        if (market.isPublicProductUrl(clean)) {
            val store = ProductSubscriptionStore(context)
            require(!store.contains(clean) && AuctionStore(context).list().none {
                SubscriptionIdentity.sameProduct(it.optString("url"), clean)
            }) { SubscriptionIdentity.DUPLICATE_MESSAGE }
            // Auction-capable platforms are classified before creating any product record.
            val initial = if (market in setOf(Market.YAHOO, Market.RUTEN)) store.readPublicSnapshot(clean) else null
            if (productKind(market, initial) == RefreshKind.AUCTION) {
                val url = AuctionParser.canonicalUrl(clean)
                val auctions = AuctionStore(context)
                auctions.add(url)
                auctions.update(url) { entry -> initial?.title?.let { entry.put("title", it)
                    .put("isAdult", entry.optBoolean("isAdult") || AdultVisibility.adultText(it)) } }
                SubscriptionRefreshQueue.request(context, RefreshTarget(RefreshKind.AUCTION, url))
                return ImportedSubscription("已加入競拍追蹤，正在刷新競標資料。", RefreshKind.AUCTION)
            }
            val result = store.addPublicUrl(clean, initial)
            require(result.accepted) { result.message }
            return ImportedSubscription(result.message, RefreshKind.PRODUCT)
        }
        require(market.supportsShopSubscriptions) { "此平台目前尚未支援店家追蹤。" }
        val target = when (market) {
            Market.SHOPEE -> PublicShareLinkResolver.canonicalShopeeDestination(clean)
                ?: throw IllegalArgumentException("請提供蝦皮公開店家或商品網址，不是搜尋／登入頁。")
            Market.CAROUSELL -> PublicShareLinkResolver.canonicalCarousellDestination(clean)
                ?: throw IllegalArgumentException("請提供旋轉拍賣公開店家或商品網址。")
            Market.RUTEN -> market.rutenStorePage(clean)
            else -> clean
        }
        val prefs = context.getSharedPreferences("shop_subscriptions_v2", Context.MODE_PRIVATE)
        synchronized(SubscriptionMutations.lock) {
            require(!prefs.contains(target) && prefs.all.keys.none { SubscriptionIdentity.sameShop(it, target) }) {
                SubscriptionIdentity.DUPLICATE_MESSAGE
            }
            val saved = shopSubscriptionRecord(target, prefs.getString(target, null))
            saved.put("platform", market.name)
            if (saved.optString("name").isBlank()) saved.put("name", "待取得店家資料")
            prefs.edit().putString(target, saved.toString()).commit()
        }
        SubscriptionRefreshQueue.request(context, RefreshTarget(RefreshKind.SHOP, target))
        return ImportedSubscription("已訂閱店家，正在讀取名稱與建立商品基準。", RefreshKind.SHOP)
    }

    internal fun productKind(market: Market, snapshot: PublicProductSnapshot?): RefreshKind {
        if (market !in setOf(Market.YAHOO, Market.RUTEN)) return RefreshKind.PRODUCT
        require(snapshot?.auctionTypeKnown == true) { "尚未確認這個連結是競標或一般商品，請稍後重試；未加入商品訂閱。" }
        return if (snapshot.isAuction) RefreshKind.AUCTION else RefreshKind.PRODUCT
    }
}

internal data class ImportedSubscription(val message: String, val kind: RefreshKind)

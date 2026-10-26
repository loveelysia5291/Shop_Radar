package com.aykkdk.shop

import android.net.Uri
import org.json.JSONObject
import java.net.URI

enum class Market(val label: String, val host: String) {
    SHOPEE("蝦皮", "shopee.tw"), RUTEN("露天", "www.ruten.com.tw"),
    CAROUSELL("旋轉拍賣", "tw.carousell.com"), YAHOO("Y拍", "tw.bid.yahoo.com"),
    FACEBOOK("Facebook", "www.facebook.com"),
    BBOBO("跳蚤本舖", "www.bbbobo.com.tw"),
    IOPEN("iOPEN Mall", "mall.iopenmall.tw");

    val supportsShopSubscriptions: Boolean get() = this !in setOf(BBOBO, IOPEN)
    // Search availability is separate from stored subscription/link compatibility.
    val supportsSearch: Boolean get() = true

    fun search(keyword: String, min: String, max: String, yahooCategoryId: String? = null, page: Int = 1,
        carousellSortBy: String = "3", carousellCategory: String? = null,
        yahooState: String = "0", yahooSort: String = "", rutenCategoryId: String? = null, boboMall: String = "0",
        shopeeSort: String = "relevancy", shopeeMall: Boolean = false, shopeePreferred: Boolean = false,
        shopeeCondition: String = "", facebookSort: String = FacebookFilters.DEFAULT_SORT,
        facebookLocationId: String = FacebookLocation.DEFAULT_ID): String {
        if (this == IOPEN) return IOpenSearch.url(keyword)
        if (this == BBOBO) return BoboSearch.url(keyword, min, max, page, boboMall)
        if (this == FACEBOOK) return FacebookLocation.searchUrl(keyword, min, max, facebookSort, facebookLocationId)
        val selectedPage = page.coerceIn(1, 20)
        val b = Uri.Builder().scheme("https").authority(host)
        when (this) {
            SHOPEE -> {
                b.appendPath("search").appendQueryParameter("keyword", keyword).appendQueryParameter("page", (selectedPage - 1).toString())
                ShopeeFilters.parameters(shopeeSort).forEach { (key, value) -> b.appendQueryParameter(key, value) }
                ShopeeFilters.filterOptions(min, max, shopeeMall, shopeePreferred, shopeeCondition)
                    ?.let { b.appendQueryParameter("fe_filter_options", it) }
            }
            RUTEN -> {
                val category = RutenCategories.validId(rutenCategoryId)
                if (keyword.isBlank() && category != null) {
                    b.appendPath("category").appendPath(category).appendPath("list")
                } else {
                    b.appendPath("search").appendPath(keyword).appendPath("")
                    category?.let { b.appendQueryParameter("cateid", it) }
                }
                b.appendQueryParameter("sort", "new/dc").appendQueryParameter("pg", selectedPage.toString())
                if (min.isNotBlank() || max.isNotBlank()) b.appendQueryParameter("prc.now", "$min-$max")
            }
            CAROUSELL -> {
                // Carousell's public results do not expose stable numbered pages.
                // Its mobile-phone category has a different route and uses `search`.
                val categoryUrl = CarousellCategories.groups.asSequence().flatMap { sequenceOf(it) + it.children.asSequence() }
                    .firstOrNull { it.url == carousellCategory }?.url
                if (carousellCategory == "mobile-phones" || categoryUrl != null) {
                    val path = categoryUrl?.let { URI(it).path }
                        ?: "/categories/mobile-phones-gadgets-1091/mobile-phones-6406/"
                    path.trim('/').split('/').forEach { b.appendPath(it) }
                    b.appendPath("")
                        .appendQueryParameter("search", keyword).appendQueryParameter("tab", "marketplace")
                } else {
                    b.appendPath("search").appendPath(keyword)
                        .appendQueryParameter("t-search_query_source", "direct_search")
                }
                b.appendQueryParameter("sort_by", carousellSortBy)
                if (min.isNotBlank()) b.appendQueryParameter("price_start", min)
                if (max.isNotBlank()) b.appendQueryParameter("price_end", max)
            }
            YAHOO -> {
                val category = (YahooCategories.groups + YahooCategories.groups.flatMap { it.children })
                    .firstOrNull { it.cid == yahooCategoryId }
                if (keyword.isBlank() && category != null) {
                    b.appendPath("tw").appendPath("${category.name}-${category.cid}-category.html")
                } else {
                    b.appendPath("search").appendPath("auction").appendPath("product")
                        .appendQueryParameter("p", keyword)
                    if (!yahooCategoryId.isNullOrBlank()) b.appendQueryParameter("cid", yahooCategoryId).appendQueryParameter("clv", "1")
                }
                if (min.isNotBlank()) b.appendQueryParameter("minp", min)
                if (max.isNotBlank()) b.appendQueryParameter("maxp", max)
                if (selectedPage > 1) b.appendQueryParameter("pg", selectedPage.toString())
                YahooFilters.parameters(yahooState, yahooSort).forEach { (key, value) -> b.appendQueryParameter(key, value) }
            }
            FACEBOOK -> Unit // Built above with the selected, validated Facebook place ID.
            BBOBO -> Unit // Built above with the selected mall's route and SearchZone.
            IOPEN -> Unit // Built above using the supplied public search route.
        }
        return b.build().toString()
    }

    /** Public newest-first store page used by Ruten shop subscriptions. */
    fun shopPage(raw: String, page: Int = 1): String {
        if (this == RUTEN) return rutenStorePage(raw, page)
        require(accepts(raw)) { "店家網址不支援" }
        if (this == SHOPEE) {
            val store = PublicShareLinkResolver.canonicalShopeeDestination(raw)
                ?.takeUnless { isPublicProductUrl(it) } ?: error("請提供蝦皮公開店家網址。")
            // This only selects All products / Latest. Mobile continuation scrolls
            // the same document instead of advancing desktop page numbers.
            return "$store?page=0&sortBy=ctime&tab=0"
        }
        val uri = URI(raw)
        val replacements = when (this) {
            YAHOO -> mapOf("sort" to "-ptime", "pg" to page.toString())
            CAROUSELL -> mapOf("sort_by" to "3")
            else -> emptyMap()
        }
        val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() && it.substringBefore('=') !in replacements }
        return "https://${uri.host}${uri.rawPath}" + (params + replacements.map { "${it.key}=${it.value}" }).joinToString("&").let { if (it.isBlank()) "" else "?$it" }
    }

    fun rutenStorePage(usernameOrUrl: String, page: Int = 1): String {
        require(this == RUTEN) { "只有露天支援店家帳號訂閱" }
        val username = rutenStoreUsername(usernameOrUrl)
            ?: throw IllegalArgumentException("露天使用者名稱格式不正確。")
        // Username is validated to URL-safe characters above. Keep this builder
        // JVM-testable; android.net.Uri is not available in plain unit tests.
        return "https://$host/store/$username/list?sort=new%2Fdc&p=${page.coerceIn(1, 50)}"
    }

    /** Returns a validated store username from either the username or official store URL. */
    fun rutenStoreUsername(value: String): String? {
        if (this != RUTEN) return null
        val direct = value.trim().lowercase()
        if (direct.matches(Regex("[a-z0-9][a-z0-9_-]{1,63}"))) return direct
        val uri = publicUri(value) ?: return null
        if (uri.host?.lowercase() !in officialHosts) return null
        return uri.path.orEmpty().split('/').getOrNull(2)?.lowercase()
            ?.takeIf { uri.path.orEmpty().matches(Regex("/store/[a-zA-Z0-9_-]{2,64}/?(?:list)?/?")) }
    }

    /**
     * True for a public first-party page on this marketplace.  This deliberately
     * includes the official mobile web hosts so a link copied from an Android
     * browser can be used without being rewritten by the user first.
     */
    fun accepts(raw: String): Boolean = publicUri(raw)?.host?.lowercase() in officialHosts

    /** True only for a public *listing* URL, never a search/category/shop URL. */
    fun isPublicProductUrl(raw: String): Boolean {
        val uri = publicUri(raw) ?: return false
        if (uri.host?.lowercase() !in officialHosts) return false
        val path = uri.rawPath.orEmpty()
        val full = uri.rawPath.orEmpty() + "?" + uri.rawQuery.orEmpty()
        return when (this) {
            SHOPEE -> Regex("(?:^|/)(?:product/\\d+/\\d+|[^/]*-i\\.\\d+\\.\\d+)(?:/|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(path)
            // Ruten exposes both /item/<long-id> and /item/show?<id> links.
            RUTEN -> Regex("^/item/(?:show)?/?", RegexOption.IGNORE_CASE).containsMatchIn(path) &&
                Regex("\\d{8,}").containsMatchIn(full)
            CAROUSELL -> Regex("^/p/[^/]*\\d{5,}/?$", RegexOption.IGNORE_CASE).matches(path)
            YAHOO -> Regex("^/item/\\d{6,}/?$", RegexOption.IGNORE_CASE).matches(path)
            FACEBOOK -> Regex("^/marketplace/item/\\d{6,}/?$", RegexOption.IGNORE_CASE).matches(path)
            BBOBO -> Regex("^/shop/(?:\\d+|0_1)/Goods\\.asp$", RegexOption.IGNORE_CASE).matches(path) &&
                queryParameter(uri, "AID")?.matches(Regex("\\d+")) == true &&
                queryParameter(uri, "GID")?.matches(Regex("GTT\\d{8,}", RegexOption.IGNORE_CASE)) == true
            IOPEN -> Regex("^/\\d{6}/index\\.php$", RegexOption.IGNORE_CASE).matches(path) &&
                queryParameter(uri, "action") == "product_detail" &&
                queryParameter(uri, "prod_no")?.matches(Regex("P\\d+")) == true
        }
    }

    /**
     * Gives one stable first-party URL for a public product link.  Tracking
     * parameters and fragments are intentionally discarded so desktop/mobile
     * copies of the same listing do not create duplicate subscriptions.
     */
    fun normalizePublicProductUrl(raw: String): String? {
        val uri = publicUri(raw) ?: return null
        if (!isPublicProductUrl(raw)) return null
        val canonicalHost = canonicalHost
        val path = if (this == BBOBO) {
            if (Regex("^/shop/0_1/", RegexOption.IGNORE_CASE).containsMatchIn(uri.path)) "/shop/0_1/Goods.asp" else "/shop/0/Goods.asp"
        } else uri.rawPath.orEmpty().ifBlank { return null }
        // Some platforms identify products in the query, not the path.
        // Keep only the essential identifier fields; discard share tracking.
        val query = when (this) {
            RUTEN -> cleanRutenQuery(uri.rawQuery)
            BBOBO -> "AID=${queryParameter(uri, "AID")}&GID=${queryParameter(uri, "GID")?.uppercase()}"
            IOPEN -> "action=product_detail&prod_no=${queryParameter(uri, "prod_no")}"
            else -> null
        }
        return buildString {
            append("https://").append(canonicalHost).append(path)
            if (!query.isNullOrBlank()) append('?').append(query)
        }
    }

    private fun publicUri(raw: String): URI? = runCatching {
        val uri = URI(raw.trim())
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
            uri.host.isNullOrBlank() || uri.port != -1) null else uri
    }.getOrNull()

    private fun queryParameter(uri: URI, key: String): String? = uri.rawQuery.orEmpty().split('&')
        .firstOrNull { it.substringBefore('=').equals(key, ignoreCase = true) }?.substringAfter('=', "")

    private fun cleanRutenQuery(rawQuery: String?): String? = rawQuery
        ?.split('&')
        ?.filter { part ->
            val name = part.substringBefore('=').lowercase()
            name.isNotBlank() && name !in TRACKING_QUERY_NAMES && !name.startsWith("utm_")
        }
        ?.joinToString("&")
        ?.takeIf { it.isNotBlank() }

    private val officialHosts: Set<String>
        get() = when (this) {
            SHOPEE -> setOf("shopee.tw", "www.shopee.tw", "m.shopee.tw")
            RUTEN -> setOf("www.ruten.com.tw", "ruten.com.tw", "m.ruten.com.tw")
            CAROUSELL -> setOf("tw.carousell.com", "m.tw.carousell.com")
            YAHOO -> setOf("tw.bid.yahoo.com", "m.tw.bid.yahoo.com")
            FACEBOOK -> setOf("www.facebook.com", "m.facebook.com", "web.facebook.com")
            BBOBO -> setOf("www.bbbobo.com.tw", "bbbobo.com.tw")
            IOPEN -> setOf("mall.iopenmall.tw")
        }

    private val canonicalHost: String
        get() = host

    companion object {
        private val TRACKING_QUERY_NAMES = setOf("fbclid", "gclid", "ref", "referrer", "tracking", "source")

        val searchMarkets: List<Market> = values().filter { it.supportsSearch }

        /** Restored selections must not resurrect a retired search platform. */
        fun searchSelection(label: String): Market = searchMarkets.firstOrNull { it.label == label } ?: CAROUSELL

        /** Finds the marketplace for any accepted first-party public page. */
        fun fromPublicUrl(raw: String): Market? = values().firstOrNull { it.accepts(raw) }

        /** Finds the marketplace only when [raw] is a direct public item link. */
        fun fromPublicProductUrl(raw: String): Market? = values().firstOrNull { it.isPublicProductUrl(raw) }

        /** Both older shop records (enum names) and product records (labels) remain readable. */
        fun fromSubscription(storedPlatform: String, url: String): Market? =
            fromPublicUrl(url) ?: values().firstOrNull { it.name == storedPlatform || it.label == storedPlatform }

        /** Convenience for UI callers validating a pasted product URL. */
        fun normalizePublicProductUrl(raw: String): String? =
            fromPublicProductUrl(raw)?.normalizePublicProductUrl(raw)
    }
}

data class Product(val title: String, val price: Long, val platform: String, val url: String,
    val image: String = "", val listedAt: String = "", val shop: String = "", val isAdvertisement: Boolean = false,
    val isAuction: Boolean = false, val auctionEndsAt: Long = 0L, val highestBidder: String = "",
    val auctionPriceKnown: Boolean = false, val auctionCancelled: Boolean = false, val isAdult: Boolean = false,
    val listedAtEpochMillis: Long = 0L, val category: String = "",
    val auctionServerAt: Long = 0L, val auctionBidCount: Int = -1, val auctionEnded: Boolean = false) {
    fun json() = JSONObject().put("title", title).put("price", price).put("platform", platform)
        .put("url", url).put("image", image).put("listedAt", listedAt).put("shop", shop).put("isAdvertisement", isAdvertisement)
        .put("isAuction", isAuction).put("auctionEndsAt", auctionEndsAt).put("highestBidder", highestBidder)
        .put("auctionPriceKnown", auctionPriceKnown).put("auctionCancelled", auctionCancelled).put("isAdult", isAdult).put("listedAtEpochMillis", listedAtEpochMillis).put("category", category)
        .put("auctionServerAt", auctionServerAt).put("auctionBidCount", auctionBidCount).put("auctionEnded", auctionEnded)
    companion object {
        fun parse(o: JSONObject): Product? {
            val market = Market.values().firstOrNull { it.label == o.optString("platform") } ?: return null
            val rawUrl = o.optString("url")
            val url = if (market == Market.FACEBOOK && FacebookCardReference.parse(rawUrl) != null) rawUrl
                else market.normalizePublicProductUrl(rawUrl) ?: return null
            val listedAt = o.optString("listedAt").trim()
            // Keep listings when a marketplace has not provided a timestamp.  The UI
            // explicitly marks these as "無時間" rather than inventing a search time.
            if (!o.has("price") || o.optLong("price", -1) < 0 ||
                o.optString("title").isBlank()) return null
            return Product(ShopText.title(o.getString("title")).take(240), o.getLong("price"), market.label, url,
                o.optString("image").takeIf { it.startsWith("https://") }.orEmpty(), listedAt, ShopText.shop(o.optString("shop")), o.optBoolean("isAdvertisement"),
                o.optBoolean("isAuction"), o.optLong("auctionEndsAt"), o.optString("highestBidder"),
                o.optBoolean("auctionPriceKnown"), o.optBoolean("auctionCancelled"), o.optBoolean("isAdult") || AdultVisibility.adultText(o.optString("title")) || AdultVisibility.adultText(o.optString("category")), o.optLong("listedAtEpochMillis"), o.optString("category").trim().take(120),
                o.optLong("auctionServerAt"), o.optInt("auctionBidCount", -1), o.optBoolean("auctionEnded"))
        }
    }
}

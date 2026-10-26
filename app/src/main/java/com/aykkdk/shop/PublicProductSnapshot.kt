package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** Unknown is never sold. Only the requested product can supply stock evidence. */
data class PublicProductSnapshot(val status: String = "unknown", val price: Long? = null,
    val title: String? = null, val error: String? = null, val isAuction: Boolean = false,
    val shop: String? = null, val listedAt: String? = null, val category: String? = null,
    val stockQuantity: Long? = null, val image: String? = null, val resolvedUrl: String? = null,
    val auctionTypeKnown: Boolean = false, val listedAtEpochMillis: Long? = null)

object PublicProductParser {
    fun parse(url: String, code: Int, html: String): PublicProductSnapshot {
        if (code == 410 && Market.fromPublicProductUrl(url) == Market.CAROUSELL) return PublicProductSnapshot(status = "removed")
        if (code != 200) return PublicProductSnapshot(error = "HTTP $code，暫時無法確認庫存（不代表售出）")
        if (html.isBlank()) return PublicProductSnapshot(error = "商品頁沒有提供可確認的資料")
        val market = Market.fromPublicProductUrl(url) ?: return PublicProductSnapshot(error = "網址不支援")
        if (market == Market.BBOBO) return BoboProductSnapshot.parse(url, html).let { snapshot ->
            if (snapshot.title == null) snapshot else snapshot.copy(image = verifiedMetadata(url, html)?.image)
        }
        if (market == Market.RUTEN) return PublicProductSnapshot(error = "露天只使用公開商品資料端點確認庫存；不讀取商品頁備援")
        val metadata = verifiedMetadata(url, html)
        val id = Regex("\\d{5,}").findAll(URI(url).path).lastOrNull()?.value.orEmpty()
        var yahooMetadata: PublicProductSnapshot? = null
        if (market == Market.YAHOO) {
            val raw = scripts(html).firstOrNull { it.first.contains(Regex("id=[\"']isoredux-data[\"']", RegexOption.IGNORE_CASE)) }?.second
            val item = runCatching { JSONObject(raw ?: "").optJSONObject("item") }.getOrNull()
            if (item != null && item.optString("id") == id) {
                val price = listOf(item.optJSONObject("bidInfo")?.opt("currentPrice"), item.opt("price"), item.opt("buyPrice"))
                    .firstNotNullOfOrNull { amount(it) }
                val listingTime = yahooListingTime(item)
                yahooMetadata = PublicProductSnapshot(price = price, title = item.optString("title").takeIf { it.isNotBlank() }, isAuction = item.optInt("type") == 2,
                    image = ProductThumbnail.url(item.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("sm")?.optString("src").orEmpty(), url)
                        ?: metadata?.image,
                    error = "商品仍可讀取，但平台未提供可確認的庫存狀態", auctionTypeKnown = item.optInt("type") in setOf(1, 2),
                    listedAt = listingTime?.first, listedAtEpochMillis = listingTime?.second)
                if (yahooMetadata.isAuction) return yahooMetadata
            }
        }
        val products = scripts(html).filter { it.first.contains(Regex("type=[\"']application/ld\\+json[\"']", RegexOption.IGNORE_CASE)) }
            .flatMap { (_, raw) -> runCatching { productNodes(JSONTokener(raw).nextValue()) }.getOrDefault(emptyList()) }
        val product = products.firstOrNull { item ->
            val declaredUrl = item.optString("url").ifBlank { item.optString("@id") }
                .ifBlank { item.optJSONObject("offers")?.optString("url").orEmpty() }
            if (declaredUrl.isNotBlank()) sameProductUrl(declaredUrl, url)
            else id.isNotBlank() && listOf("sku", "productID").any { item.optString(it) == id }
        } ?: return yahooMetadata ?: metadata ?: PublicProductSnapshot(error = "未取得這件商品的明確庫存資料；保留上次狀態")
        val offers = when (val value = product.opt("offers")) {
            is JSONObject -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            else -> emptyList()
        }
        val offer = offers.singleOrNull()
        val offerUrl = offer?.optString("url").orEmpty()
        val identifiedOffer = offer?.takeIf { offerUrl.isBlank() || sameProductUrl(offerUrl, url) }
        val availability = identifiedOffer?.optString("availability").orEmpty().substringAfterLast('/').substringAfterLast('#')
        val inventory = if (!product.has("hasVariant") && !product.has("isVariantOf")) ProductStock.inventory(identifiedOffer) else null
        val status = when (availability.lowercase()) {
            "instock", "limitedavailability" -> if (inventory == 0L) "unknown" else "active"
            "soldout", "outofstock" -> if (inventory != null && inventory > 0) "unknown" else "sold"
            "" -> when { inventory == null -> "unknown"; inventory > 0 -> "active"; else -> "sold" }
            else -> "unknown"
        }
        val price = if (identifiedOffer?.optString("priceCurrency") == "TWD") amount(identifiedOffer.opt("price")) else null
        return PublicProductSnapshot(status, price ?: yahooMetadata?.price,
            product.optString("name").takeIf { it.isNotBlank() }?.take(240) ?: yahooMetadata?.title ?: metadata?.title,
            if (status == "unknown") "平台未公開明確庫存狀態；保留上次狀態" else null,
            stockQuantity = inventory?.takeIf { status != "unknown" },
            image = ProductThumbnail.fromJson(product.opt("image"), url) ?: metadata?.image, resolvedUrl = metadata?.resolvedUrl,
            auctionTypeKnown = yahooMetadata?.auctionTypeKnown == true,
            listedAt = yahooMetadata?.listedAt ?: listOf("datePosted", "datePublished").firstNotNullOfOrNull { publishedTime(product.optString(it)) },
            listedAtEpochMillis = yahooMetadata?.listedAtEpochMillis)
    }
    internal fun yahooListingTime(item: JSONObject, now: Long = System.currentTimeMillis()): Pair<String, Long>? {
        val raw = item.optString("startTime").ifBlank { item.optJSONObject("bidInfo")?.optString("startTime").orEmpty() }
        if (!Regex("^\\d{10}(?:\\d{3})?$").matches(raw)) return null
        val at = raw.toLongOrNull()?.let { if (raw.length == 10) it * 1000 else it } ?: return null
        if (at < 1_072_915_200_000L || at > now + 60_000) return null
        val text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.TAIWAN).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Taipei")
        }.format(java.util.Date(at))
        return text to at
    }
    /** Published dates only: neither dateModified nor availability implies listing time. */
    internal fun publishedTime(raw: String): String? {
        val at = ShopCheckpoint.millis(raw, 0) ?: return null
        if (at < 1_072_915_200_000L || at > System.currentTimeMillis() + 60_000) return null
        return java.text.SimpleDateFormat(if (raw.length <= 10) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm:ss", java.util.Locale.TAIWAN)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Taipei") }.format(java.util.Date(at))
    }
    internal fun sameProductUrl(first: String, second: String): Boolean {
        val left = Market.normalizePublicProductUrl(first)?.let(::URI) ?: return false
        val right = Market.normalizePublicProductUrl(second)?.let(::URI) ?: return false
        if (left.host != right.host) return false
        val market = Market.fromPublicProductUrl(second)
        fun identity(uri: URI): String? = when (market) {
            Market.CAROUSELL -> Regex("[0-9]{5,20}(?=/?$)").find(uri.path)?.value
            Market.SHOPEE -> Regex("/product/([0-9]+)/([0-9]+)/?$").find(uri.path)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
                ?: Regex("-i\\.([0-9]+)\\.([0-9]+)/?$").find(uri.path)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
            else -> null
        }
        val id = identity(left)
        if (id != null) return id == identity(right)
        return left.path.trimEnd('/') == right.path.trimEnd('/') && left.query == right.query
    }

    /** A name/image can be public even when inventory is not; never infer stock from Open Graph. */
    private fun verifiedMetadata(url: String, html: String): PublicProductSnapshot? {
        if (Market.fromPublicProductUrl(url) !in setOf(Market.SHOPEE, Market.CAROUSELL)) return null
        val meta = mutableMapOf<String, String>()
        val declared = mutableListOf<String>()
        for (tag in Regex("<(?:meta|link)\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(html)) {
            val attrs = Regex("([a-zA-Z][a-zA-Z0-9:_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
                .findAll(tag.groupValues[1]).associate { it.groupValues[1].lowercase() to
                    decodeMetadata(if (it.groupValues[2].isNotEmpty()) it.groupValues[2] else it.groupValues[3]) }
            val key = attrs["property"].orEmpty().lowercase()
            attrs["content"]?.let { meta[key] = it }
            if (key == "og:url") attrs["content"]?.let(declared::add)
            if ("canonical" in attrs["rel"].orEmpty().lowercase().split(Regex("\\s+"))) attrs["href"]?.let(declared::add)
        }
        if (declared.isEmpty() || declared.any { !sameProductUrl(it, url) }) return null
        val title = meta["og:title"]?.replace(Regex("\\s*[|｜]\\s*(?:蝦皮購物|Shopee|旋轉拍賣|Carousell).*$", RegexOption.IGNORE_CASE), "")?.trim()
            ?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("登入", "login", "just a moment...", "access denied") }?.take(240)
            ?: return null
        val image = meta["og:image"]?.let { ProductThumbnail.url(it, url) }
        return PublicProductSnapshot(title = title, image = image, resolvedUrl = Market.normalizePublicProductUrl(declared.first()),
            error = "已取得商品名稱；平台未提供可確認的庫存狀態")
    }
    private fun decodeMetadata(raw: String): String = raw.replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    private fun amount(value: Any?): Long? = value?.toString()?.replace(",", "")?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
    private fun scripts(html: String) = Regex("<script\\b([^>]*)>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
        .findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()
    private fun productNodes(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> (0 until value.length()).flatMap { productNodes(value.opt(it)) }
        is JSONObject -> {
            val type = value.opt("@type")
            val isProduct = type == "Product" || (type is JSONArray && (0 until type.length()).any { type.optString(it) == "Product" })
            if (isProduct) listOf(value) else productNodes(value.opt("@graph")) + productNodes(value.opt("mainEntity"))
        }
        else -> emptyList()
    }
}

object ProductSubscriptionDecision {
    fun migrate(item: JSONObject) {
        if (item.optInt("stockEvidenceVersion") < 2 && item.optString("status") == "sold") {
            item.put("status", "unknown").put("lastCheckError", "舊版庫存標記待重新確認")
        }
        item.put("stockEvidenceVersion", 2)
    }
    fun apply(item: JSONObject, snapshot: PublicProductSnapshot, now: Long): ProductSubscriptionAlert? {
        migrate(item)
        val oldStatus = item.optString("status", "unknown")
        val oldPrice = item.optLong("price", -1)
        val title = snapshot.title ?: item.optString("title")
        val shop = item.optString("shop").ifBlank { item.optString("platform") }
        val alert = when {
            snapshot.status == "sold" && oldStatus != "sold" -> ProductSubscriptionAlert(shop, title, "已售出／缺貨", item.optBoolean("isAdult"))
            snapshot.status == "removed" && oldStatus != "removed" -> ProductSubscriptionAlert(shop, title, "已下架／失效", item.optBoolean("isAdult"))
            snapshot.status !in setOf("sold", "removed") && snapshot.price != null && oldPrice >= 0 && snapshot.price < oldPrice -> ProductSubscriptionAlert(shop, title, "降價", item.optBoolean("isAdult"))
            else -> null
        }
        item.put("lastAttemptAt", now)
        if (snapshot.status != "unknown") {
            item.put("status", snapshot.status).put("checkedAt", now)
            if (snapshot.stockQuantity != null) item.put("stockQuantity", snapshot.stockQuantity)
            else item.remove("stockQuantity")
        }
        snapshot.price?.let { item.put("price", it) }
        snapshot.title?.let { item.put("title", it); if (AdultVisibility.adultText(it)) item.put("isAdult", true) }
        ProductThumbnail.url(snapshot.image.orEmpty())?.let { item.put("image", it) }
        snapshot.resolvedUrl?.takeIf { PublicProductParser.sameProductUrl(it, item.optString("url")) }
            ?.let { item.put("resolvedUrl", it) }
        snapshot.shop?.takeIf { it.isNotBlank() }?.let { item.put("shop", it) }
        snapshot.listedAt?.takeIf { it.isNotBlank() }?.let { text ->
            val unchanged = text == item.optString("listedAt")
            val at = if (unchanged && item.optLong("listedAtEpochMillis") > 0) item.optLong("listedAtEpochMillis")
                else snapshot.listedAtEpochMillis ?: ShopCheckpoint.millis(text, now)
            item.put("listedAt", text)
            if (at != null && at >= 1_072_915_200_000L && at <= now + 60_000) item.put("listedAtEpochMillis", at)
            else item.remove("listedAtEpochMillis")
        }
        snapshot.category?.takeIf { it.isNotBlank() }?.let {
            item.put("category", it)
            if (AdultVisibility.adultText(it)) item.put("isAdult", true)
        }
        if (snapshot.error == null) item.remove("lastCheckError") else item.put("lastCheckError", snapshot.error)
        return alert
    }
}

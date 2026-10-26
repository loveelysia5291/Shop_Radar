package com.example.shopradar

import java.net.URI

/** Read the site's explicit stock count, scoped to the identified product, never recommendations. */
internal object BoboProductSnapshot {
    fun parse(url: String, html: String): PublicProductSnapshot {
        val clean = Market.BBOBO.normalizePublicProductUrl(url)
            ?: return PublicProductSnapshot(error = "商品網址格式無法辨識")
        val source = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        val description = element(source, "div", "goods-description2")
            ?: run {
                // This site's successful product response replaces sold items with this explicit message.
                // Do not infer sale from transport errors, scripts, or a recommendation's text.
                val message = text(source.substringBefore("您可能喜歡的商品"))
                if (Regex("查無此商品資料[！!。]?").containsMatchIn(message))
                    return PublicProductSnapshot(status = "sold")
                val metadata = BoboProductMetadata.parse(clean, source)
                return PublicProductSnapshot(error = "未取得商品庫存區塊；保留上次狀態",
                    shop = metadata?.shop.orEmpty(), listedAt = metadata?.listedAt.orEmpty(),
                    category = metadata?.category.orEmpty())
            }
        val metadataBlock = element(description, "div", "gd-otinfo-list").orEmpty()
        val metadata = BoboProductMetadata.parse(clean, metadataBlock)
            ?: return PublicProductSnapshot(error = "商品編號不符或資料不完整；保留上次狀態")
        val aid = URI(clean).query.split('&').first { it.startsWith("AID=") }.substringAfter('=')
        val shopTag = Regex("<a\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(metadataBlock)
            .firstOrNull { hasClass(it.value, "goshop-url") }?.value.orEmpty()
        if (attribute(shopTag, "aid") != aid) return PublicProductSnapshot(error = "商品店舖編號不符；保留上次狀態")
        val title = element(description, "h2", "gd-name-txt")?.let(::text)?.takeIf { it.isNotBlank() }?.take(240)
        val purchase = element(description, "div", "good-addcar").orEmpty()
        val stock = element(purchase, "div", "webad-in-goods")?.let(::text)
            ?.let { Regex("^庫存數\\s*[:：]\\s*([\\d,]+)$").matchEntire(it)?.groupValues?.get(1) }
            ?.replace(",", "")?.toLongOrNull()
        val price = element(description, "div", "gd-addinfo-list")
            ?.let { element(it, "div", "pr-price") }?.let(::text)
            ?.let { Regex("(?:NT\\$|\\$)\\s*([\\d,]+)").find(it)?.groupValues?.get(1) }
            ?.replace(",", "")?.toLongOrNull()
        return PublicProductSnapshot(status = when {
            stock == null -> "unknown"
            stock > 0 -> "active"
            else -> "sold"
        }, price = price, title = title,
            error = if (stock == null) "平台未提供這件商品的明確庫存數；保留上次狀態" else null,
            shop = metadata.shop, listedAt = metadata.listedAt, category = metadata.category,
            stockQuantity = stock)
    }

    // Balance only the requested tag so nested DIVs cannot leak into adjacent/recommended products.
    private fun element(html: String, tag: String, cssClass: String): String? {
        val tokens = Regex("</?$tag\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).iterator()
        while (tokens.hasNext()) {
            val open = tokens.next()
            if (open.value.startsWith("</") || !hasClass(open.value, cssClass)) continue
            var depth = 1
            while (tokens.hasNext()) {
                val token = tokens.next()
                depth += if (token.value.startsWith("</")) -1 else 1
                if (depth == 0) return html.substring(open.range.last + 1, token.range.first)
            }
            return null
        }
        return null
    }
    private fun attribute(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(2)
    private fun hasClass(tag: String, value: String) = attribute(tag, "class")?.split(Regex("\\s+"))?.contains(value) == true
    private fun text(html: String) = html.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()
}

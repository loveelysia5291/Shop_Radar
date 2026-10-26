package com.aykkdk.shop

/** Desktop public cards expose their real href before a user asks to open them. */
internal object FacebookWebPolicy {
    fun desktopUserAgent(webViewAgent: String): String = webViewAgent
        .replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)")
        .replace(Regex("\\s+Version/\\S+"), "")
        .replace(Regex("\\s+Mobile\\b"), "")
}

internal data class FacebookListingTime(val text: String, val epochMillis: Long)

/** Bounded process-local metadata, not an inventory refresh or a persistent account cache. */
internal object FacebookListingMetadata {
    private data class Entry(val time: FacebookListingTime?, val readAt: Long)
    private val dates = linkedMapOf<String, Entry>()
    private const val SUCCESS_TTL = 60 * 60_000L
    private const val FAILURE_TTL = 5 * 60_000L
    const val MAX_READERS = 2
    const val READ_MILLIS = 8_000L

    fun directUrl(product: Product): String? {
        if (product.platform == Market.IOPEN.label)
            return Market.fromPublicProductUrl(product.url)?.normalizePublicProductUrl(product.url)
                ?.takeIf { Market.fromPublicProductUrl(it)?.label == product.platform }
        if (product.platform != Market.FACEBOOK.label) return null
        return Market.FACEBOOK.normalizePublicProductUrl(product.url) ?: FacebookResolvedLinks.find(product.url)
    }

    @Synchronized fun needsRead(product: Product, now: Long): Boolean {
        if (product.listedAt.isNotBlank()) return false
        val url = directUrl(product) ?: return false
        val saved = dates[url] ?: return true
        return now - saved.readAt >= if (saved.time == null) FAILURE_TTL else SUCCESS_TTL
    }

    @Synchronized fun complete(url: String, time: FacebookListingTime?, now: Long) {
        val canonical = Market.normalizePublicProductUrl(url) ?: return
        val accepted = time?.takeIf { it.text.isNotBlank() && it.text.length <= 140 &&
            it.epochMillis >= 0 && it.epochMillis <= now + 60_000 }
        dates.remove(canonical)
        dates[canonical] = Entry(accepted, now)
        while (dates.size > 160) dates.remove(dates.keys.first())
    }

    @Synchronized fun apply(product: Product, now: Long): Product {
        val url = directUrl(product) ?: return product
        val canonical = product.copy(url = url)
        if (product.listedAt.isNotBlank()) return canonical
        val saved = dates[url] ?: return canonical
        if (now - saved.readAt >= SUCCESS_TTL) return canonical
        val time = saved.time ?: return canonical
        return canonical.copy(listedAt = time.text, listedAtEpochMillis = time.epochMillis)
    }
}

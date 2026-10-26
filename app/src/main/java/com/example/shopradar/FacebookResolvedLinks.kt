package com.example.shopradar

/** Session-only cache of real public item URLs; never derive an ID from a photo or UI action handle. */
internal object FacebookResolvedLinks {
    private val urls = linkedMapOf<String, String>()
    @Synchronized fun remember(reference: String, actual: String) {
        if (FacebookCardReference.parse(reference) == null) return
        val url = Market.FACEBOOK.normalizePublicProductUrl(actual) ?: return
        urls[reference] = url
        while (urls.size > 160) urls.remove(urls.keys.first())
    }
    @Synchronized fun find(reference: String): String? = urls[reference]
}

internal object FacebookProductMerge {
    fun merge(old: Product?, fresh: Product): Product = if (old == null) fresh else fresh.copy(
        image = fresh.image.ifBlank { old.image },
        listedAt = fresh.listedAt.ifBlank { old.listedAt },
        listedAtEpochMillis = if (fresh.listedAt.isBlank() || fresh.listedAt == old.listedAt)
            old.listedAtEpochMillis.takeIf { it > 0 } ?: fresh.listedAtEpochMillis else fresh.listedAtEpochMillis
    )
}

package com.example.shopradar

/** One immutable checkpoint for the whole check, including every source/UI page. */
internal data class SubscriptionNewItemsPaging(
    val checkpoint: Long,
    val seen: Set<String>,
    val collected: List<Product> = emptyList(),
    val pages: List<List<Product>> = emptyList(),
    val pending: List<Product> = emptyList(),
    val currentPage: Int = 0,
    val collecting: Boolean = true,
    val reachedOld: Boolean = false,
    val awaitingTime: Boolean = false,
    val error: String? = null
) {
    val hasNew get() = collected.isNotEmpty()
    val canPrevious get() = !collecting && currentPage > 0
    val canNext get() = !collecting && (currentPage < pages.lastIndex || pending.isNotEmpty() || !reachedOld && error == null)
    val displayed get() = pages.getOrNull(currentPage).orEmpty()

    /** Stop at the first dated boundary. Unknown dates cannot prove "no new items". */
    fun observe(incoming: List<Product>): SubscriptionNewItemsPaging {
        if (reachedOld) return this
        val all = collected.associateByTo(linkedMapOf()) { it.url }
        var boundary = false
        var missingTime = false
        for (product in incoming) {
            if (product.isAdvertisement) continue
            val previous = all[product.url]
            val item = if (previous != null && previous.listedAtEpochMillis > 0)
                product.copy(listedAtEpochMillis = previous.listedAtEpochMillis) else product
            val at = ShopCheckpoint.timestamp(item)
            if (item.url in seen || checkpoint > 0 && at != null && at <= checkpoint) {
                boundary = true; break
            }
            if (checkpoint > 0 && at == null) { missingTime = true; break }
            all[item.url] = item
        }
        val published = pages.flatten().map { it.url }.toSet()
        return copy(collected = all.values.toList(), pending = all.values.filter { it.url !in published },
            reachedOld = boundary, awaitingTime = missingTime)
    }

    /** At five seconds reserve one real, undisplayed item unless the scan is complete. */
    fun publish(): SubscriptionNewItemsPaging {
        if (!collecting || pending.isEmpty() && !reachedOld && error == null) return this
        // A retry may only confirm the boundary after all known new items were
        // already published. Do not create a phantom, empty next page.
        if (pending.isEmpty() && pages.isNotEmpty()) return copy(collecting = false)
        val ordered = ShopNewItems.newestFirst(pending)
        val batch = if (!reachedOld && error == null && ordered.size > 1) ordered.dropLast(1) else ordered
        return copy(pages = pages + listOf(batch), pending = ordered.drop(batch.size),
            currentPage = pages.size, collecting = false)
    }

    fun next(): SubscriptionNewItemsPaging = when {
        !canNext -> this
        currentPage < pages.lastIndex -> copy(currentPage = currentPage + 1)
        else -> copy(collecting = true, error = null)
    }
    fun previous(): SubscriptionNewItemsPaging = if (canPrevious) copy(currentPage = currentPage - 1) else this
    fun fail(message: String) = copy(error = message).publish()
}

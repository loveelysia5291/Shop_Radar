package com.example.shopradar

/** A share can include a caption, but must identify exactly one supported HTTPS link. */
internal object SubscriptionShareInput {
    fun parse(text: String?): String? {
        if (text == null || text.length > 32_768) return null
        val links = Regex("https://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ')', ']', '}', '。', '，', '；', '）', '】') }
            .distinct().toList()
        val link = links.singleOrNull() ?: return null
        return link.takeIf { PublicShareLinkResolver.validatedShareUrl(it) != null || Market.fromPublicUrl(it) != null }
    }
}

internal data class SharedSubscriptionRequest(val id: String, val url: String)

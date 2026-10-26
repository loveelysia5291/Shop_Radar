package com.aykkdk.shop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import javax.net.ssl.HttpsURLConnection

/** Explicit labels from the requested public product, never from recommendations or its ID. */
internal object BoboProductMetadata {
    data class Details(val shop: String, val category: String, val listedAt: String)

    fun parse(url: String, html: String): Details? {
        val clean = Market.BBOBO.normalizePublicProductUrl(url) ?: return null
        val gid = URI(clean).query.split('&').firstOrNull { it.startsWith("GID=") }?.substringAfter('=') ?: return null
        val text = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&#160;", " ")
            .replace("&amp;", "&").substringBefore("您可能喜歡的商品").substringBefore("購物流程及注意事項")
        val block = Regex("商品所在\\s*[:：]\\s*([\\s\\S]*?)上架時間\\s*[:：]\\s*(\\d{4}/\\d{1,2}/\\d{1,2})(?![\\d/])").find(text) ?: return null
        val identity = Regex("商品編號\\s*[:：]\\s*(GTT\\d{8,})", RegexOption.IGNORE_CASE).find(block.value)?.groupValues?.get(1)
        if (!gid.equals(identity, ignoreCase = true)) return null
        fun field(label: String, next: String) = Regex("$label\\s*[:：]\\s*([\\s\\S]*?)$next\\s*[:：]")
            .find(block.value)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(120).orEmpty()
        val date = block.groupValues[2]
        if (ShopCheckpoint.millis(date, 0) == null) return null
        return Details(field("商品所在", "商品分類"), field("商品分類", "商品編號"), date)
    }

    /** Share slots across initial page batches so date reads never exceed four concurrent requests. */
    suspend fun enrich(products: List<Product>, slots: Semaphore = Semaphore(4), onProduct: suspend (Product) -> Unit) = coroutineScope {
        products.filter { it.platform == Market.BBOBO.label && (it.category.isBlank() || it.listedAt.isBlank() || it.shop.isBlank()) }
            .distinctBy { it.url }.map { product -> async {
                slots.withPermit {
                    val details = withContext(Dispatchers.IO) {
                        ensureActive()
                        try { read(product.url) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }
                    ensureActive()
                    if (details != null) onProduct(product.copy(shop = details.shop.ifBlank { product.shop },
                        category = details.category.ifBlank { product.category }, listedAt = details.listedAt,
                        listedAtEpochMillis = ShopCheckpoint.millis(details.listedAt, 0) ?: 0,
                        isAdult = product.isAdult || AdultVisibility.adultText(details.category)))
                }
            } }.awaitAll()
    }

    private fun read(url: String): Details? {
        val clean = Market.BBOBO.normalizePublicProductUrl(url) ?: return null
        val connection = URL(clean).openConnection() as HttpsURLConnection
        return try {
            connection.connectTimeout = 4000; connection.readTimeout = 4000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { it.readBytesLimited(512_000) } ?: return null
            parse(clean, decode(bytes, connection.contentType))
        } finally { connection.disconnect() }
    }

    internal fun decode(bytes: ByteArray, contentType: String?): String {
        val pattern = Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
        val charset = pattern.find(contentType.orEmpty())?.groupValues?.get(1)
            ?: pattern.find(String(bytes.take(4096).toByteArray(), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return String(bytes, runCatching { Charset.forName(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = read(buffer)
            if (size < 0) return output.toByteArray()
            if (output.size() + size > limit) return null
            output.write(buffer, 0, size)
        }
    }
}

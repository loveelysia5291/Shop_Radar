package com.example.shopradar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Store names come from an identified public store page, not another product's seller label. */
internal object PublicShopMetadata {
    fun needsRead(url: String, name: String, market: Market): Boolean {
        if (name.isBlank() || name == market.label || name.startsWith("待取得")) return true
        if (market !in setOf(Market.CAROUSELL, Market.SHOPEE)) return false
        val username = canonical(url)?.let { URI(it).path.trim('/').removePrefix("u/") } ?: return false
        return name == username || market == Market.CAROUSELL && name == "${username}在旋轉拍賣上"
    }

    private fun canonical(raw: String): String? = when (Market.fromPublicUrl(raw)) {
        Market.SHOPEE -> PublicShareLinkResolver.canonicalShopeeDestination(raw)
        Market.CAROUSELL -> PublicShareLinkResolver.canonicalCarousellDestination(raw)
        else -> null
    }?.takeUnless { Market.fromPublicProductUrl(it) != null }

    private fun sameStore(first: String, second: String): Boolean {
        val a = canonical(first)?.let(::URI) ?: return false
        val b = canonical(second)?.let(::URI) ?: return false
        return a.host == b.host && a.path.trim('/').removePrefix("u/") == b.path.trim('/').removePrefix("u/")
    }

    fun parse(url: String, html: String): String? {
        if (canonical(url) == null) return null
        val metadata = Regex("<meta\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(html).map { tag ->
            Regex("([a-zA-Z][a-zA-Z0-9:_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
                .findAll(tag.groupValues[1]).associate { it.groupValues[1].lowercase() to
                    (if (it.groupValues[2].isNotEmpty()) it.groupValues[2] else it.groupValues[3])
                        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&") }
        }.toList()
        val declared = metadata.filter { it["property"]?.lowercase() == "og:url" }.mapNotNull { it["content"] }
        if (declared.any { !sameStore(it, url) }) return null
        // Public mobile stores may omit og:url. A store-qualified document title
        // still identifies the name on the exact validated store route we fetched.
        val shopeeTitle = if (Market.fromPublicUrl(url) == Market.SHOPEE) {
            Regex("<title\\b[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)
                ?.groupValues?.get(1)?.let(::headingText)?.let { title ->
                    Regex("^(.+?)[,，]\\s*線上商店\\s*[|｜]\\s*(?:蝦皮購物|Shopee)$", RegexOption.IGNORE_CASE)
                        .matchEntire(title)?.groupValues?.get(1)?.trim()?.take(120)
                }
        } else null
        if (Market.fromPublicUrl(url) == Market.SHOPEE) return shopeeTitle
        if (declared.isEmpty()) return null
        if (Market.fromPublicUrl(url) == Market.CAROUSELL) {
            // The verified public profile's h1 is its display name; og:title is
            // only "<username>在旋轉拍賣上". Do not depend on generated CSS classes.
            val markup = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
                .replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
            val names = Regex("<h1\\b[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE).findAll(markup)
                .map { headingText(it.groupValues[1]) }.filter { it.isNotBlank() }.distinct().toList()
            // Two responsive copies with the same name are normal; conflicting
            // headings must not silently select another seller or an error page.
            return names.singleOrNull()?.takeIf { it.lowercase() !in setOf("登入", "login", "just a moment...", "access denied") }?.take(120)
        }
        return metadata.firstOrNull { it["property"]?.lowercase() == "og:title" }?.get("content")
            ?.replace(Regex("\\s*[|｜]\\s*(?:蝦皮購物|Shopee|旋轉拍賣|Carousell).*$", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("[,，]\\s*線上商店\\s*$"), "")?.let(ShopText::shop)?.take(120)
            ?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("登入", "login", "just a moment...", "access denied") }
    }

    private fun headingText(raw: String): String = raw.replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .replace(Regex("&#(?:x([0-9a-fA-F]{1,6})|([0-9]{1,7}));")) { match ->
            val code = if (match.groupValues[1].isNotEmpty()) match.groupValues[1].toIntOrNull(16)
                else match.groupValues[2].toIntOrNull()
            if (code != null && code in 32..0x10FFFF && code !in 0xD800..0xDFFF)
                String(Character.toChars(code)) else ""
        }.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()

    suspend fun read(url: String): String? = withContext(Dispatchers.IO) {
        val initial = canonical(url) ?: return@withContext null
        var current = url
        val deadline = System.nanoTime() + 8_000_000_000L
        try {
            repeat(4) {
                if (!sameStore(current, initial)) return@withContext null
                val remaining = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                if (remaining <= 0) return@withContext null
                val connection = URI(current).toURL().openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = remaining.coerceAtMost(3_000)
                    connection.readTimeout = remaining.coerceAtMost(3_000)
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    when (connection.responseCode) {
                        301, 302, 303, 307, 308 -> current = URI(current).resolve(connection.getHeaderField("Location") ?: return@withContext null).toString()
                        200 -> {
                            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                                val result = StringBuilder(); val chunk = CharArray(8192)
                                while (result.length < 262_144 && System.nanoTime() < deadline) {
                                    val count = reader.read(chunk, 0, minOf(chunk.size, 262_144 - result.length))
                                    if (count < 0) break
                                    result.append(chunk, 0, count)
                                }
                                result.toString()
                            }
                            return@withContext parse(initial, html)
                        }
                        else -> return@withContext null
                    }
                } finally { connection.disconnect() }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* No name is better than a login/error-page name. */ }
        null
    }
}

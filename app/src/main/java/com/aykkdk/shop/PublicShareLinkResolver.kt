package com.aykkdk.shop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/** Resolve official sharing links without importing an account or WebView session. */
object PublicShareLinkResolver {
    internal const val MAX_BODY_BYTES = 262_144
    private const val MAX_URL_LENGTH = 8_192
    private const val MAX_REDIRECTS = 4
    private const val TOTAL_TIMEOUT_MILLIS = 15_000L
    private const val SHARE_HOST = "carousell.app.link"
    private const val SHOPEE_SHARE_HOST = "tw.shp.ee"
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private val taiwanHosts = setOf("tw.carousell.com", "m.tw.carousell.com")
    private val globalHosts = setOf("carousell.com", "www.carousell.com")
    private val reservedShopPaths = setOf("about", "accounts", "buy", "categories", "chats", "contact",
        "help", "login", "logout", "marketplace", "myprofile", "notifications", "p", "privacy",
        "safety", "search", "sell", "settings", "signup", "terms", "u", "verify", "captcha")

    /** Existing validated marketplace URLs require no network request. */
    suspend fun resolve(raw: String): String {
        existingPublicUrl(raw)?.let { return it }
        return withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000
            resolveWith(raw) { readSharePage(it, deadline) }
        }
    }

    /** Injected transport keeps redirect and deepview policy testable on the JVM. */
    internal fun resolveWith(raw: String, fetch: (String) -> ShareLinkResponse): String {
        existingPublicUrl(raw)?.let { return it }
        var current = validatedShareUrl(raw)
            ?: throw IllegalArgumentException("請貼上支援平台的 HTTPS 商品或店家網址。")
        val market = if (URI(current).host == SHOPEE_SHARE_HOST) Market.SHOPEE else Market.CAROUSELL
        val visited = mutableSetOf<String>()
        repeat(MAX_REDIRECTS + 1) { hop ->
            require(visited.add(current)) { "分享連結重複跳轉，請改貼公開商品或店家網址。" }
            val response = fetch(current)
            if (response.code in redirectCodes) {
                val target = redirectTarget(current, response.location)
                    ?: throw IllegalArgumentException("分享連結沒有提供可辨識的公開網址。")
                canonicalDestination(target, market)?.let { return it }
                require(hop < MAX_REDIRECTS) { "分享連結跳轉次數過多。" }
                current = validatedShareUrl(target)
                    ?.takeIf { URI(it).host == URI(current).host }
                    ?: throw IllegalArgumentException("分享連結未指向同平台的公開商品或店家。")
            } else {
                require(response.code == 200) { "分享連結暫時無法讀取（HTTP ${response.code}）。" }
                require(response.body.length <= MAX_BODY_BYTES) { "分享連結頁面過大。" }
                return destinationFromDeepview(response.body, market)
                    ?: throw IllegalArgumentException("分享連結未提供可訂閱的公開網址，請從瀏覽器複製商品或店家網址。")
            }
        }
        throw IllegalArgumentException("分享連結跳轉次數過多。")
    }

    private fun existingPublicUrl(raw: String): String? = raw.trim().takeIf {
        publicUri(it) != null && Market.fromPublicUrl(it) != null
    }

    internal fun validatedShareUrl(raw: String): String? {
        val uri = publicUri(raw) ?: return null
        val host = uri.host.lowercase(Locale.ROOT)
        if (host !in setOf(SHARE_HOST, SHOPEE_SHARE_HOST) ||
            !uri.rawPath.orEmpty().matches(Regex("/[a-zA-Z0-9]{4,128}/?"))) return null
        return "https://$host${uri.rawPath}" + uri.rawQuery?.let { "?$it" }.orEmpty()
    }

    internal fun redirectTarget(current: String, location: String?): String? {
        val base = validatedShareUrl(current)?.let(::URI) ?: return null
        val target = location?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL_LENGTH } ?: return null
        return runCatching { base.resolve(URI(target)).toString() }.getOrNull()
            ?.takeIf { publicUri(it) != null }
    }

    internal fun canonicalCarousellDestination(raw: String): String? {
        val uri = publicUri(raw) ?: return null
        val host = uri.host.lowercase(Locale.ROOT)
        val path = uri.rawPath.orEmpty()
        // Branch's public desktop fallback uses the global numeric listing route.
        // Only explicit public profile/product routes can be changed to the Taiwan host.
        if (host in globalHosts) {
            // Official profile shares also use the global /u/<username> route.
            // Keep the explicit profile prefix; never accept arbitrary global paths.
            val profile = Regex("/u/([a-zA-Z0-9][a-zA-Z0-9._-]{1,63})/?").matchEntire(path)
            if (profile != null && profile.groupValues[1].lowercase(Locale.ROOT) !in reservedShopPaths)
                return "https://tw.carousell.com/u/${profile.groupValues[1]}/"
            if (!path.matches(Regex("/p/[0-9]{5,20}/?"))) return null
            return Market.CAROUSELL.normalizePublicProductUrl("https://tw.carousell.com${path.trimEnd('/')}/")
        }
        if (host !in taiwanHosts || !safeDestinationPath(path)) return null
        Market.CAROUSELL.normalizePublicProductUrl(raw)?.let { return it }
        val shop = Regex("/(?:u/)?([a-zA-Z0-9][a-zA-Z0-9._-]{1,63})/?").matchEntire(path) ?: return null
        if (shop.groupValues[1].lowercase(Locale.ROOT) in reservedShopPaths) return null
        return "https://tw.carousell.com${path.trimEnd('/')}/"
    }

    internal fun canonicalShopeeDestination(raw: String): String? {
        val uri = publicUri(raw) ?: return null
        if (uri.host.lowercase(Locale.ROOT) !in setOf("shopee.tw", "www.shopee.tw", "m.shopee.tw") ||
            !safeDestinationPath(uri.rawPath.orEmpty())) return null
        Market.SHOPEE.normalizePublicProductUrl(raw)?.let { return it }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.matches(Regex("/shop/[1-9][0-9]{0,19}"))) return "https://shopee.tw$path"
        val shop = Regex("/([a-zA-Z0-9][a-zA-Z0-9._-]{1,63})").matchEntire(path) ?: return null
        if (shop.groupValues[1].lowercase(Locale.ROOT) in reservedShopPaths + setOf("buyer", "user", "account",
                "shop", "mall", "cart", "checkout", "order", "orders", "wallet", "payment", "api", "product", "seller")) return null
        return "https://shopee.tw$path"
    }

    private fun canonicalDestination(raw: String, market: Market): String? = when (market) {
        Market.SHOPEE -> canonicalShopeeDestination(raw)
        Market.CAROUSELL -> canonicalCarousellDestination(raw)
        else -> null
    }

    /** Parse published browser fallback links; never execute Branch's scripts. */
    internal fun destinationFromDeepview(html: String, market: Market = Market.CAROUSELL): String? {
        if (html.length > MAX_BODY_BYTES) return null
        val fallbackLinks = Regex("<a\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull {
            val attributes = attributes(it.groupValues[1])
            if ("secondary-action" in attributes["class"].orEmpty().split(Regex("\\s+"))) attributes["href"] else null
        }.toList()
        // The observed Branch deepview exposes its browser destination here.
        // If that explicit fallback exists, do not substitute unrelated metadata.
        if (fallbackLinks.isNotEmpty()) return uniqueDestination(fallbackLinks, market)
        val metadata = Regex("<(?:meta|link)\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull {
            val attributes = attributes(it.groupValues[1])
            when {
                "canonical" in attributes["rel"].orEmpty().lowercase(Locale.ROOT).split(Regex("\\s+")) -> attributes["href"]
                attributes["property"]?.lowercase(Locale.ROOT) in setOf("og:url", "al:web:url") -> attributes["content"]
                else -> null
            }
        }.toList()
        return uniqueDestination(metadata, market)
    }

    private fun uniqueDestination(values: List<String>, market: Market): String? {
        if (values.isEmpty()) return null
        val destinations = values.map { canonicalDestination(it, market) ?: return null }
        return destinations.distinct().singleOrNull()
    }

    private fun attributes(raw: String): Map<String, String> =
        Regex("([a-zA-Z][a-zA-Z0-9:_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
            .findAll(raw).associate {
                it.groupValues[1].lowercase(Locale.ROOT) to decodeHtmlAttribute(
                    if (it.groupValues[2].isNotEmpty()) it.groupValues[2] else it.groupValues[3])
            }

    private fun decodeHtmlAttribute(raw: String): String =
        Regex("&(?:amp|quot|apos|lt|gt|#(?:[0-9]{1,7}|x[0-9a-fA-F]{1,6}));", RegexOption.IGNORE_CASE)
            .replace(raw) { match ->
                when (val entity = match.value.substring(1, match.value.length - 1).lowercase(Locale.ROOT)) {
                    "amp" -> "&"
                    "quot" -> "\""
                    "apos" -> "'"
                    "lt" -> "<"
                    "gt" -> ">"
                    else -> {
                        val number = if (entity.startsWith("#x")) entity.substring(2).toIntOrNull(16)
                            else entity.substring(1).toIntOrNull()
                        if (number != null && number in 1..0x10FFFF && number !in 0xD800..0xDFFF)
                            String(Character.toChars(number)) else match.value
                    }
                }
            }

    private fun publicUri(raw: String): URI? = runCatching {
        val value = raw.trim()
        if (value.length > MAX_URL_LENGTH || value.any { it.code < 32 || it.code == 127 || it == '\\' }) return null
        val uri = URI(value)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
            uri.host.isNullOrBlank() || uri.port != -1) null else uri
    }.getOrNull()

    private fun safeDestinationPath(path: String): Boolean =
        path.split('/').none { it == "." || it == ".." } &&
            !Regex("%(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE).containsMatchIn(path)

    private fun readSharePage(raw: String, deadline: Long): ShareLinkResponse {
        val url = validatedShareUrl(raw) ?: throw IOException("分享網址不支援")
        // Android's URL transport uses a process-wide CookieHandler. Refuse one
        // instead of reading or temporarily replacing anyone's cookie store.
        // This app also never installs a Java Authenticator; keep that invariant
        // if its transport changes. WebView/account sessions are never imported.
        if (CookieHandler.getDefault() != null) throw IOException("無法以匿名連線解析分享網址")
        val addresses = InetAddress.getAllByName(URI(url).host)
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) throw IOException("分享網址的網路位置不支援")
        val connection = URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        try {
            val timeout = remainingMillis(deadline).coerceAtMost(5_000)
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            // Request the web fallback, not the mobile app/store launch route.
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            val code = connection.responseCode
            remainingMillis(deadline)
            if (code in redirectCodes) return ShareLinkResponse(code, connection.getHeaderField("Location"))
            if (code != 200) return ShareLinkResponse(code)
            val declaredLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_BODY_BYTES) throw IOException("分享連結頁面過大")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val chunk = ByteArray(8_192)
                while (true) {
                    connection.readTimeout = remainingMillis(deadline).coerceAtMost(5_000)
                    val count = stream.read(chunk)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_BODY_BYTES) throw IOException("分享連結頁面過大")
                    bytes.write(chunk, 0, count)
                }
            }
            return ShareLinkResponse(code, body = bytes.toString(Charsets.UTF_8.name()))
        } finally {
            connection.disconnect()
        }
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        return when (bytes.size) {
            4 -> bytes[0] != 0 && bytes[0] < 224 &&
                !(bytes[0] == 100 && bytes[1] in 64..127) &&
                !(bytes[0] == 192 && bytes[1] == 0) &&
                !(bytes[0] == 198 && bytes[1] in 18..19) &&
                !(bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100) &&
                !(bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113)
            16 -> bytes[0] and 0xE0 == 0x20 &&
                !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0D && bytes[3] == 0xB8)
            else -> false
        }
    }

    private fun remainingMillis(deadline: Long): Int {
        val remaining = (deadline - System.nanoTime()) / 1_000_000
        if (remaining <= 0) throw IOException("分享連結解析逾時")
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

internal data class ShareLinkResponse(val code: Int, val location: String? = null, val body: String = "")

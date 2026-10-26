package com.aykkdk.shop

import java.net.URI

internal object ShopeeLoginPolicy {
    const val ORIGIN = "https://shopee.tw"
    const val LOGIN_URL = "$ORIGIN/buyer/login"
    enum class AccessRoute { LOGIN, VERIFICATION }

    fun accessRoute(raw: String): AccessRoute? {
        if (!officialPage(raw)) return null
        val path = runCatching { URI(raw).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            Regex("^/(?:buyer/)?(?:verify|captcha)(?:/|$)").containsMatchIn(path) -> AccessRoute.VERIFICATION
            Regex("^/(?:buyer/)?(?:login|signup)(?:/|$)").containsMatchIn(path) -> AccessRoute.LOGIN
            else -> null
        }
    }

    fun publicSearch(raw: String): Boolean = officialPage(raw) &&
        runCatching { URI(raw).path.trimEnd('/') == "/search" }.getOrDefault(false)

    /** Only a completed, current official home navigation after an access flow counts. */
    fun completedHome(finished: String, current: String, accessStarted: Boolean, failed: Boolean): Boolean {
        fun home(raw: String): Boolean = officialPage(raw) && runCatching {
            val uri = URI(raw)
            uri.host.lowercase() in setOf("shopee.tw", "www.shopee.tw", "m.shopee.tw") &&
                uri.path.orEmpty() in setOf("", "/")
        }.getOrDefault(false)
        return accessStarted && !failed && home(finished) && home(current)
    }

    /** Only the current, successfully loaded original search can end an access flow. */
    fun completedSearch(finished: String, current: String, expected: String, accessStarted: Boolean, failed: Boolean): Boolean =
        accessStarted && !failed && canResumeSearch(finished, expected) && canResumeSearch(current, expected)

    /** Returning from verification is not evidence of login. Match the frozen search separately. */
    fun canResumeSearch(current: String, expected: String): Boolean {
        if (!publicSearch(current) || !publicSearch(expected) || current.length > 16_384 || expected.length > 16_384) return false
        fun parameters(raw: String): Map<String, String>? = runCatching {
            val pairs = URI(raw).rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            if (pairs.groupBy { it.first }.any { it.value.size != 1 }) return null
            pairs.toMap().filterKeys { it in setOf("keyword", "page", "sortBy", "order", "fe_filter_options") }
                .toMutableMap().apply { putIfAbsent("page", "0"); putIfAbsent("sortBy", "relevancy") }
        }.getOrNull()
        val actual = parameters(current) ?: return false
        val original = parameters(expected) ?: return false
        return original["keyword"].orEmpty().isNotBlank() && actual == original
    }

    fun shouldAutoRecover(request: SearchCapture, foreground: Boolean, recovery: ShopeeRecovery?): Boolean =
        foreground && request.market == Market.SHOPEE && request.quickSearch && !request.backgroundRead &&
            !request.scanSubscription && request.officialRecoveryAttempt == 0 && recovery?.searchUrl == request.url

    fun recovery(raw: String, searchUrl: String): ShopeeRecovery? {
        if (raw.length > 16_384 || searchUrl.length > 16_384) return null
        val route = accessRoute(raw) ?: return null
        if (!publicSearch(searchUrl)) return null
        // A server redirect may carry an encoded continuation. Do not allow an external login destination.
        val continuations = runCatching {
            URI(raw).rawQuery.orEmpty().split('&').map { part ->
                java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8").lowercase() to
                    java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }.filter { it.first in setOf("next", "home_url") }
        }.getOrNull() ?: return null
        if (continuations.groupBy { it.first }.any { it.value.size != 1 } ||
            continuations.any { !officialPage(it.second) }) return null
        return ShopeeRecovery(raw, searchUrl, route)
    }

    fun accessMessage(route: AccessRoute): String = when (route) {
        AccessRoute.VERIFICATION -> "蝦皮要求官方驗證，不代表帳號已登出。請由右上角「完成蝦皮驗證」繼續，再重新搜尋。"
        AccessRoute.LOGIN -> "蝦皮要求重新登入。請由右上角「重新登入蝦皮」繼續，再重新搜尋。"
    }

    /** Expire only this app's Shopee session, retaining language and other sites' cookies. */
    fun logoutCookies(): List<Pair<String, String>> {
        val names = listOf("SPC_U", "SPC_EC", "SPC_ST")
        return listOf(ORIGIN, "https://www.shopee.tw", "https://m.shopee.tw", "https://account.shopee.tw")
            .flatMap { origin -> names.flatMap { name ->
                listOf("", "; Domain=shopee.tw").map { domain ->
                    origin to "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/$domain; Secure; HttpOnly"
                }
            } }
    }

    fun officialPage(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            (host == "shopee.tw" || host.endsWith(".shopee.tw"))
    }.getOrDefault(false)

    fun requiresAuthentication(raw: String): Boolean = accessRoute(raw) != null

    /** Local gate only; the server can still reject an expired/revoked session. */
    fun sessionCookiesPresent(header: String?): Boolean {
        if (header.isNullOrBlank()) return false
        val parts = header.split(';').mapNotNull { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2) pair[0].trim() to pair[1].trim().removeSurrounding("\"") else null
        }
        val users = parts.filter { it.first == "SPC_U" }
        if (users.size != 1 || (users.single().second.toLongOrNull() ?: -1L) <= 0L) return false
        return parts.any { (name, value) -> name in setOf("SPC_EC", "SPC_ST") &&
            value.isNotBlank() && value.lowercase() !in setOf("-1", "null", "undefined") }
    }
}

/** Memory-only navigation, never persisted, exported, or treated as confirmed sign-in. */
internal data class ShopeeRecovery(val officialUrl: String, val searchUrl: String, val route: ShopeeLoginPolicy.AccessRoute)

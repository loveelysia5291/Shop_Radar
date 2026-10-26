package com.aykkdk.shop

import java.net.URI

/** Yahoo may visit GUCE during an automatic redirect; the URL alone is not an auth failure. */
internal object YahooConsentPolicy {
    const val MESSAGE = "Yahoo 尚未從轉址頁返回商品頁，這不代表需要帳號認證。可開啟原網頁後重新讀取；若網站實際顯示隱私選項，再自行選擇。"
    const val REDIRECT_WAIT_MILLIS = 5_000L

    fun isConsentUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", true) && uri.host.equals("guce.yahoo.com", true) &&
            uri.userInfo == null && uri.port == -1
    }.getOrDefault(false)

    fun allowsVisiblePage(raw: String): Boolean = Market.YAHOO.accepts(raw) || isConsentUrl(raw)

    fun allowsProductPage(current: String, original: String): Boolean =
        Market.YAHOO.isPublicProductUrl(original) &&
            (PublicProductParser.sameProductUrl(current, original) || isConsentUrl(current))

    fun canRetryFrom(current: String, original: String): Boolean = runCatching {
        Market.YAHOO.accepts(current) && Market.YAHOO.accepts(original) &&
            URI(current).host.equals(URI(original).host, true) &&
            URI(current).path.trimEnd('/') == URI(original).path.trimEnd('/')
    }.getOrDefault(false)
}

/** One bounded wait per continuous redirect, not a fresh timeout on every page event. */
internal class YahooRedirectWait {
    private var enteredAt: Long? = null

    fun expired(url: String, now: Long): Boolean {
        if (!YahooConsentPolicy.isConsentUrl(url)) {
            enteredAt = null
            return false
        }
        val start = enteredAt ?: now.also { enteredAt = it }
        return now - start >= YahooConsentPolicy.REDIRECT_WAIT_MILLIS
    }
}

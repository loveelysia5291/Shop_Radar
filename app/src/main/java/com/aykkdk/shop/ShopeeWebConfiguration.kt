package com.aykkdk.shop

import android.content.Context
import android.webkit.WebSettings
import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Same browser identity and WebView cookie jar for sign-in and product searches. */
internal object ShopeeWebConfiguration {
    // Initialize only on an explicit browser/search action, never on the launch path.
    private var cachedUserAgent: String? = null
    // An official login return can precede the cookie jar's session-cookie update.
    // Keep only the result in memory, never credentials or duplicate cookie values.
    private var homeLoginConfirmed = false
    var recovery by mutableStateOf<ShopeeRecovery?>(null)
        private set
    fun requireRecovery(officialUrl: String, searchUrl: String) {
        ShopeeLoginPolicy.recovery(officialUrl, searchUrl)?.let {
            if (it.route == ShopeeLoginPolicy.AccessRoute.LOGIN) homeLoginConfirmed = false
            recovery = it
        }
    }
    fun loginReturnedHome() { homeLoginConfirmed = true; recovery = null }
    fun searchConfirmed(searchUrl: String) {
        if (recovery?.searchUrl == searchUrl) recovery = null
    }
    @Synchronized fun userAgent(context: Context): String = cachedUserAgent ?: WebSettings.getDefaultUserAgent(context)
        .also { cachedUserAgent = it }

    fun configure(web: WebView, context: Context) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgent(context)
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
    }

    // Cookie values stay in System WebView's own jar. Never log, export or duplicate them.
    fun canSearch(): Boolean = homeLoginConfirmed || runCatching {
        ShopeeLoginPolicy.sessionCookiesPresent(CookieManager.getInstance().getCookie(ShopeeLoginPolicy.ORIGIN))
    }.getOrDefault(false)

    suspend fun logout(): Boolean = withContext(Dispatchers.Main.immediate) {
        recovery = null
        homeLoginConfirmed = false
        val jar = CookieManager.getInstance()
        for ((origin, cookie) in ShopeeLoginPolicy.logoutCookies()) {
            suspendCancellableCoroutine<Unit> { continuation ->
                jar.setCookie(origin, cookie) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        withContext(Dispatchers.IO) { jar.flush() }
        !canSearch()
    }
}

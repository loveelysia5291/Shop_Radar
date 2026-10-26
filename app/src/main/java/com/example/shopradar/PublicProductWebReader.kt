package com.example.shopradar

import android.content.Context
import android.net.http.SslError
import android.view.View
import android.webkit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Bounded official product-page rendering, following the final listing route before parsing. */
internal object PublicProductWebReader {
    suspend fun read(context: Context, original: String): PublicProductSnapshot = withContext(Dispatchers.Main.immediate) {
        val market = Market.fromPublicProductUrl(original)
        if (market !in setOf(Market.CAROUSELL, Market.SHOPEE, Market.FACEBOOK, Market.IOPEN, Market.YAHOO)) return@withContext PublicProductSnapshot(error = "網址不支援")
        var web: WebView? = null
        var loadError: String? = null
        var httpCode = 200
        var rendererGone = false
        var latest = PublicProductSnapshot(error = "商品頁尚未提供可確認的資料")
        var metadataReadyAt = 0L
        val yahooRedirectWait = YahooRedirectWait()
        fun permitted(url: String) = if (market == Market.YAHOO) YahooConsentPolicy.allowsProductPage(url, original)
            else PublicProductParser.sameProductUrl(url, original)
        val dateScript = if (market in setOf(Market.SHOPEE, Market.FACEBOOK, Market.IOPEN))
            CaptureAssets.read(context, "facebook-public-metadata.js") + ";\n" + CaptureAssets.read(context, "public-listing-time.js") + ";\n" +
                "window.__shopRadarFacebookListing = ${JSONObject().put("url", original).put("title", "")};\n" +
                "var listingDate = JSON.parse(" + CaptureAssets.read(context,
                    if (market == Market.FACEBOOK) "extract-facebook-listing-time.js" else "extract-public-listing-time.js") + ");\n"
            else "var listingDate = {};\n"
        try {
            withTimeoutOrNull(15_000) {
                val view = WebView(context.applicationContext)
                web = view
                if (market == Market.SHOPEE) ShopeeWebConfiguration.configure(view, context)
                else view.settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true
                    userAgentString = if (market == Market.FACEBOOK) FacebookWebPolicy.desktopUserAgent(WebSettings.getDefaultUserAgent(context))
                        else WebSettings.getDefaultUserAgent(context)
                    useWideViewPort = true; loadWithOverviewMode = true
                    allowFileAccess = false; allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                }
                MetadataNetworkPolicy.configure(view)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        MetadataNetworkPolicy.response(request.url.toString(), request.isForMainFrame)
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val allowed = permitted(request.url.toString())
                        if (!allowed) loadError = if (market == Market.YAHOO) "Y拍跳轉至其他頁面，未取得原商品資料；保留原有狀態"
                            else "商品頁要求登入／驗證，或跳轉至非商品頁；保留原有狀態"
                        return !allowed
                    }
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        httpCode = 200
                        if (!permitted(url)) loadError = "未到達原商品的最終頁面"
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) httpCode = response.statusCode
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) loadError = "WebView 暫時無法載入商品頁"
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel(); loadError = "商品頁安全連線驗證失敗"
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        rendererGone = true; loadError = "系統 WebView 已停止，請重試"; return true
                    }
                }
                val metrics = context.resources.displayMetrics
                view.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels.coerceAtLeast(360), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels.coerceAtLeast(640), View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                view.loadUrl(original)
                while (true) {
                    delay(500)
                    loadError?.let { return@withTimeoutOrNull PublicProductSnapshot(error = it) }
                    if (httpCode != 200) return@withTimeoutOrNull PublicProductParser.parse(original, httpCode, "")
                    if (market == Market.YAHOO && yahooRedirectWait.expired(view.url.orEmpty(), android.os.SystemClock.elapsedRealtime()))
                        return@withTimeoutOrNull PublicProductSnapshot(error = YahooConsentPolicy.MESSAGE)
                    if (!PublicProductParser.sameProductUrl(view.url.orEmpty(), original)) continue
                    // Product-scoped published metadata only; never read forms, cookies or private globals.
                    val raw = suspendCancellableCoroutine<String> { continuation ->
                        view.evaluateJavascript("""(function(){
                            $dateScript
                            return JSON.stringify({url:location.href,listedAt:listingDate.listedAt || '',listedAtEpochMillis:listingDate.listedAtEpochMillis || 0,html:Array.from(document.querySelectorAll('meta[property^="og:"],link[rel="canonical"],script[type="application/ld+json"]${if (market == Market.YAHOO) ",script#isoredux-data" else ""}')).map(n=>n.outerHTML).join('').slice(0,524288)});})()""") {
                            if (continuation.isActive) continuation.resume(it)
                        }
                    }
                    val data = runCatching { JSONObject(JSONTokener(raw).nextValue() as? String ?: "") }.getOrNull() ?: continue
                    val final = data.optString("url")
                    if (!PublicProductParser.sameProductUrl(final, original)) continue
                    latest = PublicProductParser.parse(original, 200, data.optString("html"))
                        .copy(resolvedUrl = Market.normalizePublicProductUrl(final))
                    data.optString("listedAt").takeIf { it.isNotBlank() }?.let {
                        latest = latest.copy(listedAt = it, listedAtEpochMillis = data.optLong("listedAtEpochMillis").takeIf { at -> at > 0 })
                    }
                    if (!latest.listedAt.isNullOrBlank()) return@withTimeoutOrNull latest
                    if (latest.status != "unknown" || latest.title != null) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (metadataReadyAt == 0L) metadataReadyAt = now
                        // OG metadata often precedes the rendered date. Give hydration
                        // a short grace period, within the existing overall timeout.
                        if (market == Market.CAROUSELL || now - metadataReadyAt >= 3_000)
                            return@withTimeoutOrNull latest
                    }
                }
                @Suppress("UNREACHABLE_CODE") latest
            } ?: latest
        } finally {
            web?.let { view ->
                runCatching { if (!rendererGone) { view.stopLoading(); view.removeAllViews() }; view.destroy() }
            }
        }
    }
}

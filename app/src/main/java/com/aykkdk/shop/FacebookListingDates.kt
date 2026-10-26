package com.aykkdk.shop

import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Fill in Facebook/iOPEN dates only for cards settled on screen.
 * Two readers at most; stop on leaving results or the foreground.
 */
@Composable
internal fun FacebookListingDates(products: List<Product>, gridState: LazyGridState, enabled: Boolean,
    content: @Composable (List<Product>) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val listener = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(listener)
        onDispose { owner.lifecycle.removeObserver(listener) }
    }
    val onScreen by remember(gridState) {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.map { it.key.toString() }.toSet() }
    }
    // Reading the version invalidates the UI even though cache storage itself is not Compose state.
    val displayed = remember(products, version) {
        products.map { FacebookListingMetadata.apply(it, System.currentTimeMillis()) }.distinctBy { it.url }
    }
    var settledUrls by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(onScreen, enabled, resumed) {
        settledUrls = settledUrls.intersect(onScreen)
        if (enabled && resumed) {
            // Do not start full page loads for cards that merely flash past while scrolling.
            delay(450)
            settledUrls = onScreen
        } else settledUrls = emptySet()
    }
    val pending = if (!enabled || !resumed) emptyList() else displayed.filter { product ->
        product.url in onScreen && product.url in settledUrls &&
            FacebookListingMetadata.needsRead(product, System.currentTimeMillis())
    }.take(FacebookListingMetadata.MAX_READERS)
    Box(Modifier.fillMaxSize()) {
        pending.forEach { product ->
            key(product.url) {
                val reader = remember(product.url) {
                    FacebookListingDateReader(context, product) { date ->
                        FacebookListingMetadata.complete(product.url, date, System.currentTimeMillis())
                        version++
                    }
                }
                DisposableEffect(reader) { onDispose { reader.close() } }
                AndroidView(factory = { reader.web }, modifier = Modifier.fillMaxSize())
            }
        }
        // The calling page must be opaque; do not expose site pages or login forms.
        content(displayed)
    }
}

private class FacebookListingDateReader(context: android.content.Context, product: Product,
    private val deliver: (FacebookListingTime?) -> Unit) {
    private val listingHandler = Handler(Looper.getMainLooper())
    private val targetUrl = requireNotNull(FacebookListingMetadata.directUrl(product))
    private val market = requireNotNull(Market.fromPublicProductUrl(targetUrl))
    private var closed = false
    private var finished = false
    private var scheduled = false
    private var loading = false
    private val script = "window.__shopRadarFacebookListing = " +
        JSONObject().put("url", targetUrl).put("title", product.title) + ";\n" +
        CaptureAssets.read(context, "facebook-public-metadata.js") + ";\n" +
        CaptureAssets.read(context, "public-listing-time.js") + ";\n" +
        CaptureAssets.read(context, if (market == Market.FACEBOOK) "extract-facebook-listing-time.js" else "extract-public-listing-time.js")
    val web = WebView(context).apply {
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setOnTouchListener { _, _ -> true }
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = if (market == Market.FACEBOOK) FacebookWebPolicy.desktopUserAgent(WebSettings.getDefaultUserAgent(context))
                else WebSettings.getDefaultUserAgent(context)
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // Use the same local WebView jar, without reading/exporting cookies.
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        if (market == Market.SHOPEE) ShopeeWebConfiguration.configure(this, context)
        MetadataNetworkPolicy.configure(this)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                MetadataNetworkPolicy.response(request.url.toString(), request.isForMainFrame)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (PublicProductParser.sameProductUrl(request.url.toString(), targetUrl)) return false
                // A redirect to login/verification is not listing-time evidence. Do not bypass it.
                finish(null)
                return true
            }
            override fun onPageFinished(view: WebView, loadedUrl: String) { schedule() }
            override fun onPageCommitVisible(view: WebView, loadedUrl: String) { schedule() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) finish(null)
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) finish(null)
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel(); finish(null)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finish(null)
                return true
            }
        }
        doOnLayout {
            if (!closed && !finished && !loading && width > 0 && height > 0) {
                loading = true
                listingHandler.postDelayed({ finish(null) }, FacebookListingMetadata.READ_MILLIS)
                runCatching { loadUrl(targetUrl); schedule() }.onFailure { finish(null) }
            }
        }
    }
    private fun schedule() {
        if (closed || finished || scheduled || !loading) return
        scheduled = true
        listingHandler.postDelayed({
            if (closed || finished) return@postDelayed
            runCatching {
                web.evaluateJavascript(script) { raw ->
                    scheduled = false
                    if (closed || finished) return@evaluateJavascript
                    val data = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()
                    if (data?.optString("state") == "ready" && data.optString("url") == targetUrl &&
                        data.optString("listedAt").isNotBlank()) {
                        finish(FacebookListingTime(data.optString("listedAt"), data.optLong("listedAtEpochMillis")))
                    } else schedule()
                }
            }.onFailure { scheduled = false; finish(null) }
        }, 350)
    }
    private fun finish(date: FacebookListingTime?) {
        if (closed || finished) return
        finished = true
        listingHandler.removeCallbacksAndMessages(null)
        runCatching { web.stopLoading() }
        deliver(date)
    }
    fun close() {
        if (closed) return
        closed = true
        listingHandler.removeCallbacksAndMessages(null)
        runCatching {
            web.stopLoading()
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.webViewClient = WebViewClient()
            web.destroy()
        }
    }
}

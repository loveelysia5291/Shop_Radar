package com.example.shopradar

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class SearchCapture(
    val market: Market,
    val url: String,
    val token: Int,
    // Legacy store-preview continuation; mobile keyword searches scroll continuously.
    val loadMore: Boolean = false,
    // The reader reloads the public page for each continuation.  This target
    // replays prior "more" clicks internally, while each user action adds only
    // one new batch to the visible list.
    val moreClicks: Int = 0,
    val quickSearch: Boolean = false,
    val subscriptionCheckpoint: Long = 0,
    val subscriptionSeen: Set<String> = emptySet(),
    val scanSubscription: Boolean = false,
    val backgroundRead: Boolean = false,
    val previousPageUrls: Set<String> = emptySet(),
    val retryAttempt: Int = 0,
    val shopPreview: Boolean = false,
    // Separate from network retries: do not repeatedly open official verification UI.
    val officialRecoveryAttempt: Int = 0,
    val requestedPage: Int = 1,
    val minimumPrice: Long = 0L,
    val maximumPrice: Long = Long.MAX_VALUE,
    val consumedProducts: Int = 0
)

internal val SearchCapture.infiniteSearch: Boolean get() = SearchPageLoading.infinite(market) &&
    (quickSearch || shopPreview && market in setOf(Market.SHOPEE, Market.YAHOO))

/** Retrying Shopee keeps the attached renderer and its original deadline. */
internal fun SearchCapture.readerSession(): SearchCapture =
    if (infiniteSearch) copy(retryAttempt = 0, consumedProducts = 0)
    else if (market == Market.SHOPEE) copy(retryAttempt = 0) else this

/** Hidden in Release; Debug can show the exact Shopee renderer for diagnosis. */
@Composable
internal fun HiddenMarketCapture(
    request: SearchCapture,
    onDiagnostic: (String, String) -> Unit = { _, _ -> },
    onProgress: (List<Product>) -> Unit = {},
    onPageCount: (Int) -> Unit = {},
    onFeedPaused: (Boolean) -> Unit = {},
    onShopName: (String) -> Unit = {},
    onProducts: (List<Product>) -> Unit,
    onTimeout: (String) -> Unit
) {
    val context = LocalContext.current
    val latestProgress by rememberUpdatedState(onProgress)
    val latestPageCount by rememberUpdatedState(onPageCount)
    val latestFeedPaused by rememberUpdatedState(onFeedPaused)
    val latestProducts by rememberUpdatedState(onProducts)
    val latestTimeout by rememberUpdatedState(onTimeout)
    val latestDiagnostic by rememberUpdatedState(onDiagnostic)
    val latestShopName by rememberUpdatedState(onShopName)
    val session = request.readerSession()
    key(session) {
        val captureScope = rememberCoroutineScope()
        val preparationStarted = remember { SystemClock.elapsedRealtime() }
        val boboMetadata = remember { mutableMapOf<String, Product>() }
        val boboRequested = remember { mutableSetOf<String>() }
        val boboJobs = remember { mutableListOf<Job>() }
        val boboSlots = remember { Semaphore(4) }
        fun withMetadata(found: List<Product>) = found.map { product ->
            boboMetadata[product.url]?.let { metadata -> product.copy(shop = metadata.shop, category = metadata.category,
                listedAt = metadata.listedAt, listedAtEpochMillis = metadata.listedAtEpochMillis,
                isAdult = product.isAdult || metadata.isAdult) } ?: product
        }
        fun prepareBobo(found: List<Product>) {
            if (request.market != Market.BBOBO || !request.quickSearch) return
            val pending = found.filter { boboRequested.add(it.url) }
            if (pending.isNotEmpty()) boboJobs += captureScope.launch {
                BoboProductMetadata.enrich(pending, boboSlots) { boboMetadata[it.url] = it }
            }
        }
        val controller = remember { HiddenCaptureController(context, session, captureScope, { captured ->
            prepareBobo(captured)
            captureScope.launch {
                if (request.market == Market.BBOBO && request.quickSearch) {
                    val remaining = SearchPageLoading.prepareRemaining(request.market, SystemClock.elapsedRealtime() - preparationStarted)
                    if (remaining > 0) withTimeoutOrNull(remaining) { boboJobs.toList().joinAll() }
                    delay(SearchPageLoading.prepareRemaining(request.market, SystemClock.elapsedRealtime() - preparationStarted))
                }
                val enriched = if (request.market == Market.RUTEN && !request.quickSearch && !request.shopPreview && !request.scanSubscription) RutenSearch.enrich(captured) else withMetadata(captured)
                ImageCachePolicy.record(context, enriched)
                latestProducts(ShopCheckpoint.freeze(enriched, System.currentTimeMillis()))
            }
        }, { latestTimeout(it) }, { message, report -> latestDiagnostic(message, report) },
            { prepareBobo(it); latestProgress(ShopCheckpoint.freeze(withMetadata(it), System.currentTimeMillis())) }, { latestPageCount(it) }, { latestFeedPaused(it) }, { latestShopName(it) }) }
        LaunchedEffect(controller, request.consumedProducts) { controller.updateConsumed(request.consumedProducts) }
        DisposableEffect(controller) {
            onDispose { controller.close() }
        }
        LaunchedEffect(controller, request.retryAttempt) {
            if (request.market == Market.SHOPEE && request.retryAttempt > 0) controller.retryEmptyPage()
        }
        CaptureReaderSurface(request, controller.liveText, { controller.stopDebug() }) { modifier ->
            AndroidView(factory = { controller.web }, modifier = modifier)
        }
    }
}

private class YahooDateBridge(private val deliver: (String) -> Unit, private val progress: (String) -> Unit) {
    @JavascriptInterface
    fun deliver(json: String) = Handler(Looper.getMainLooper()).post { deliver.invoke(json) }
    @JavascriptInterface
    fun preview(json: String) = Handler(Looper.getMainLooper()).post { progress.invoke(json) }
}

internal class HiddenCaptureController(
    private val context: android.content.Context,
    private val request: SearchCapture,
    private val captureScope: CoroutineScope,
    private val onProducts: (List<Product>) -> Unit,
    private val onTimeout: (String) -> Unit,
    private val onDiagnostic: (String, String) -> Unit,
    private val onProgress: (List<Product>) -> Unit,
    private val onPageCount: (Int) -> Unit = {},
    private val onFeedPaused: (Boolean) -> Unit = {},
    private val onShopName: (String) -> Unit = {}
) {
    // Never call this `handler`: WebView.apply resolves View.getHandler(), which is null before attachment.
    private val captureHandler = Handler(Looper.getMainLooper())
    private val isFacebook = request.market == Market.FACEBOOK
    private val extendedShopeeSearch = request.market == Market.SHOPEE && request.quickSearch
    private val script = CaptureAssets.script(context, request.market)
    private val startedAt = SystemClock.elapsedRealtime()
    private val facebookItems = linkedMapOf<String, Product>()
    private val retainedProducts = linkedMapOf<String, Product>()
    private var consumedProducts = 0
    private var feedPaused = false
    fun updateConsumed(count: Int) {
        consumedProducts = count.coerceAtLeast(0)
        if (feedPaused && !SearchPageLoading.pauseFeed(true, feedProducts().size - consumedProducts)) {
            feedPaused = false; feedEnd.reset(); lastFeedReadAt = SystemClock.elapsedRealtime()
            onFeedPaused(false); scheduleExtract(0)
        }
    }
    private var facebookCandidates = 0
    private var facebookState = "loading"
    private val diagnostics = CaptureDiagnostics(request.market.label, request.url,
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())
    private val captureScript = ShopeeGuestSearch.script(context, request) +
        "window.__shopRadarQuickSearch = ${request.quickSearch};\n" +
        "window.__shopRadarInfiniteSearch = ${request.infiniteSearch};\n" +
        (if (request.market == Market.SHOPEE) "window.__shopRadarShopeeEnvelope = true; window.__shopRadarExpectedSearchUrl = ${JSONObject.quote(request.url)}; window.__shopRadarPreviousPageUrls = ${JSONArray(request.previousPageUrls.toList())}; window.__shopRadarShopeeStoreUrl = ${JSONObject.quote(if (!request.quickSearch && !request.market.isPublicProductUrl(request.url)) PublicShareLinkResolver.canonicalShopeeDestination(request.url).orEmpty() else "")};\n" else "") +
        "window.__shopRadarScanSubscription = ${request.scanSubscription}; window.__shopRadarSubscriptionCheckpoint = ${request.subscriptionCheckpoint}; window.__shopRadarSubscriptionSeen = ${JSONArray(request.subscriptionSeen.toList())};\n" + if (request.loadMore) {
        "window.__shopRadarLoadMore = true; window.__shopRadarMoreTarget = ${request.moreClicks.coerceAtLeast(1)};\n$script"
    } else script
    private var attempts = 0
    private var previousCount = -1
    private var stableCount = 0
    private var productSignature = ""
    private var done = false
    private var closed = false
    private var finishingFailure = false
    private var extractionScheduled = false
    private var rutenEnrichmentPending = false
    private val rutenMetadata = linkedMapOf<String, Product>()
    private var shopeePageReady = false
    private val pageCompletion = SearchPageLoading.Completion()
    private var guestEmptyAttempted = false
    private var emptyPageRetried = false
    private val feedEnd = SearchPageLoading.FeedEnd()
    private var lastFeedReadAt = startedAt
    private val feedStateScript by lazy { CaptureAssets.read(context, "search-feed-state.js") }
    private var pageNavigationTarget: String? = null
    private var pageNavigationReady = request.market != Market.IOPEN || request.requestedPage == 1
    private val yahooRedirectWait = YahooRedirectWait()
    val liveText get() = diagnostics.liveText
    val web: WebView = WebView(context).apply {
        isFocusable = CaptureDebugPolicy.visibleReader(request)
        isFocusableInTouchMode = CaptureDebugPolicy.visibleReader(request)
        setOnTouchListener { _, _ -> !CaptureDebugPolicy.visibleReader(request) }
        if (request.market == Market.SHOPEE) ShopeeWebConfiguration.configure(this, context)
        else {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = when (request.market) {
                Market.FACEBOOK -> FacebookWebPolicy.desktopUserAgent(WebSettings.getDefaultUserAgent(context))
                else -> WebSettings.getDefaultUserAgent(context)
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, isFacebook)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                // Do not collect console text; it can contain page data or credentials.
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                    diagnostics.consoleError(message.message(), settings.userAgentString)
                return true
            }
        }
        if (request.market in setOf(Market.SHOPEE, Market.YAHOO)) MetadataNetworkPolicy.configure(this)
        if (request.market == Market.YAHOO) addJavascriptInterface(YahooDateBridge(::acceptYahooDates, ::previewYahooDates), "ShopRadarYahoo")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, navigation: WebResourceRequest): WebResourceResponse? =
                if (request.market in setOf(Market.SHOPEE, Market.YAHOO))
                    MetadataNetworkPolicy.response(navigation.url.toString(), navigation.isForMainFrame) else null
            override fun shouldOverrideUrlLoading(view: WebView, navigation: WebResourceRequest): Boolean {
                val uri = navigation.url
                if (!navigation.isForMainFrame) return false
                // GUCE often immediately returns to the listing. Let the official redirect run.
                if (request.market == Market.YAHOO && YahooConsentPolicy.isConsentUrl(uri.toString())) return false
                // Debug can test one login redirect; verification always goes to official UI.
                if (ShopeeGuestSearch.blockLoginRedirect(request, uri.toString(), retainedProducts.isNotEmpty(), navigation.hasGesture(), guestEmptyAttempted)) {
                    diagnostics.event("debugPublicCardsRetainedBeforeLogin", uri.toString())
                    if (retainedProducts.isEmpty()) {
                        guestEmptyAttempted = true
                        captureHandler.postDelayed({
                            if (canUseWeb() && retainedProducts.isEmpty()) stopForShopeeAccess(uri.toString())
                        }, 1_200)
                    }
                    scheduleExtract(250)
                    return true
                }
                if (stopForShopeeAccess(uri.toString())) return true
                return uri.scheme != "https" || uri.userInfo != null || !request.market.accepts(uri.toString())
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                diagnostics.event("pageStarted", url)
                if (awaitYahooRedirect(url)) return
                diagnostics.step(CaptureStep.PAGE_STARTED)
                if (stopForShopeeAccess(url)) return
            }
            override fun onPageFinished(view: WebView, url: String) {
                diagnostics.event("pageFinished", url)
                if (awaitYahooRedirect(view.url.orEmpty())) return
                if (stopForShopeeAccess(url)) return
                diagnostics.step(CaptureStep.PAGE_FINISHED)
                if (url == pageNavigationTarget && view.url == url) pageNavigationReady = true
                if (canUseWeb() && request.market.accepts(url)) {
                    // A page-finished callback can arrive after Compose has removed
                    // this invisible view.  Never let a destroyed System WebView or
                    // a malformed page script take down the whole application.
                    scheduleExtract(300)
                }
            }
            override fun onPageCommitVisible(view: WebView, url: String) {
                diagnostics.event("pageCommitVisible", url)
                if (awaitYahooRedirect(view.url.orEmpty())) return
                if (stopForShopeeAccess(url)) return
                diagnostics.step(CaptureStep.PAGE_COMMITTED)
                if (url == pageNavigationTarget) pageNavigationReady = true
                if (canUseWeb() && request.market.accepts(url)) scheduleExtract(250)
            }
            override fun onReceivedHttpError(view: WebView, navigation: WebResourceRequest, response: WebResourceResponse) {
                diagnostics.event("HTTP ${response.statusCode}; main=${navigation.isForMainFrame}", navigation.url.toString())
                if (navigation.isForMainFrame) {
                    diagnostics.step(CaptureStep.LOAD_FAILED)
                    finishTimeout("頁面回應 HTTP ${response.statusCode}，請稍後重試。", immediate = true)
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if (canUseWeb()) {
                    diagnostics.step(CaptureStep.LOAD_FAILED)
                    finishTimeout("系統 WebView 已停止，請重試；原有商品與訂閱會保留。")
                }
                return true
            }
            override fun onReceivedError(view: WebView, navigation: WebResourceRequest, error: WebResourceError) {
                diagnostics.event("WebView errorCode=${error.errorCode}; main=${navigation.isForMainFrame}", navigation.url.toString())
                if (navigation.isForMainFrame) {
                    if (error.errorCode == WebViewClient.ERROR_TIMEOUT &&
                        (request.quickSearch || request.shopPreview || request.loadMore)) {
                        scheduleExtract(500)
                        return
                    }
                    diagnostics.step(CaptureStep.LOAD_FAILED)
                    finishTimeout("讀取失敗，請按重試。", immediate = true)
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                diagnostics.step(CaptureStep.LOAD_FAILED)
                finishTimeout("網站安全連線驗證失敗，已停止搜尋。", immediate = true)
            }
        }
        diagnostics.step(CaptureStep.LOADING)
        if (request.market == Market.SHOPEE && !ShopeeWebConfiguration.canSearch() && !ShopeeGuestSearch.allows(request)) {
            // Delay until this controller's web property is initialized.
            captureHandler.post {
                diagnostics.step(CaptureStep.AUTH_REQUIRED)
                finishTimeout("請先由右上角登入蝦皮，再搜尋商品。", immediate = true, accessRequired = true)
            }
        } else if (request.market == Market.SHOPEE || isFacebook) {
            // The same ordering as native sign-in: responsive cards must see a real viewport.
            // Never rewrite the page; access routes hand off to the official visible browser.
            doOnLayout {
                if (canUseWeb() && width > 0 && height > 0) runCatching { loadUrl(request.url) }
                    .onFailure { diagnostics.step(CaptureStep.LOAD_FAILED); finishTimeout("讀取失敗，請按重試。") }
            }
        } else runCatching { loadUrl(request.url) }.onFailure { diagnostics.step(CaptureStep.LOAD_FAILED); finishTimeout("讀取失敗，請按重試。") }
    }
    init {
        if (!request.backgroundRead) diagnostics.startHtmlCapture(context, web, isFacebook || request.market == Market.SHOPEE)
        if (request.infiniteSearch) captureHandler.postDelayed({
            if (canUseWeb() && feedProducts().isEmpty()) finishTimeout(SearchWaitPolicy.TIMEOUT_MESSAGE, immediate = true)
        }, SearchPageLoading.emptyDeadline(request.market, true))
        if (request.infiniteSearch) checkFeedHealth()
        if (!request.infiniteSearch) captureHandler.postDelayed({
            if (!canUseWeb()) return@postDelayed
            if ((request.quickSearch || request.loadMore) && retainedProducts.isNotEmpty()) {
                val remaining = (SearchPageLoading.PAGE_DEADLINE_MILLIS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)
                captureHandler.postDelayed({
                    if (canUseWeb()) finishTimeout(SearchPageLoading.PARTIAL_MESSAGE, immediate = true)
                }, remaining)
            }
            else if (retainedProducts.isNotEmpty()) finishProducts(retainedProducts.values.toList())
            else if (isFacebook && facebookItems.isNotEmpty()) finishProducts(facebookItems.values.toList())
            else finishTimeout(if (isFacebook && facebookCandidates > 0)
                "已讀到 Facebook 商品連結，但沒有可辨識的名稱或價格，請重試。"
            else if (isFacebook) "Facebook 頁面未提供可讀取商品，請重試。"
            else if (request.quickSearch || request.shopPreview || request.loadMore) SearchWaitPolicy.TIMEOUT_MESSAGE
            else "抓取逾時，請按重試。")
        }, (if (request.quickSearch) SearchPageLoading.emptyDeadline(request.market, true)
            else CaptureBudgets.reader(request.market, request.shopPreview, request.loadMore)).let {
            if (request.quickSearch || request.shopPreview || request.loadMore) it else diagnostics.readerMillis(it)
        })
        // A long-lived SPA may never finish all subresources. Start polling after
        // attachment rather than waiting exclusively for onPageFinished.
        scheduleExtract(500)
    }

    private fun canUseWeb() = !done && !closed && !finishingFailure

    private fun checkFeedHealth() {
        captureHandler.postDelayed({
            if (!canUseWeb()) return@postDelayed
            if (!feedPaused && retainedProducts.isNotEmpty() && SystemClock.elapsedRealtime() - lastFeedReadAt >= 30_000L)
                finishTimeout(SearchPageLoading.PARTIAL_MESSAGE, immediate = true)
            else checkFeedHealth()
        }, 1_000)
    }

    private fun feedProducts() = retainedProducts.values.filter {
        AdultVisibility.visible(it) && it.price in request.minimumPrice..request.maximumPrice
    }

    private fun acceptFeedProducts(found: List<Product>) {
        if (!canUseWeb()) return
        lastFeedReadAt = SystemClock.elapsedRealtime()
        found.forEach { retainedProducts[it.url] = rutenMetadata[it.url] ?: it }
        val available = feedProducts()
        onProgress(available)
        feedPaused = request.market == Market.RUTEN && SearchPageLoading.pauseFeed(feedPaused, available.size - consumedProducts)
        onFeedPaused(feedPaused)
        // Enrich public Ruten fields without reloading or changing the feed cursor.
        if (request.market == Market.RUTEN && !rutenEnrichmentPending) {
            val pending = found.filterNot { it.url in rutenMetadata }.take(40)
            if (pending.isNotEmpty()) {
                rutenEnrichmentPending = true
                captureScope.launch {
                    try {
                        val enriched = RutenSearch.enrich(pending)
                        if (canUseWeb()) {
                            enriched.forEach { rutenMetadata[it.url] = it; retainedProducts[it.url] = it }
                            val enrichedFeed = feedProducts()
                            onProgress(enrichedFeed)
                        }
                    } finally { rutenEnrichmentPending = false; if (canUseWeb()) scheduleExtract(0) }
                }
            }
        }
        if (feedPaused) return
        val remaining = (available.size - consumedProducts).coerceAtLeast(0)
        evaluate(web, "window.__shopRadarFeedFast = ${SearchPageLoading.fastFirstBatch(request.market, remaining)};\n" + feedStateScript) { value ->
            if (!canUseWeb()) return@evaluate
            val state = runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrNull()
            val now = SystemClock.elapsedRealtime()
            val signature = "${retainedProducts.size}:${state?.optString("cursor").orEmpty()}"
            val exhausted = feedEnd.observe(signature, state?.optBoolean("atBottom") == true,
                state?.optBoolean("ready") == true, state?.optBoolean("busy") != false, now)
            if (exhausted && !rutenEnrichmentPending) finishProducts(feedProducts())
            else if (feedEnd.stalled(now)) finishTimeout(SearchPageLoading.PARTIAL_MESSAGE, immediate = true)
            else {
                evaluate(web, "if (window.__shopRadarFeedScroll) window.__shopRadarFeedScroll();", null)
                scheduleExtract(SearchPageLoading.feedPollMillis(request.market, remaining))
            }
        }
    }

    fun retryEmptyPage() {
        if (!canUseWeb() || emptyPageRetried || retainedProducts.isNotEmpty()) return
        emptyPageRetried = true
        if (stopForShopeeAccess(web.url.orEmpty())) return
        // Do not cancel a still-loading navigation. A completed empty public page
        // can reload once in-place without paying for another renderer/attachment.
        if (web.url.isNullOrBlank() || web.progress < 100) { scheduleExtract(0); return }
        evaluate(web, "document.readyState") { state ->
            if (!canUseWeb() || retainedProducts.isNotEmpty()) return@evaluate
            if (state == "\"complete\"" && request.market.accepts(web.url.orEmpty())) {
                diagnostics.step(CaptureStep.LOADING)
                runCatching { web.reload() }.onFailure { finishTimeout("讀取失敗，請按重試。", immediate = true) }
            }
            scheduleExtract(0)
        }
    }

    private fun evaluate(target: WebView, javascript: String, callback: ((String) -> Unit)?): Boolean {
        if (!canUseWeb()) return false
        val error = runCatching {
            if (callback == null) target.evaluateJavascript(javascript, null)
            else target.evaluateJavascript(javascript) { value -> callback.invoke(value) }
        }.exceptionOrNull()
        if (error != null && canUseWeb()) {
            diagnostics.step(CaptureStep.EVALUATION_FAILED)
            finishTimeout("讀取失敗，請按重試。")
        }
        return error == null
    }

    private fun scheduleExtract(waitMs: Long) {
        if (!canUseWeb() || feedPaused || extractionScheduled || (rutenEnrichmentPending && !request.infiniteSearch)) return
        extractionScheduled = true
        captureHandler.postDelayed({
            extractionScheduled = false
            if (canUseWeb()) extract()
        }, waitMs)
    }

    private fun stopForShopeeAccess(url: String): Boolean {
        if (request.market != Market.SHOPEE) return false
        val access = ShopeeLoginPolicy.accessRoute(url) ?: return false
        if (canUseWeb()) {
            if (request.quickSearch && !request.scanSubscription && !request.backgroundRead)
                ShopeeWebConfiguration.requireRecovery(url, request.url)
            diagnostics.event("shopeeOfficialAccessRequired", url)
            diagnostics.step(CaptureStep.AUTH_REQUIRED)
            finishTimeout(ShopeeLoginPolicy.accessMessage(access), immediate = true, accessRequired = true)
        }
        return true
    }

    private fun awaitYahooRedirect(url: String): Boolean {
        if (request.market != Market.YAHOO) return false
        val expired = yahooRedirectWait.expired(url, SystemClock.elapsedRealtime())
        if (!YahooConsentPolicy.isConsentUrl(url)) return false
        // No product extraction or scripted consent decisions on this intermediate page.
        if (canUseWeb() && expired) {
            diagnostics.event("yahooRedirectStalled", url)
            diagnostics.step(CaptureStep.CONSENT_REQUIRED)
            facebookState = "redirect_stalled"
            finishTimeout(YahooConsentPolicy.MESSAGE, immediate = true, accessRequired = true)
        } else scheduleExtract(350)
        return true
    }

    private fun extract() {
        if (!canUseWeb() || feedPaused || (rutenEnrichmentPending && !request.infiniteSearch)) return
        if (awaitYahooRedirect(web.url.orEmpty())) return
        if (stopForShopeeAccess(web.url.orEmpty())) return
        if (request.market == Market.YAHOO && !request.market.accepts(web.url.orEmpty())) { scheduleExtract(350); return }
        if (request.quickSearch && !request.infiniteSearch) evaluate(web,
            "window.__shopRadarWantedPage = 1;\n" + CaptureAssets.read(context, "search-page-navigation.js")) { value ->
            val state = runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrNull()
            state?.optInt("pages")?.takeIf { it > 0 }?.let(onPageCount)
        }
        if (request.quickSearch && request.market == Market.IOPEN && !pageNavigationReady) {
            if (pageNavigationTarget != null) { scheduleExtract(350); return }
            evaluate(web, "window.__shopRadarWantedPage = ${request.requestedPage};\n" + CaptureAssets.read(context, "search-page-navigation.js")) { value ->
                val state = runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrNull()
                state?.optInt("pages")?.takeIf { it > 0 }?.let(onPageCount)
                if (state?.optBoolean("ready") == true) { pageNavigationReady = true; scheduleExtract(0) }
                else {
                    val next = state?.optString("next").orEmpty()
                    if (next.isNotBlank() && request.market.accepts(next)) {
                        pageNavigationTarget = next
                        web.loadUrl(next)
                    }
                    scheduleExtract(350)
                }
            }
            return
        }
        diagnostics.polls++
        diagnostics.step(CaptureStep.EXTRACTING)
        evaluate(web, captureScript) { value ->
            if (!canUseWeb()) return@evaluate
            diagnostics.step(CaptureStep.PARSED)
            diagnostics.samplePage(web)
            if (request.market == Market.YAHOO && !request.infiniteSearch) { scheduleExtract(350); return@evaluate }
            if (isFacebook) { acceptFacebook(value); return@evaluate }
            if (request.market == Market.SHOPEE) { acceptShopee(value); return@evaluate }
            val data = runCatching { JSONArray(JSONTokener(value).nextValue() as String) }.getOrElse {
                diagnostics.parseErrors++
                JSONArray()
            }
            val products = (0 until data.length()).mapNotNull { index ->
                runCatching { Product.parse(data.getJSONObject(index)) }.getOrNull()
            }
                .filter { it.platform == request.market.label }
                .filterNot { request.quickSearch && !request.infiniteSearch && it.url in request.previousPageUrls }
            if (request.market == Market.IOPEN && products.isEmpty()) {
                evaluate(web, "Boolean(window.__shopRadarVerificationRequired)") { requiresVerification ->
                    if (requiresVerification == "true") finishTimeout("iOPEN Mall 回傳網站驗證頁，暫時無法讀取公開商品；這不代表需要登入，請稍後重試。")
                    else scheduleExtract(500)
                }
                return@evaluate
            }
            if (request.market == Market.RUTEN && (request.scanSubscription || request.shopPreview) && products.isNotEmpty()) {
                if (rutenEnrichmentPending) return@evaluate
                rutenEnrichmentPending = true
                captureScope.launch {
                    try {
                        val first = products.firstOrNull { !it.isAdvertisement }
                        if (first != null && !rutenMetadata.containsKey(first.url)) {
                            RutenSearch.enrich(listOf(first)).forEach { rutenMetadata[it.url] = it }
                        }
                        val firstDated = first?.let { rutenMetadata[it.url] }
                        if (firstDated != null && subscriptionBoundary(listOf(firstDated)) != null) {
                            finishProducts(listOf(firstDated)); return@launch
                        }
                        if (firstDated != null && canUseWeb()) onProgress(listOf(firstDated))
                        for (batch in products.filterNot { rutenMetadata.containsKey(it.url) }.chunked(8)) {
                            if (!canUseWeb()) return@launch
                            RutenSearch.enrich(batch).forEach { rutenMetadata[it.url] = it }
                            val enriched = products.map { rutenMetadata[it.url] ?: it }
                            subscriptionBoundary(enriched)?.let { finishProducts(it); return@launch }
                            if (canUseWeb()) onProgress(enriched)
                        }
                        rutenEnrichmentPending = false
                        if (canUseWeb()) acceptProducts(products.map { rutenMetadata[it.url] ?: it })
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (canUseWeb()) finishTimeout("無法確認店家上架時間；保留原有比對基準，請重試。") }
                    finally { rutenEnrichmentPending = false }
                }
                return@evaluate
            }
            acceptProducts(products)
        }
    }
    private fun subscriptionBoundary(products: List<Product>): List<Product>? {
        if (!request.scanSubscription) return null
        val dated = ShopCheckpoint.freeze(products, System.currentTimeMillis())
        for ((index, item) in dated.withIndex()) {
            if (item.isAdvertisement) continue
            val at = ShopCheckpoint.timestamp(item)
            if (item.url in request.subscriptionSeen || request.subscriptionCheckpoint > 0 && at != null && at <= request.subscriptionCheckpoint)
                return dated.take(index + 1)
            if (request.subscriptionCheckpoint > 0 && at == null) return null
        }
        return null
    }
    private fun acceptShopee(value: String) {
        val envelope = runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrNull()
        if (!request.quickSearch && envelope != null &&
            SubscriptionIdentity.sameShop(request.url, envelope.optString("shopUrl")) &&
            SubscriptionIdentity.sameShop(request.url, web.url.orEmpty())) {
            envelope.optString("shopName").trim().takeIf { it.isNotBlank() && it.length <= 120 }?.let(onShopName)
        }
        val state = envelope?.optJSONObject("state")
        if (state == null) {
            diagnostics.parseErrors++
            scheduleExtract(500)
            return
        }
        if (!state.optBoolean("requestMatches") || !state.optBoolean("pageVerified") || state.optBoolean("previousPageOnly")) {
            // Discard both old cards and old stability when the SPA changes routes.
            if (!request.infiniteSearch) retainedProducts.clear()
            pageCompletion.reset()
            shopeePageReady = false
            if (!request.infiniteSearch) onProgress(emptyList())
            scheduleExtract(500)
            return
        }
        val data = envelope.optJSONArray("items") ?: JSONArray()
        state.optInt("totalPages").takeIf { it > 0 }?.let(onPageCount)
        val found = (0 until data.length()).mapNotNull { index ->
            runCatching { Product.parse(data.getJSONObject(index)) }.getOrNull()
        }.filter { it.platform == Market.SHOPEE.label }
        if (request.infiniteSearch) { acceptFeedProducts(found); return }
        if (found.isEmpty()) {
            pageCompletion.reset()
            // Hydration/lazy rendering can temporarily clear an already verified
            // page. Keep its last real snapshot; mismatched pages are cleared above.
            onProgress(retainedProducts.values.toList())
            diagnostics.step(CaptureStep.WAITING_PRODUCTS)
            scheduleExtract(400)
            return
        }
        subscriptionBoundary(found)?.let { finishProducts(it); return }
        val signature = found.joinToString("\n") { "${it.url}:${it.price}:${it.title}" }
        val now = SystemClock.elapsedRealtime()
        // Replace this page's snapshot; merging is for Carousell continuations,
        // not for a numbered page whose early cards can be replaced by hydration.
        retainedProducts.clear()
        found.forEach { retainedProducts[it.url] = it }
        onProgress(found)
        shopeePageReady = state.optBoolean("atBottom") && (state.optBoolean("complete") ||
            state.optInt("slots") == 0 && found.isNotEmpty())
        if (pageCompletion.ready(signature, shopeePageReady, now)) {
            finishProducts(found)
            return
        }
        diagnostics.step(CaptureStep.WAITING_PRODUCTS)
        if (!shopeePageReady) evaluate(web, "if (window.__shopRadarShopeeScroll) window.__shopRadarShopeeScroll();", null)
        scheduleExtract(400)
    }
    private fun acceptProducts(products: List<Product>) {
            if (request.infiniteSearch) { acceptFeedProducts(products); return }
            if (products.isEmpty()) { pageCompletion.reset(); diagnostics.step(CaptureStep.WAITING_PRODUCTS) }
            subscriptionBoundary(products)?.let { finishProducts(it); return }
            if (products.isNotEmpty()) {
                products.forEach { retainedProducts[it.url] = it }
                onProgress(retainedProducts.values.toList())
                val signature = retainedProducts.values.joinToString("\n") { "${it.url}:${it.price}:${it.title}" }
                stableCount = if (productSignature == signature) stableCount + 1 else 0
                productSignature = signature
                previousCount = products.size
                // Carousell may append results a moment after its "顯示更多結果"
                // button is pressed.  Give that page several stable samples before
                // publishing the result set.
                val stableTarget = if (request.quickSearch) 6 else if (retainedProducts.size < 15) 10 else 6
                if (request.loadMore) {
                    evaluate(web, "Boolean(window.__shopRadarMoreDone) || (!window.__shopRadarMorePending && !Array.from(document.querySelectorAll('button,[role=button],a')).some(n => /顯示更多|載入更多|show more|load more/i.test(n.textContent || '')))", { state ->
                        if (state == "true" && stableCount >= 3 && (SystemClock.elapsedRealtime() - startedAt >= 8000)) finishProducts(retainedProducts.values.toList())
                        else scheduleExtract(350)
                    })
                } else if (request.quickSearch) {
                    evaluate(web, "document.readyState === 'complete' && !Array.from(document.querySelectorAll('[aria-busy=true]')).some(n => n.getClientRects().length)") { ready ->
                        if (canUseWeb() && pageCompletion.ready(signature, ready == "true", SystemClock.elapsedRealtime())) finishProducts(retainedProducts.values.toList())
                        else scheduleExtract(350)
                    }
                } else if (stableCount >= stableTarget || attempts >= 40) finishProducts(retainedProducts.values.toList())
                else {
                    attempts++
                    if (evaluate(web, "window.scrollTo(0, document.body && document.body.scrollHeight)", null)) scheduleExtract(500)
                }
            } else if (++attempts >= 40 && !extendedShopeeSearch && !request.quickSearch) {
                if (retainedProducts.isNotEmpty()) finishProducts(retainedProducts.values.toList())
                else finishTimeout("沒有讀到公開商品；頁面可能尚未載入，或要求登入／驗證，請重試。")
            }
            else scheduleExtract(500)
    }
    private fun acceptFacebook(value: String) {
        val data = runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrNull()
        if (data == null) { diagnostics.parseErrors++; scheduleExtract(1000); return }
        diagnostics.readyState = data.optString("readyState", "unknown").take(24)
        diagnostics.scriptCount = data.optInt("scriptCount")
        diagnostics.imageCount = data.optInt("imageCount")
        diagnostics.structuredCandidates = data.optInt("structuredCandidates")
        diagnostics.pageVisibility = if (data.optString("visibility") == "visible") "visible" else "hidden"
        diagnostics.viewport = "${data.optInt("viewportWidth")} × ${data.optInt("viewportHeight")}"
        facebookState = data.optString("state", "loading")
        facebookCandidates = maxOf(facebookCandidates, data.optInt("candidateCount"))
        val items = data.optJSONArray("items") ?: JSONArray()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val parsed = runCatching { Product.parse(item) }.getOrNull() ?: continue
            if (parsed.platform != Market.FACEBOOK.label) continue
            FacebookResolvedLinks.remember(parsed.url, item.optString("resolvedUrl"))
            val product = FacebookListingMetadata.apply(parsed, System.currentTimeMillis())
            val previous = facebookItems.remove(parsed.url)
            if (request.infiniteSearch || facebookItems.size < 80 || facebookItems.containsKey(product.url))
                facebookItems[product.url] = FacebookProductMerge.merge(facebookItems[product.url] ?: previous, product)
        }
        if (!request.infiniteSearch) onProgress(facebookItems.values.toList())
        if (facebookState == "overlay_dismissed") { scheduleExtract(800); return }
        if (facebookState == "login" && facebookItems.isEmpty()) {
            finishTimeout("Facebook 顯示登入提示，關閉後仍沒有公開商品。請重試。")
            return
        }
        if (facebookState == "empty") { finishProducts(emptyList()); return }
        if (request.infiniteSearch) { acceptFeedProducts(facebookItems.values.toList()); return }
        if (facebookItems.isNotEmpty()) {
            stableCount = if (previousCount == facebookItems.size) stableCount + 1 else 0
            previousCount = facebookItems.size
            if (facebookItems.size >= 80 || stableCount >= 8) { finishProducts(facebookItems.values.toList()); return }
            evaluate(web, "if (window.__shopRadarFacebookScroll) window.__shopRadarFacebookScroll(); else window.scrollBy(0, Math.max(300, window.innerHeight * 0.75));", null)
        }
        if (facebookItems.isEmpty() && facebookState == "loading")
            evaluate(web, "if (window.__shopRadarFacebookScroll) window.__shopRadarFacebookScroll(); else window.scrollBy(0, Math.max(300, window.innerHeight * 0.5));", null)
        scheduleExtract(900)
    }
    private fun previewYahooDates(json: String) {
        if (!canUseWeb() || !Market.YAHOO.accepts(web.url.orEmpty())) return
        val data = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        for (index in 0 until data.length()) {
            val p = runCatching { Product.parse(data.getJSONObject(index)) }.getOrNull() ?: continue
            if (p.platform == Market.YAHOO.label) retainedProducts[p.url] = p
        }
        onProgress(retainedProducts.values.toList())
        subscriptionBoundary(retainedProducts.values.toList())?.let { finishProducts(it) }
    }
    private fun acceptYahooDates(json: String) {
        if (!canUseWeb() || !Market.YAHOO.accepts(web.url.orEmpty())) return
        val data = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        val products = (0 until data.length()).mapNotNull { index ->
            runCatching { Product.parse(data.getJSONObject(index)) }.getOrNull()
        }
            .filter { it.platform == Market.YAHOO.label }
        finishProducts(products)
    }
    private fun finishProducts(products: List<Product>) {
        if (done || finishingFailure) return
        if (request.market == Market.SHOPEE && request.quickSearch && products.isNotEmpty())
            ShopeeWebConfiguration.searchConfirmed(request.url)
        diagnostics.step(CaptureStep.SUCCESS)
        done = true; captureHandler.removeCallbacksAndMessages(null)
        if (CaptureDebugPolicy.visibleReader(request)) onDiagnostic("蝦皮 Debug 搜尋完成，共 ${products.size} 件商品。",
            diagnostics.report(SystemClock.elapsedRealtime() - startedAt, web.url, "ready", facebookCandidates, products.size))
        onProducts(products)
    }
    fun stopDebug() {
        if (CaptureDebugPolicy.visibleReader(request)) finishTimeout("已停止蝦皮 Debug 診斷。", stopped = true)
    }
    private fun finishTimeout(message: String, stopped: Boolean = false, immediate: Boolean = false, accessRequired: Boolean = false) {
        if (done || finishingFailure) return
        // Auth navigation is a terminal access requirement, not an elapsed-time timeout.
        if (!accessRequired) diagnostics.step(if (stopped) CaptureStep.STOPPED else CaptureStep.TIMEOUT)
        finishingFailure = true
        captureHandler.removeCallbacksAndMessages(null)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val report = { diagnostics.report(elapsedMs, web.url, facebookState,
            facebookCandidates, if (isFacebook) facebookItems.size else retainedProducts.size) }
        if (accessRequired) {
            diagnostics.finishAccess(report) { completedReport ->
                if (!done && !closed) {
                    done = true
                    if (completedReport.isNotBlank()) onDiagnostic(message, completedReport)
                    onTimeout(message)
                }
            }
            return
        }
        if (immediate) {
            done = true
            val currentReport = report()
            if (currentReport.isNotBlank()) onDiagnostic(message, currentReport)
            onTimeout(message)
            return
        }
        // Keep the live renderer attached until the debug snapshot callback.
        // Release's variant invokes this immediately without reading HTML.
        diagnostics.finishFailure(web, report) { completedReport ->
            if (!done && !closed) {
                done = true; captureHandler.removeCallbacksAndMessages(null)
                if (completedReport.isNotBlank()) onDiagnostic(message, completedReport)
                onTimeout(message)
            }
        }
    }
    fun close() {
        if (closed) return
        closed = true; done = true; captureHandler.removeCallbacksAndMessages(null)
        diagnostics.close()
        runCatching { web.stopLoading() }
        runCatching { web.destroy() }
    }
}

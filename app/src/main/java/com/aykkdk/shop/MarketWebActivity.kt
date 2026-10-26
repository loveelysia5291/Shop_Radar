package com.aykkdk.shop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.net.http.SslError
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File

private class YahooSearchBridge(private val onData: (String) -> Unit) {
    @JavascriptInterface
    fun deliver(json: String) = Handler(Looper.getMainLooper()).post { onData.invoke(json) }
}

class MarketWebActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val pageHandler = Handler(Looper.getMainLooper())
    private var warning by mutableStateOf<String?>(null)
    private var status by mutableStateOf("正在載入搜尋頁…")
    private var attempts = 0
    private var generation = 0
    private var previousCount = 0
    private var stableCount = 0
    private var delivered = false
    private lateinit var market: Market
    private var searching = false
    private var loadProgress by mutableIntStateOf(0)
    private var pageFailed = false
    private var providerInfo = "系統 WebView"
    private var yahooWaitingGeneration = -1
    private var recoverYahoo = false
    private var yahooReturnUrl = ""
    private var yahooConsentPage by mutableStateOf(false)
    private var yahooRetryReady by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        market = Market.values().firstOrNull { it.name == intent.getStringExtra("market") } ?: Market.SHOPEE
        searching = intent.getBooleanExtra("search", false)
        val url = intent.getStringExtra("url") ?: "https://shopee.tw/buyer/login"
        if (!market.accepts(url)) { finish(); return }
        recoverYahoo = intent.getBooleanExtra("yahooConsent", false)
        if (recoverYahoo && (market != Market.YAHOO || searching)) { finish(); return }
        yahooReturnUrl = url
        if (market == Market.SHOPEE && !searching) {
            startActivity(Intent(this, ShopeeLoginActivity::class.java))
            finish()
            return
        }
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 26) {
            providerInfo = WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "系統未提供 WebView 版本資訊"
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 100
            settings.userAgentString = WebSettings.getDefaultUserAgent(this@MarketWebActivity)
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, market == Market.SHOPEE)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (market == Market.SHOPEE) ShopeeWebConfiguration.configure(this, this@MarketWebActivity)
            if (market == Market.YAHOO && searching) addJavascriptInterface(YahooSearchBridge(::acceptYahooProducts), "ShopRadarYahoo")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) { loadProgress = value }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val uri = request.url
                    val allowed = if (market == Market.YAHOO) YahooConsentPolicy.allowsVisiblePage(uri.toString())
                        else market.accepts(uri.toString())
                    if (!allowed) warning = "連結將離開 ${market.label}。請留在原平台完成搜尋。"
                    return !allowed
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    pageFailed = false
                    yahooConsentPage = market == Market.YAHOO && YahooConsentPolicy.isConsentUrl(url)
                    yahooRetryReady = false
                    generation++; pageHandler.removeCallbacksAndMessages(null)
                    yahooWaitingGeneration = -1
                    attempts = 0; previousCount = 0; stableCount = 0
                    status = if (yahooConsentPage) "正在等待 Yahoo 轉址；若出現隱私選項，請自行選擇。" else "正在載入 ${market.label}…"
                }
                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    if (pageFailed) return
                    yahooConsentPage = market == Market.YAHOO && YahooConsentPolicy.isConsentUrl(url)
                    if (yahooConsentPage) {
                        status = "正在等待 Yahoo 轉址；若出現隱私選項，請自行選擇。"
                        return
                    }
                    yahooRetryReady = recoverYahoo && YahooConsentPolicy.canRetryFrom(url, yahooReturnUrl)
                    status = if (recoverYahoo) "已回到原網頁，按右上角「重新讀取」即可；不需要額外認證。"
                        else if (searching) "正在讀取商品，最多等待約 25 秒。" else "完成登入後按返回即可。"
                    if (searching) pageHandler.postDelayed({ extract(false, generation) }, 2500)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail("連線失敗：${error.description}。可按重新載入。")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) fail("${market.label} 回應 HTTP ${response.statusCode}。若需要登入或驗證，請在頁面自行完成後再試。")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel(); fail("網站安全連線失敗，無法載入商品。")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fail("系統 WebView 已停止，請返回後重新開啟。可在 Play 商店更新 Android System WebView／Chrome。")
                    return true
                }
            }
            loadUrl(url)
        }
        setContent {
            BackHandler { if (searching) stopSearch() else if (web.canGoBack()) web.goBack() else finish() }
            MaterialTheme {
                Scaffold(topBar = {
                    TopAppBar(title = { Text(if (recoverYahoo) "Y拍網頁" else if (searching) "抓取商品" else market.label) }, navigationIcon = {
                        TextButton(onClick = { if (searching) stopSearch() else finish() }) { Text(if (searching) "停止" else "返回") }
                    },
                        actions = {
                            if (recoverYahoo) TextButton(enabled = yahooRetryReady && !pageFailed, onClick = {
                                if (YahooConsentPolicy.canRetryFrom(web.url.orEmpty(), yahooReturnUrl)) {
                                    CookieManager.getInstance().flush()
                                    setResult(Activity.RESULT_OK)
                                    finish()
                                }
                            }) { Text("重新讀取") }
                            if (!searching) {
                                TextButton(onClick = { warning = null; web.reload() }) { Text("重試") }
                                var menu by remember { mutableStateOf(false) }
                                TextButton(onClick = { menu = true }) { Text("更多") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("WebView 版本") }, onClick = { menu = false; warning = "目前使用：$providerInfo。可從 Play 商店更新 Android System WebView 或 Chrome。" })
                                    DropdownMenuItem(text = { Text("以系統瀏覽器開啟") }, onClick = {
                                        menu = false
                                        val current = web.url.orEmpty()
                                        if (market.accepts(current)) {
                                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current))) }
                                                .onFailure { warning = "找不到可用的系統瀏覽器。" }
                                            status = "外部瀏覽器登入狀態不會自動同步到內建 WebView。"
                                        }
                                    })
                                }
                            }
                        })
                }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                        if (loadProgress < 100 && !pageFailed) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (searching && !pageFailed && !yahooConsentPage) "抓取中，請稍候…" else status, Modifier.padding(12.dp))
                        // Keep the page rendered for DOM extraction but do not expose a partial
                        // result list. The main screen shows results only after this activity returns.
                        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth().alpha(if (searching && !yahooConsentPage) 0f else 1f))
                    }
                }
                warning?.let { message -> AlertDialog(onDismissRequest = { warning = null }, title = { Text("搜尋提醒") },
                    text = { Text(message) }, confirmButton = { TextButton(onClick = { warning = null }) { Text("知道了") } }) }
            }
        }
    }
    private fun fail(message: String) {
        pageFailed = true
        generation++; pageHandler.removeCallbacksAndMessages(null)
        warning = message; status = message
    }
    private fun extract(manual: Boolean, token: Int) {
        if (delivered || isDestroyed || token != generation) return
        if (!market.accepts(web.url.orEmpty())) { if (manual) fail("請回到 ${market.label} 的商品結果頁。"); return }
        val script = CaptureAssets.script(this, market)
        if (market == Market.YAHOO) {
            // Yahoo detail-page enrichment is asynchronous.  The JavaScript bridge
            // below returns the verified product objects instead of the page's
            // category/filter cards.
            yahooWaitingGeneration = token
            web.evaluateJavascript(script, null)
            status = "抓取中，正在確認 Y拍商品資料…"
            pageHandler.postDelayed({
                if (!delivered && !isDestroyed && yahooWaitingGeneration == generation) {
                    fail("Y拍商品資料讀取逾時，請按重試。")
                }
            }, 30_000)
            return
        }
        web.evaluateJavascript(script) { value ->
            if (isDestroyed || delivered || token != generation) return@evaluateJavascript
            val data = runCatching { JSONArray(JSONTokener(value).nextValue() as String) }.getOrDefault(JSONArray())
            val products = (0 until data.length()).mapNotNull { Product.parse(data.getJSONObject(it)) }.filter { it.platform == market.label }
            if (products.isNotEmpty()) {
                stableCount = if (previousCount == products.size) stableCount + 1 else 0
                previousCount = products.size
                status = "抓取中，正在確認頁面資料…"
                if (manual || stableCount >= 2 || attempts >= 20) {
                    File(cacheDir, "search-result.json").writeText(JSONArray(products.map { it.json() }).toString())
                    delivered = true
                    setResult(Activity.RESULT_OK)
                    finish(); return@evaluateJavascript
                }
            }
            attempts++
            if (manual || attempts >= 20) {
                fail("未讀取到可辨識商品。可能沒有結果、尚未載入、需要驗證，或頁面格式已變更。請停止後確認登入狀態或重新搜尋。")
            } else pageHandler.postDelayed({ extract(false, token) }, 1000)
        }
    }
    private fun acceptYahooProducts(json: String) {
        if (delivered || isDestroyed || !searching || market != Market.YAHOO || yahooWaitingGeneration != generation) return
        val data = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        val products = (0 until data.length()).mapNotNull { index ->
            runCatching { Product.parse(data.getJSONObject(index)) }.getOrNull()
        }.filter { it.platform == Market.YAHOO.label }
        if (products.isEmpty()) {
            fail("Y拍沒有讀到有效商品；分類或篩選文字已略過，請按重試。")
            return
        }
        File(cacheDir, "search-result.json").writeText(JSONArray(products.map { it.json() }).toString())
        delivered = true
        pageHandler.removeCallbacksAndMessages(null)
        setResult(Activity.RESULT_OK)
        finish()
    }
    override fun onDestroy() {
        pageHandler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) { CookieManager.getInstance().flush(); web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
    private fun stopSearch() {
        generation++
        pageHandler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.stopLoading()
        setResult(Activity.RESULT_CANCELED, Intent().putExtra("stopped", true))
        finish()
    }
    companion object {
        fun searchIntent(context: Context, market: Market, keyword: String, min: String, max: String, yahooCategoryId: String? = null) =
            Intent(context, MarketWebActivity::class.java).putExtra("market", market.name)
                .putExtra("url", market.search(keyword, min, max, yahooCategoryId)).putExtra("search", true)
    }
}

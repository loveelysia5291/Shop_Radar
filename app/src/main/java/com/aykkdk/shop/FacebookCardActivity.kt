package com.aykkdk.shop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener

/** Legacy WebLite references require a verified real URL before adding a subscription. */
class FacebookCardActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private var closed = false
    private var message by mutableStateOf<String?>(null)
    private var completed = false
    private var retryable = true
    private lateinit var product: Product
    private lateinit var reference: FacebookCardReference
    private lateinit var clickScript: String
    private var popup by mutableStateOf<WebView?>(null)
    private var lastCardClick = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = runCatching { Product.parse(JSONObject(intent.getStringExtra("product") ?: "{}")) }.getOrNull()
        val ref = input?.let { FacebookCardReference.parse(it.url) }
        if (input == null || ref == null) { finish(); return }
        product = input; reference = ref
        clickScript = "window.__shopRadarFacebookCard = " + JSONObject().put("label", ref.label).put("image", ref.image) +
            ";\n" + CaptureAssets.read(this, "resolve-facebook-card.js")
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            // Match the search reader's layout; changing UA would change the card labels.
            settings.userAgentString = WebSettings.getDefaultUserAgent(this@FacebookCardActivity)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setOnTouchListener { _, _ -> true }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    // Only a recent explicitly requested listing click may open a window.
                    // The child remains behind native UI and only yields a verified item URL.
                    if (closed || completed || message != null || popup != null ||
                        (!isUserGesture && (lastCardClick == 0L || SystemClock.elapsedRealtime() - lastCardClick > 2000))) return false
                    val child = WebView(this@FacebookCardActivity).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!request.isForMainFrame) return true
                                val url = request.url.toString()
                                if (Market.fromPublicProductUrl(url) == Market.FACEBOOK) resolve(url)
                                else fail("Facebook 新視窗沒有提供有效商品網址，請重試。")
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) {
                                if (url == "about:blank") return
                                view.stopLoading()
                                if (Market.fromPublicProductUrl(url) == Market.FACEBOOK) resolve(url)
                                else fail("Facebook 新視窗沒有提供有效商品網址，請重試。")
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                retryable = false
                                fail("系統 WebView 已停止，請重試。")
                                return true
                            }
                        }
                    }
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: run { child.destroy(); return false }
                    popup = child
                    transport.webView = child
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val url = request.url.toString()
                    if (Market.fromPublicProductUrl(url) == Market.FACEBOOK) { resolve(url); return true }
                    if (!Market.FACEBOOK.accepts(url) || !request.url.path.orEmpty().startsWith("/marketplace/")) {
                        fail("Facebook 要求登入或驗證，尚未取得商品網址；沒有新增訂閱。")
                        return true
                    }
                    return false
                }
                override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) {
                    if (Market.fromPublicProductUrl(url) == Market.FACEBOOK) resolve(url)
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    if (Market.fromPublicProductUrl(url) == Market.FACEBOOK) resolve(url)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail("Facebook 商品頁讀取失敗，請重試。")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) fail("Facebook 商品頁回應 HTTP ${response.statusCode}，請重試。")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    retryable = false
                    fail("系統 WebView 已停止，請重試。")
                    return true
                }
            }
        }
        setContent {
            val mode = remember { getSharedPreferences("appearance", 0).getString("mode", if (Build.VERSION.SDK_INT >= 28) "system" else "light") }
            val dark = mode == "dark" || (mode == "system" && Build.VERSION.SDK_INT >= 28 && isSystemInDarkTheme())
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                    popup?.let { child -> AndroidView(factory = { child }, modifier = Modifier.fillMaxSize()) }
                    // Never reveal the reader, including redirects or sign-in screens.
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(contentAlignment = Alignment.Center) {
                            if (message == null) AlertDialog(onDismissRequest = {}, title = { Text("正在讀取商品") },
                                text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    CircularProgressIndicator(); Text("正在取得 Facebook 商品網址…")
                                } }, confirmButton = { TextButton(onClick = { finish() }) { Text("停止") } })
                        }
                    }
                }
                message?.let { text -> AlertDialog(onDismissRequest = { finish() },
                    title = { Text(if (text == SubscriptionIdentity.DUPLICATE_MESSAGE) "提醒" else if (completed) "已訂閱商品" else "無法讀取商品") }, text = { Text(text) },
                    confirmButton = { TextButton(onClick = { finish() }) { Text("知道了") } },
                    dismissButton = { if (!completed && retryable) TextButton(onClick = { startRead() }) { Text("重試") } }) }
            }
        }
        startRead()
    }

    private fun startRead() {
        if (closed) return
        handler.removeCallbacksAndMessages(null)
        disposePopup()
        message = null; completed = false; lastCardClick = 0
        if (runCatching { web.loadUrl(reference.source) }.isFailure) {
            fail("無法載入 Facebook 搜尋頁，請重試。")
            return
        }
        handler.postDelayed({ fail("找不到原商品卡片或尚未取得商品網址，請重新搜尋後重試；沒有新增訂閱。") }, 20_000)
        handler.postDelayed(::poll, 500)
    }

    private fun poll() {
        if (closed || message != null || completed) return
        if (!Market.FACEBOOK.accepts(web.url.orEmpty())) { handler.postDelayed(::poll, 350); return }
        if (Market.fromPublicProductUrl(web.url.orEmpty()) == Market.FACEBOOK) { resolve(web.url.orEmpty()); return }
        runCatching { web.evaluateJavascript(clickScript) { raw ->
            if (closed || message != null || completed) return@evaluateJavascript
            val result = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            if (result == "clicked") lastCardClick = SystemClock.elapsedRealtime()
            if (result == "login") fail("Facebook 要求登入或驗證，尚未取得商品網址；沒有新增訂閱。")
            else if (Market.fromPublicProductUrl(result) == Market.FACEBOOK) resolve(result)
            else handler.postDelayed(::poll, 350)
        } }.onFailure { fail("無法解析 Facebook 商品網址，請重試。") }
    }

    private fun resolve(raw: String) {
        if (closed || completed || message != null) return
        val url = Market.FACEBOOK.normalizePublicProductUrl(raw) ?: return
        FacebookResolvedLinks.remember(product.url, url)
        handler.removeCallbacksAndMessages(null)
        web.stopLoading()
        popup?.let { runCatching { it.stopLoading() } }
        if (intent.getBooleanExtra("subscribe", false)) {
            runCatching {
                val store = ProductSubscriptionStore(this)
                store.add(product.copy(url = url))
                check(store.prefs.contains(url))
            }
                .onSuccess { completed = true; message = product.title }
                .onFailure {
                    if (it.message == SubscriptionIdentity.DUPLICATE_MESSAGE) {
                        retryable = false
                        fail(SubscriptionIdentity.DUPLICATE_MESSAGE)
                    } else fail("無法新增商品訂閱，請重試。")
                }
        } else {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onSuccess { completed = true; finish() }
                .onFailure { fail("無法開啟商品網址。") }
        }
    }

    private fun fail(text: String) {
        if (closed || completed || message != null) return
        handler.removeCallbacksAndMessages(null)
        message = text
        if (::web.isInitialized) runCatching { web.stopLoading() }
        popup?.let { runCatching { it.stopLoading() } }
    }

    override fun onDestroy() {
        closed = true; handler.removeCallbacksAndMessages(null)
        disposePopup()
        if (::web.isInitialized) {
            runCatching {
                web.stopLoading(); (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.webViewClient = WebViewClient(); web.destroy()
            }
        }
        super.onDestroy()
    }

    private fun disposePopup() {
        val child = popup ?: return
        popup = null
        runCatching { child.stopLoading(); (child.parent as? android.view.ViewGroup)?.removeView(child); child.webViewClient = WebViewClient(); child.destroy() }
    }

    companion object {
        internal fun open(context: Context, product: Product, subscribe: Boolean = false) {
            require(FacebookCardReference.parse(product.url) != null)
            context.startActivity(Intent(context, FacebookCardActivity::class.java)
                .putExtra("product", product.json().toString()).putExtra("subscribe", subscribe))
        }
    }
}

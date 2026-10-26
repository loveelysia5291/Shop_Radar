package com.aykkdk.shop

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout

/** Visible, unmodified official sign-in page. No DOM scripts, bridges or HTML capture. */
class ShopeeLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var webAlive = true
    private var pageFailed = false
    private var initialUrl = ShopeeLoginPolicy.LOGIN_URL
    private var recoveryFlow = false
    private var returnSearchUrl = ""
    private var searchReturned = false
    private var homeReturned = false
    private var accessStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recovery = ShopeeLoginPolicy.recovery(intent.getStringExtra(EXTRA_URL).orEmpty(), intent.getStringExtra(EXTRA_SEARCH_URL).orEmpty())
        initialUrl = recovery?.officialUrl ?: ShopeeLoginPolicy.LOGIN_URL
        recoveryFlow = recovery != null
        returnSearchUrl = recovery?.searchUrl.orEmpty()
        accessStarted = savedInstanceState?.getBoolean("accessStarted") == true ||
            ShopeeLoginPolicy.requiresAuthentication(initialUrl)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val mode = getSharedPreferences("appearance", MODE_PRIVATE).getString("mode", "system")
        val dark = mode == "dark" || mode == "system" &&
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val background = if (dark) Color.rgb(22, 25, 32) else Color.rgb(243, 246, 252)
        val foreground = if (dark) Color.WHITE else Color.rgb(26, 30, 40)
        window.statusBarColor = background
        window.navigationBarColor = background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(background) }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        toolbar.addView(toolbarIcon(R.drawable.ic_browser_back, "返回搜尋", foreground) { complete() })
        toolbar.addView(TextView(this).apply {
            text = if (recovery?.route == ShopeeLoginPolicy.AccessRoute.VERIFICATION) "蝦皮驗證" else "登入蝦皮"
            textSize = 18f; setTextColor(foreground)
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(toolbarIcon(R.drawable.ic_browser_refresh, "刷新", foreground) {
            if (webAlive) web.reload() else finish()
        })
        root.addView(toolbar)
        status = TextView(this).apply {
            text = "請在官方頁面完成登入／驗證，完成後會自動返回搜尋。"
            textSize = 13f; setTextColor(foreground); setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFocusable = true; isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            ShopeeWebConfiguration.configure(this, this@ShopeeLoginActivity)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    this@ShopeeLoginActivity.progress.progress = newProgress
                    this@ShopeeLoginActivity.progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (!webAlive || isFinishing) return
                    pageFailed = false
                    searchReturned = false
                    homeReturned = false
                    if (ShopeeLoginPolicy.requiresAuthentication(url)) accessStarted = true
                    status.text = "正在載入蝦皮官方頁面…"
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame || ShopeeLoginPolicy.officialPage(request.url.toString())) return false
                    // Third-party account providers may reject embedded sign-in. Do not
                    // send credentials to unknown pages or pretend external cookies sync.
                    if (request.hasGesture() && request.url.scheme == "https") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        status.text = "外部瀏覽器的登入不會同步到 App；請在此頁使用蝦皮帳密登入。"
                    } else status.text = "請在蝦皮官方頁面使用帳號、密碼或簡訊登入。"
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!webAlive || isFinishing) return
                    CookieManager.getInstance().flush()
                    searchReturned = ShopeeLoginPolicy.completedSearch(url, view.url.orEmpty(), returnSearchUrl, accessStarted, pageFailed)
                    homeReturned = ShopeeLoginPolicy.completedHome(url, view.url.orEmpty(), accessStarted, pageFailed)
                    if (homeReturned) ShopeeWebConfiguration.loginReturnedHome()
                    if (homeReturned || searchReturned) {
                        // Return RESULT_OK through the launcher, which resumes the frozen search.
                        complete()
                        return
                    }
                    if (!pageFailed) status.text = "請在官方頁面完成登入／驗證，完成後會自動返回搜尋。"
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) { pageFailed = true; status.text = "登入頁載入失敗（${error.errorCode}），可按重載。" }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) { pageFailed = true; status.text = "登入頁回應 HTTP ${response.statusCode}，請稍後重載。" }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel(); pageFailed = true; status.text = "網站安全連線失敗，已停止載入。"
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    webAlive = false
                    root.removeView(view)
                    runCatching { view.destroy() }
                    status.text = "系統 WebView 已停止，請返回再開啟登入頁。"
                    return true
                }
            }
        }
        root.addView(web)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(root)
        // Load only after attachment and measurement: never a zero-size/offscreen login renderer.
        web.doOnLayout {
            val restored = savedInstanceState?.getBundle("browser")?.let { state -> web.restoreState(state) }
            if (restored == null) web.loadUrl(initialUrl)
        }
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Verification flows often redirect repeatedly; Back must return to the app,
                // not trap the user cycling through the site's redirect history.
                if (!recoveryFlow && !homeReturned && !searchReturned && webAlive && web.canGoBack()) web.goBack() else complete()
            }
        })
    }

    private fun complete() {
        if (isFinishing) return
        CookieManager.getInstance().flush()
        // Home completes login too; MainActivity retries the frozen search directly.
        // A verification/login page itself still cannot complete a recovery.
        if (webAlive && !pageFailed && (homeReturned || if (recoveryFlow) searchReturned &&
            ShopeeLoginPolicy.canResumeSearch(web.url.orEmpty(), returnSearchUrl) else
            !ShopeeLoginPolicy.requiresAuthentication(web.url.orEmpty()) && ShopeeWebConfiguration.canSearch()))
            setResult(RESULT_OK)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (webAlive) outState.putBundle("browser", Bundle().also { web.saveState(it) })
        outState.putBoolean("accessStarted", accessStarted)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        CookieManager.getInstance().flush()
        if (webAlive) web.onPause()
        super.onPause()
    }
    override fun onResume() { super.onResume(); if (::web.isInitialized && webAlive) web.onResume() }
    override fun onDestroy() {
        if (::web.isInitialized && webAlive) {
            webAlive = false
            (web.parent as? ViewGroup)?.removeView(web)
            runCatching { web.stopLoading(); web.destroy() }
        }
        super.onDestroy()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toolbarIcon(drawable: Int, description: String, color: Int, click: () -> Unit) = ImageButton(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setImageResource(drawable)
        setColorFilter(color)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = description
        setOnClickListener { click() }
    }
    companion object {
        const val EXTRA_URL = "shopeeOfficialRecoveryUrl"
        const val EXTRA_SEARCH_URL = "shopeeRecoverySearchUrl"
    }
}

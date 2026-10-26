package com.aykkdk.shop

import java.net.URI
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.json.JSONTokener

/** Debug-only counters; public search HTML is kept locally by a bounded session. */
internal class CaptureDiagnostics(private val platform: String, url: String, private val appVersion: String) {
    private var html: MarketplaceHtmlDebugSession? = null
    private var snapshotState: String? = null
    private var snapshotCandidates: Int? = null
    private var cardCounts: List<Int>? = null
    private var publicPageState: JSONObject? = null
    private val initialUrl = safeUrl(url)
    private val events = ArrayDeque<String>()
    private val trace = CaptureTrace()
    private val scriptErrorCategories = linkedMapOf<String, Int>()
    private var engineMajor: Int? = null
    private var closed = false
    private var sampling = false
    var liveText by mutableStateOf("")
        private set

    fun step(value: CaptureStep) {
        if (closed || platform != "蝦皮") return
        trace.step(value)
        updateLiveText()
    }
    private fun updateLiveText() {
        liveText = "${CaptureTrace.label(trace.current)}\n頁面：$readyState · 擷取：$polls 次 · 商品候選：${snapshotCandidates?.toString() ?: "未量測"} · 解析失敗：$parseErrors"
    }
    /** Read counters only; never read form values, body text or credentials. */
    fun samplePage(web: android.webkit.WebView) {
        if (closed || sampling || platform != "蝦皮") return
        sampling = true
        runCatching {
            web.evaluateJavascript("""JSON.stringify({readyState:document.readyState,
                scriptCount:document.scripts.length,imageCount:document.images.length,
                visibility:document.visibilityState,viewport:{width:innerWidth,height:innerHeight},
                publicCardStats:window.__shopRadarPublicCardStats,
                publicPageState:window.__shopRadarShopeePageState,
                shopeeProductLinks:Array.from(document.querySelectorAll('a[href]')).filter(a => /(?:-i\.\d+\.\d+|\/product\/\d+\/\d+)/.test(a.getAttribute('href')||'')).length})""") { raw ->
                sampling = false
                if (!closed) runCatching {
                    val data = JSONObject(JSONTokener(raw).nextValue() as String)
                    val stats = data.optJSONObject("publicCardStats")
                    val page = data.optJSONObject("publicPageState")
                    data.put("captureState", when {
                        stats != null && !stats.optBoolean("routeAllowed") -> "unsupported_page"
                        page != null && !page.optBoolean("requestMatches") -> "requested_route_loading"
                        page != null && (!page.optBoolean("pageVerified") || page.optBoolean("previousPageOnly")) -> "previous_page_cards"
                        page != null && !page.optBoolean("complete") && (stats?.optInt("parsed") ?: 0) > 0 -> "lazy_cards_loading"
                        stats != null && stats.optInt("parsed") > 0 -> "ready"
                        stats != null && stats.optInt("candidates") > 0 -> "card_fields_missing"
                        data.optInt("shopeeProductLinks") > 0 -> "product_links_present"
                        else -> "no_product_links"
                    })
                    acceptSnapshot(data)
                }
            }
        }.onFailure { sampling = false }
    }
    var polls = 0
    var parseErrors = 0
    var readyState = "unknown"
    var javascriptErrors = 0
    fun consoleError(message: String, userAgent: String) {
        if (closed) return
        javascriptErrors++
        val category = WebScriptDiagnostics.category(message)
        scriptErrorCategories[category] = ((scriptErrorCategories[category] ?: 0) + 1).coerceAtMost(10_000)
        engineMajor = WebScriptDiagnostics.engineMajor(userAgent)
    }
    var scriptCount = 0
    var imageCount = 0
    var structuredCandidates = 0
    var pageVisibility = "unknown"
    var viewport = "unknown"

    fun startHtmlCapture(context: android.content.Context, web: android.webkit.WebView, enabled: Boolean) {
        if (enabled) html = MarketplaceHtmlDebugSession(context, web, platform, ::acceptSnapshot)
    }
    internal fun acceptSnapshot(data: org.json.JSONObject) {
        if (platform == "蝦皮") {
                readyState = data.optString("readyState", "unknown").take(24)
                scriptCount = data.optInt("scriptCount")
                imageCount = data.optInt("imageCount")
                pageVisibility = data.optString("visibility", "unknown").take(24)
                val size = data.optJSONObject("viewport")
                viewport = if (size == null) "unknown" else "${size.optInt("width")} × ${size.optInt("height")}"
                snapshotState = data.optString("captureState", "unknown")
                snapshotCandidates = data.optInt("shopeeProductLinks")
                publicPageState = data.optJSONObject("publicPageState")
                data.optJSONObject("publicCardStats")?.let { stats ->
                    cardCounts = listOf("candidates", "parsed", "missingTitle", "missingPrice")
                        .map { stats.optInt(it).coerceAtLeast(0) }
                }
                updateLiveText()
        }
    }
    fun readerMillis(normal: Long): Long = if (html != null) 20_000L else normal
    fun finishFailure(web: android.webkit.WebView, report: () -> String, callback: (String) -> Unit) {
        val session = html
        if (session == null) callback(report()) else session.finish(web, report, callback)
    }
    fun finishAccess(report: () -> String, callback: (String) -> Unit) {
        val session = html
        if (session == null) callback(report()) else session.finishAccess(report, callback)
    }
    fun close() { closed = true; html?.close() }

    fun event(label: String, url: String) {
        if (events.size >= 20) events.removeFirst()
        events.addLast("$label ${safeUrl(url)}")
    }

    fun report(elapsedMs: Long, url: String?, state: String, candidates: Int, products: Int): String = buildString {
        appendLine("ShopRadar $appVersion / Android ${android.os.Build.VERSION.RELEASE}")
        appendLine("平台：$platform")
        appendLine("起始網址：$initialUrl")
        appendLine("目前網址：${safeUrl(url.orEmpty())}")
        appendLine("耗時：${elapsedMs / 1000} 秒；擷取次數：$polls")
        appendLine("頁面狀態：$readyState；擷取狀態：${if (products > 0) "ready" else snapshotState ?: state}")
        appendLine("商品候選數（連結／卡片）：${if (platform == "蝦皮") snapshotCandidates?.toString() ?: "未量測" else candidates.toString()}；成功商品數：$products；解析回應失敗：$parseErrors")
        if (platform == "蝦皮") cardCounts?.let { counts ->
            appendLine("已渲染商品候選：${counts[0]}；本次解析：${counts[1]}；缺少名稱：${counts[2]}；缺少價格：${counts[3]}")
        }
        if (platform == "蝦皮") publicPageState?.let { page ->
            appendLine("搜尋參數符合：${page.optBoolean("requestMatches")}；頁碼符合：${page.optBoolean("pageVerified")}；僅有舊頁商品：${page.optBoolean("previousPageOnly")}")
            appendLine("網站商品格：${page.optInt("slots")}；列表完整：${page.optBoolean("complete")}；網站總頁數：${page.optInt("totalPages")}")
        }
        val measured = platform != "蝦皮" || snapshotState != null
        appendLine("頁面腳本數：${if (measured) scriptCount.toString() else "未量測"}；圖片數：${if (measured) imageCount.toString() else "未量測"}；結構化商品數：${if (platform == "蝦皮") "未量測" else structuredCandidates.toString()}；JavaScript 錯誤數：$javascriptErrors")
        appendLine("頁面可見狀態：$pageVisibility；視窗大小：$viewport")
        appendLine("WebView：${if (android.os.Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "unknown" else "unknown"}")
        if (scriptErrorCategories.isNotEmpty()) {
            appendLine("JavaScript 錯誤分類：${scriptErrorCategories.entries.joinToString("；") { "${it.key} ${it.value}" }}")
            appendLine("UA 引擎主版本：${engineMajor?.toString() ?: "未識別"}（與 WebView 提供者版本不同；不代表網站支援狀態）")
        }
        if (platform == "蝦皮") appendLine(trace.report())
        appendLine("載入事件（最多 20 筆；沒有 HTTP 錯誤紀錄不代表所有請求成功）：")
        appendLine(events.joinToString("\n").ifBlank { "尚未收到頁面載入事件" })
        append(if (html == null) "隱私：診斷不收集 Cookie、密碼、頁面內容或網址參數值。"
            else "Debug 隱私：$platform HTML 快照僅存於本機快取，不會自動上傳；不讀取 Cookie API、HTTP 標頭或表單輸入值。但頁面腳本、網址與內容仍可能包含帳號資料或權杖，匯出前請確認。")
    }

    companion object {
        fun safeUrl(url: String): String = runCatching {
            val uri = URI(url)
            val host = uri.host ?: return "unknown"
            val path = uri.path.orEmpty().split('/').filter { it.isNotBlank() }.joinToString("/") {
                if (it in setOf("marketplace", "search", "item", "login", "checkpoint", "consent", "buyer", "p", "categories") || it == "114987875182625") it else "[隱藏]"
            }
            "${uri.scheme}://$host/$path${if (uri.rawQuery != null) "?[參數已隱藏]" else ""}"
        }.getOrDefault("unknown")
    }
}

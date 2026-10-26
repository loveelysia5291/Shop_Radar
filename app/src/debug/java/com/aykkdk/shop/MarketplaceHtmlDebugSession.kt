package com.aykkdk.shop

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Debug source set only. Captures the same renderer that failed, not a second HTTP client. */
internal class MarketplaceHtmlDebugSession(context: Context, web: WebView, private val platform: String,
    private val onSnapshot: (JSONObject) -> Unit) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val id = UUID.randomUUID().toString()
    private var failed = false
    // Retain the existing cache root so upgrades can prune earlier FB diagnostics.
    private val folder = File(app.cacheDir, "shopradar-facebook-html/$id")
    private val script = CaptureAssets.read(app, "marketplace-html-debug.js")
    private val notes = mutableListOf<String>()

    init {
        scope.launch(Dispatchers.IO) { MarketplaceHtmlArchives.prune(app) }
        handler.postDelayed({ take(web, "early-4s") {} }, 4_000)
    }

    private fun take(web: WebView, label: String, callback: (String) -> Unit) {
        val once = AtomicBoolean(false)
        fun complete(note: String) {
            if (once.compareAndSet(false, true)) { notes.add(note); callback(note) }
        }
        val current = Uri.parse(web.url.orEmpty())
        if (!DebugHtmlPagePolicy.accepts(platform, current.toString())) {
            complete("$label：未擷取非公開商品頁面（例如登入或驗證頁）。")
            return
        }
        handler.postDelayed({ complete("$label：HTML 擷取未回應（4 秒）。") }, 4_000)
        runCatching {
            web.evaluateJavascript(script) { value ->
                if (once.get()) return@evaluateJavascript
                scope.launch {
                    var snapshot: JSONObject? = null
                    val note = withContext(Dispatchers.IO) {
                        runCatching {
                            val decoded = JSONTokener(value).nextValue() as? String
                                ?: error("empty snapshot response")
                            val data = JSONObject(decoded)
                            require(!data.has("error")) { "snapshot page unavailable" }
                            val html = data.remove("html") as? String ?: error("missing html")
                            require(html.length <= 4 * 1024 * 1024) { "snapshot limit exceeded" }
                            snapshot = data
                            folder.mkdirs()
                            if (html.isNotEmpty()) File(folder, "$label.html").writeText(html, Charsets.UTF_8)
                            File(folder, "$label.json").writeText(data.toString(2), Charsets.UTF_8)
                            "$label：${html.length} 字元；商品連結 ${data.optInt(if (platform == "蝦皮") "shopeeProductLinks" else "marketplaceItemAnchors")}" +
                                if (data.optBoolean("truncated")) "；超過 4 Mi 字元，已截斷。" else "。"
                        }.getOrElse { "$label：HTML 解碼或保存失敗（${it.javaClass.simpleName}）。" }
                    }
                    snapshot?.let(onSnapshot)
                    complete(note)
                }
            }
        }.onFailure { complete("$label：WebView 已無法提供 HTML。") }
    }

    fun finish(web: WebView, report: () -> String, callback: (String) -> Unit) {
        failed = true
        handler.removeCallbacksAndMessages(null)
        take(web, "failure") { publish(report, callback) }
    }

    /** Access navigation is terminal: retain the early public snapshot, never sample the new page. */
    fun finishAccess(report: () -> String, callback: (String) -> Unit) {
        failed = true
        handler.removeCallbacksAndMessages(null)
        notes.add("failure：已要求官方登入／驗證，未執行頁面腳本或擷取該頁 HTML。")
        publish(report, callback)
    }

    private fun publish(report: () -> String, callback: (String) -> Unit) {
            val completed = "${report()}\nHTML 診斷編號：$id\nHTML 診斷平台：${if (platform == "蝦皮") "shopee" else "facebook"}\n" + notes.joinToString("\n") +
                "\nHTML 僅為目前頂層頁面的渲染後 DOM；每份最多 4 Mi 字元。關閉此提示框時移除快取；再次診斷時清理超過 10 分鐘的舊快取，最多保留 5 次快照組。不自動上傳。"
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { folder.mkdirs(); File(folder, "report.txt").writeText(completed, Charsets.UTF_8) }
                }
                callback(completed)
            }
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        if (!failed) {
            scope.cancel()
            // This explicit directory belongs solely to this debug capture session.
            folder.deleteRecursively()
        }
        // A failed session finishes saving off-screen. Its UI owns export/cleanup.
    }
}

internal object MarketplaceHtmlArchives {
    fun platformSlug(report: String): String = if (Regex("(?m)^HTML 診斷平台：shopee$").containsMatchIn(report)) "shopee" else "facebook"
    fun sessionId(report: String): String? = Regex("HTML 診斷編號：([a-f0-9-]{36})").find(report)?.groupValues?.get(1)
        ?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
    private fun folder(context: Context, id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(context.cacheDir, "shopradar-facebook-html/$id")
    }
    fun files(context: Context, id: String): List<File> = folder(context, id).listFiles().orEmpty()
        .filter { it.isFile && it.name in setOf("early-4s.html", "early-4s.json", "failure.html", "failure.json", "report.txt") }
        .sortedBy { it.name }
    fun clear(context: Context, id: String) { folder(context, id).deleteRecursively() }
    fun prune(context: Context) {
        val root = File(context.cacheDir, "shopradar-facebook-html")
        val cutoff = System.currentTimeMillis() - 10 * 60_000
        root.listFiles().orEmpty().filter { it.isDirectory &&
            runCatching { UUID.fromString(it.name).toString() == it.name }.getOrDefault(false) }
            .sortedByDescending { it.lastModified() }.forEachIndexed { index, entry ->
                if (entry.lastModified() < cutoff || index >= 4) entry.deleteRecursively()
            }
    }
}

/** Never archive login/security pages, even if the reader has been redirected. */
internal object DebugHtmlPagePolicy {
    fun accepts(platform: String, raw: String): Boolean = runCatching {
        val uri = java.net.URI(raw)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return false
        when (platform) {
            "蝦皮" -> uri.host in setOf("shopee.tw", "www.shopee.tw", "m.shopee.tw") && uri.path in setOf("/search", "/search/")
            "Facebook" -> uri.host in setOf("www.facebook.com", "m.facebook.com", "web.facebook.com") &&
                Regex("^/marketplace/(?:\\d+/)?search/?$").matches(uri.path)
            else -> false
        }
    }.getOrDefault(false)
}

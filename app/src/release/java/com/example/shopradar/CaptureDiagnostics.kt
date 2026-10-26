package com.example.shopradar

/** Release adapter: no event retention, HTML access, file writing or diagnostic reports. */
@Suppress("UNUSED_PARAMETER")
internal class CaptureDiagnostics(platform: String, url: String, appVersion: String) {
    val liveText = ""
    fun step(value: CaptureStep) {}
    fun samplePage(web: android.webkit.WebView) {}
    fun consoleError(message: String, userAgent: String) {}
    var polls: Int
        get() = 0
        set(value) {}
    var parseErrors: Int
        get() = 0
        set(value) {}
    var readyState: String
        get() = ""
        set(value) {}
    var javascriptErrors: Int
        get() = 0
        set(value) {}
    var scriptCount: Int
        get() = 0
        set(value) {}
    var imageCount: Int
        get() = 0
        set(value) {}
    var structuredCandidates: Int
        get() = 0
        set(value) {}
    var pageVisibility: String
        get() = ""
        set(value) {}
    var viewport: String
        get() = ""
        set(value) {}
    fun event(label: String, url: String) {}
    fun report(elapsedMs: Long, url: String?, state: String, candidates: Int, products: Int) = ""
    fun startHtmlCapture(context: android.content.Context, web: android.webkit.WebView, enabled: Boolean) {}
    fun readerMillis(normal: Long) = normal
    fun finishFailure(web: android.webkit.WebView, report: () -> String, callback: (String) -> Unit) { callback("") }
    fun finishAccess(report: () -> String, callback: (String) -> Unit) { callback("") }
    fun close() {}
}

package com.aykkdk.shop

/** Bounded, session-local, fixed-label trace. No URLs, credentials, HTML or console messages. */
internal class CaptureTrace(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val started = clock()
    private val entries = mutableListOf<String>()
    var current = CaptureStep.LOADING
        private set
    private var beforeTerminal = CaptureStep.LOADING
    private var recorded = false
    private var important: CaptureStep? = null

    fun step(value: CaptureStep) {
        if (recorded && current in terminal) return // Late WebView events cannot replace the stop reason.
        if (recorded && value == current) return
        if (value in terminal) beforeTerminal = current
        if (value in importantSteps) important = value
        current = value
        recorded = true
        if (entries.size >= 40) entries.removeAt(8) // Keep initial milestones and recent actions.
        entries.add("${(clock() - started).coerceAtLeast(0)} ms：${label(value)}")
    }
    fun report() = "目前步驟：${label(current)}\n" +
        (if (current in terminal) "結束前步驟：${label(beforeTerminal)}\n" else "") +
        (important?.let { "最近關鍵操作：${label(it)}\n" } ?: "") +
        "操作軌跡（最多 40 筆，保留起始及近期步驟）：\n${entries.joinToString("\n")}"

    companion object {
        private val terminal = setOf(CaptureStep.SUCCESS, CaptureStep.TIMEOUT, CaptureStep.STOPPED, CaptureStep.AUTH_REQUIRED, CaptureStep.CONSENT_REQUIRED)
        private val importantSteps = setOf(CaptureStep.AUTH_REQUIRED, CaptureStep.CONSENT_REQUIRED, CaptureStep.EVALUATION_FAILED, CaptureStep.LOAD_FAILED)
        fun label(value: CaptureStep): String = when (value) {
            CaptureStep.LOADING -> "開啟搜尋網址"
            CaptureStep.PAGE_STARTED -> "開始載入頁面"
            CaptureStep.PAGE_COMMITTED -> "搜尋頁開始顯示"
            CaptureStep.PAGE_FINISHED -> "頁面完成載入事件"
            CaptureStep.EXTRACTING -> "執行商品擷取腳本／等待回應"
            CaptureStep.PARSED -> "已收到擷取腳本回應"
            CaptureStep.AUTH_REQUIRED -> "需要完成官方登入／驗證"
            CaptureStep.CONSENT_REQUIRED -> "Yahoo 轉址尚未返回商品頁"
            CaptureStep.WAITING_PRODUCTS -> "等待可讀取的商品卡片"
            CaptureStep.SUCCESS -> "擷取商品完成"
            CaptureStep.TIMEOUT -> "到達搜尋等待上限"
            CaptureStep.STOPPED -> "使用者結束診斷"
            CaptureStep.EVALUATION_FAILED -> "WebView 無法執行擷取腳本"
            CaptureStep.LOAD_FAILED -> "頁面載入失敗"
        }
    }
}

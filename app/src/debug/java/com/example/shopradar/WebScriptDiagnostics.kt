package com.example.shopradar

/** Classify without retaining text, source URLs, full UA, account data or tokens. Debug only. */
internal object WebScriptDiagnostics {
    fun category(message: String): String {
        val text = message.take(4096).lowercase()
        return when {
            "syntaxerror" in text || "unexpected token" in text -> "語法錯誤"
            "referenceerror" in text || "is not defined" in text -> "未定義參照"
            "typeerror" in text || "is not a function" in text -> "型別錯誤"
            "securityerror" in text || "content security policy" in text || "blocked by cors" in text -> "安全限制"
            "chunkloaderror" in text || "loading chunk" in text -> "腳本分段載入失敗"
            "failed to fetch" in text || "net::err_" in text -> "網路載入失敗"
            else -> "其他錯誤"
        }
    }
    fun engineMajor(userAgent: String): Int? = Regex("(?:Chrome|Chromium)/(\\d{1,4})\\.")
        .find(userAgent.take(4096))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
}

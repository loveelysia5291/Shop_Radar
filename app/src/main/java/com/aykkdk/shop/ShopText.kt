package com.aykkdk.shop

/** Reject page headings, not legitimate product titles. */
internal object ShopText {
    private val heading = Regex("^全部商品\\s*[-－]\\s*")
    private val suffix = Regex("\\s*[|｜]\\s*露天市集.*$")
    private val badge = Regex("\\s*實名(?:認證|驗證)\\s*$")
    private val placeholder = Regex("^(?:優惠推薦|有\\s*[\\d,]+\\+?\\s*(?:件|個|的)|搜尋結果|全部商品)")
    fun title(value: String): String = value.trim()
        .replace(heading, "")
        .replace(suffix, "").trim()

    fun shop(value: String): String {
        val clean = title(value).replace(badge, "").trim()
        return if (placeholder.containsMatchIn(clean)) "" else clean
    }
}

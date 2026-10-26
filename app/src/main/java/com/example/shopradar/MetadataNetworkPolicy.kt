package com.example.shopradar

import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.net.URI

/** Metadata readers need HTML/scripts, not product photos, video or downloaded fonts. */
internal object MetadataNetworkPolicy {
    private val mediaExtension = Regex("\\.(?:avif|bmp|gif|ico|jpe?g|png|svg|webp|mp4|m4v|webm|mov|mp3|m4a|ogg|wav|woff2?|ttf|otf)$", RegexOption.IGNORE_CASE)

    fun configure(web: WebView) {
        web.settings.apply {
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
            // Respect server cache freshness; never force stale inventory HTML.
            cacheMode = WebSettings.LOAD_DEFAULT
        }
    }

    internal fun blocks(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val imageHost = uri.host?.lowercase().orEmpty()
        val shopeeImage = (imageHost.endsWith(".img.susercontent.com") || imageHost.endsWith(".img.shopee.tw")) &&
            uri.path.orEmpty().startsWith("/file/")
        uri.scheme?.lowercase() in setOf("https", "http") && (shopeeImage || mediaExtension.containsMatchIn(uri.path.orEmpty()))
    }.getOrDefault(false)

    fun response(raw: String, mainFrame: Boolean): WebResourceResponse? =
        if (!mainFrame && blocks(raw)) WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))) else null
}

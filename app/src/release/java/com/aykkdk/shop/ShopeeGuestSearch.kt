package com.aykkdk.shop

import android.content.Context
import androidx.compose.runtime.*
import java.net.URI

internal object ShopeeGuestSearch {
    var enabled by mutableStateOf(true)

    fun allows(request: SearchCapture): Boolean = enabled && request.market == Market.SHOPEE &&
        request.quickSearch && !request.scanSubscription && !request.backgroundRead &&
        publicSearch(request.url)

    private fun publicSearch(url: String): Boolean = ShopeeLoginPolicy.publicSearch(url)

    fun script(context: Context, request: SearchCapture): String =
        if (allows(request)) CaptureAssets.read(context, "shopee-guest-search-debug.js") + ";\n" else ""

    fun blockLoginRedirect(request: SearchCapture, destination: String, hasProducts: Boolean, userGesture: Boolean,
        emptyAttempted: Boolean = false): Boolean =
        allows(request) && (hasProducts || !emptyAttempted) && !userGesture && ShopeeLoginPolicy.officialPage(destination) &&
            runCatching { URI(destination).path.trimEnd('/') in setOf("/buyer/login", "/login") }.getOrDefault(false)
}

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DebugShopeeGuestMenu(onDismiss: () -> Unit) {}

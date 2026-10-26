package com.example.shopradar

import android.content.Context
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import java.net.URI

/** Session-only opt-in experiment. Never changes the sign-in browser or background reads. */
internal object ShopeeGuestSearch {
    var enabled by mutableStateOf(false)

    fun allows(request: SearchCapture): Boolean = enabled && request.market == Market.SHOPEE &&
        request.quickSearch && !request.scanSubscription && !request.backgroundRead &&
        publicSearch(request.url)

    private fun publicSearch(url: String): Boolean = ShopeeLoginPolicy.publicSearch(url)

    fun script(context: Context, request: SearchCapture): String =
        if (allows(request)) CaptureAssets.read(context, "shopee-guest-search-debug.js") + ";\n" else ""

    // One empty-page attempt only. Keeping an empty page forever cannot unlock server data.
    fun blockLoginRedirect(request: SearchCapture, destination: String, hasProducts: Boolean, userGesture: Boolean,
        emptyAttempted: Boolean = false): Boolean =
        allows(request) && (hasProducts || !emptyAttempted) && !userGesture && ShopeeLoginPolicy.officialPage(destination) &&
            runCatching { URI(destination).path.trimEnd('/') in setOf("/buyer/login", "/login") }.getOrDefault(false)
}

@Composable
internal fun DebugShopeeGuestMenu(onDismiss: () -> Unit) {
    DropdownMenuItem(text = { Text("蝦皮免登入實驗（Debug）") },
        leadingIcon = { Checkbox(ShopeeGuestSearch.enabled, null) }, onClick = {
            ShopeeGuestSearch.enabled = !ShopeeGuestSearch.enabled
            onDismiss()
        })
}

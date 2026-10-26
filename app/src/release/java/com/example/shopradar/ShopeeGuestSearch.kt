package com.example.shopradar

import android.content.Context
import androidx.compose.runtime.Composable

/** No anonymous experiment, injected script or interception implementation in Release. */
@Suppress("UNUSED_PARAMETER")
internal object ShopeeGuestSearch {
    const val enabled = false
    fun allows(request: SearchCapture) = false
    fun script(context: Context, request: SearchCapture) = ""
    fun blockLoginRedirect(request: SearchCapture, destination: String, hasProducts: Boolean, userGesture: Boolean,
        emptyAttempted: Boolean = false) = false
}

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DebugShopeeGuestMenu(onDismiss: () -> Unit) {}

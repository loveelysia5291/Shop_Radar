package com.example.shopradar

import androidx.compose.runtime.*

internal val LocalScreenBackBinding = staticCompositionLocalOf<ScreenBackBinding> {
    error("MainActivity must provide its screen back mapping")
}

/** Publishes the latest screen action. Registration belongs to MainActivity. */
@Composable
internal fun ScreenBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val binding = LocalScreenBackBinding.current
    val token = remember { Any() }
    SideEffect { binding.bind(token, enabled, onBack) }
    DisposableEffect(binding, token) {
        onDispose { binding.clear(token) }
    }
}

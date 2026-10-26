package com.example.shopradar

import androidx.compose.foundation.layout.fillMaxSize

internal object CaptureDebugPolicy {
    @Suppress("UNUSED_PARAMETER") fun visibleReader(request: SearchCapture?) = false
    @Suppress("UNUSED_PARAMETER") fun searchMillis(platform: String, normal: Long) = normal
}

@androidx.compose.runtime.Composable
@Suppress("UNUSED_PARAMETER")
internal fun CaptureReaderSurface(request: SearchCapture, liveText: String, onStop: () -> Unit,
    content: @androidx.compose.runtime.Composable (androidx.compose.ui.Modifier) -> Unit) {
    content(androidx.compose.ui.Modifier.fillMaxSize())
}

/** Release source set has no diagnostic/export controls. */
@androidx.compose.runtime.Composable
@Suppress("UNUSED_PARAMETER")
internal fun CaptureDiagnosticDialog(message: String, report: String, onClose: () -> Unit) {}

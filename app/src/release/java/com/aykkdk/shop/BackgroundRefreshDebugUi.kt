package com.aykkdk.shop

import androidx.compose.runtime.Composable

/** No status menu, WorkManager inspector or diagnostic UI in Release. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DebugBackgroundRefreshMenu(onOpen: () -> Unit) {}

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun BackgroundRefreshDialog(target: RefreshTarget? = null, onClose: () -> Unit) {}

package com.example.shopradar

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Separate photo corners retain 48dp touch targets and compact, shape-aware feedback. */
@Composable
internal fun AuctionCardCover(target: RefreshTarget, reading: Boolean, onRefresh: () -> Unit,
    extraMenu: @Composable (onDismiss: () -> Unit) -> Unit, image: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val separateActions = maxWidth >= (2 * RadarLayoutPolicy.CARD_ACTION_SIZE).dp
        Box(if (separateActions) Modifier.fillMaxWidth().aspectRatio(1f)
            else Modifier.fillMaxWidth().height(96.dp)) {
            image(Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopStart)) {
                CardIconButton(onClick = onRefresh, enabled = !reading) {
                    if (reading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    else Icon(Icons.Default.Refresh, "刷新這件競拍", Modifier.size(14.dp))
                }
                if (!separateActions) AutomaticRefreshMenu(target, compactFeedback = true, extra = extraMenu)
            }
            if (separateActions) Box(Modifier.align(Alignment.TopEnd)) {
                AutomaticRefreshMenu(target, compactFeedback = true, extra = extraMenu)
            }
        }
    }
}

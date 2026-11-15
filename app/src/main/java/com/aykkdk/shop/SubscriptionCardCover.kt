package com.aykkdk.shop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Photo corners hold refresh and close; the settings menu lives in the text header. */
@Composable
internal fun SubscriptionCardCover(
    target: RefreshTarget,
    reading: Boolean,
    refreshLabel: String,
    removeLabel: String,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    isLightning: Boolean = false,
    onToggleLightning: (() -> Unit)? = null,
    image: @Composable (Modifier) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val separateClose = maxWidth >= (2 * RadarLayoutPolicy.CARD_ACTION_SIZE).dp
        val cover = if (separateClose) Modifier.fillMaxWidth().aspectRatio(1f)
            else Modifier.fillMaxWidth().height(144.dp)
        // Move only the small glyphs: full, non-overlapping 48dp targets stay in the card.
        val actionIcon = Modifier.size(16.dp)

        val topEndActions: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onToggleLightning != null) {
                    CardIconButton(onClick = onToggleLightning) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = if (isLightning) "關閉閃電刷新" else "開啟閃電刷新",
                            modifier = actionIcon,
                            tint = if (isLightning) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                CardIconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, removeLabel, actionIcon)
                }
            }
        }

        Box(cover) {
            image(Modifier.fillMaxSize())
            // Transparent controls retain their accessible touch targets, without photo overlays.
            Column(Modifier.align(Alignment.TopStart), horizontalAlignment = Alignment.CenterHorizontally) {
                CardIconButton(onClick = onRefresh, enabled = !reading) {
                    if (reading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    else Icon(Icons.Default.Refresh, refreshLabel, Modifier.size(14.dp))
                }
                if (!separateClose) topEndActions()
            }
            if (separateClose) Box(Modifier.align(Alignment.TopEnd)) { topEndActions() }
        }
    }
}

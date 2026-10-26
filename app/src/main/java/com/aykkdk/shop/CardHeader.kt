package com.aykkdk.shop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** All card actions share 48dp slots and one vertical center, independent of title height. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardHeader(title: String, actions: List<@Composable () -> Unit>, platform: String = "",
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium, stackedTitlePadding: Dp = 0.dp) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        val inline = RadarLayoutPolicy.inlineCardHeader(availableWidth.value, actions.size, platform.isNotBlank(), fontScale)
        val actionWidth = (actions.size * RadarLayoutPolicy.CARD_ACTION_SIZE).dp
        val actionSlots: @Composable () -> Unit = {
            actions.forEach { action ->
                Box(Modifier.size(RadarLayoutPolicy.CARD_ACTION_SIZE.dp), contentAlignment = Alignment.Center) { action() }
            }
        }
        if (inline) {
            Row(Modifier.fillMaxWidth().heightIn(min = RadarLayoutPolicy.CARD_ACTION_SIZE.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = titleStyle, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (platform.isNotBlank()) Text(platform, Modifier.widthIn(max = 92.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.width(actionWidth), verticalAlignment = Alignment.CenterVertically) { actionSlots() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (availableWidth >= actionWidth) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) { actionSlots() }
                else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) { actionSlots() }
                if (platform.isNotBlank()) Text(platform, Modifier.padding(horizontal = stackedTitlePadding),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, Modifier.padding(horizontal = stackedTitlePadding), style = titleStyle,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

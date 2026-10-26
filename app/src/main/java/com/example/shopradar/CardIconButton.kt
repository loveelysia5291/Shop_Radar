package com.example.shopradar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Large accessible hit area, small centered ripple clipped to the visible icon's shape. */
@Composable
internal fun CardIconButton(onClick: () -> Unit, enabled: Boolean = true, narrow: Boolean = false,
    visualAlignment: Alignment = Alignment.Center, visualOffsetY: Dp = (-6).dp,
    content: @Composable () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val color = MaterialTheme.colorScheme.onSurfaceVariant.let { if (enabled) it else it.copy(alpha = .38f) }
    Box(Modifier.size(48.dp).clickable(interactionSource = interactions, indication = null,
        enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = visualAlignment) {
        // Unbounded ripple starts at the center, even if the 48dp target is tapped near its edge.
        Box(Modifier.offset(y = visualOffsetY).size(width = if (narrow) 20.dp else 28.dp, height = 28.dp)
            .clip(CircleShape).indication(interactions, rememberRipple(bounded = false, radius = 14.dp, color = color)),
            contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides color, content = content)
        }
    }
}

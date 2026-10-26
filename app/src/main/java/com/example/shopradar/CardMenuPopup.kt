package com.example.shopradar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween

internal val LocalCardMenuBounds = compositionLocalOf<State<Rect?>?> { null }

/** Position the menu over its own card, clamped to the visible window. */
internal class CardMenuPosition(private val card: IntRect, private val inset: Int, private val fromHeading: Boolean) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = maxOf(0, card.left + inset)
        val right = minOf(windowSize.width, card.right - inset)
        val top = maxOf(0, card.top + inset)
        val bottom = minOf(windowSize.height, card.bottom - inset)
        val maxX = maxOf(left, right - popupContentSize.width)
        val maxY = maxOf(top, bottom - popupContentSize.height)
        return IntOffset(((left + right - popupContentSize.width) / 2).coerceIn(left, maxX),
            (if (fromHeading) anchorBounds.top else anchorBounds.bottom).coerceIn(top, maxY))
    }
}

@Composable
internal fun CardMenuPopup(bounds: Rect, fromHeading: Boolean, expanded: Boolean, onDismiss: () -> Unit,
                           preferredHeight: Dp = 420.dp, content: @Composable ColumnScope.() -> Unit) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    if (!visible.currentState && !visible.targetState) return
    val density = LocalDensity.current
    val inset = with(density) { 6.dp.roundToPx() }
    val card = IntRect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }
    val visibleHeight = (minOf(bounds.bottom, screenHeight) - maxOf(bounds.top, 0f) - 2 * inset).coerceAtLeast(1f)
    val width = with(density) { (bounds.width - 2 * inset).coerceAtLeast(1f).toDp() }
    val height = with(density) { visibleHeight.toDp() }.coerceAtMost(preferredHeight)
    Popup(popupPositionProvider = remember(card, inset, fromHeading) { CardMenuPosition(card, inset, fromHeading) },
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(visibleState = visible,
            enter = fadeIn(tween(180)) + scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .98f)) {
        Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 1f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Column(Modifier.width(width).height(height).padding(vertical = 4.dp), content = content)
        }
        }
    }
}

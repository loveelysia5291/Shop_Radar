package com.example.shopradar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Filter intent, then feed continuous movement into Pager's scroll/snap machinery. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.intentionalPageSwipe(enabled: Boolean, pager: PagerState, fling: FlingBehavior): Modifier {
    val configuration = LocalViewConfiguration.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val verticalSlop = configuration.touchSlop
    val horizontalSlop = maxOf(verticalSlop * 2f, with(density) { 24.dp.toPx() })
    val minimumSwipe = with(density) { 72.dp.toPx() }
    val systemGestures = WindowInsets.systemGestures
    val edgeFallback = with(density) { 24.dp.toPx() }
    val longPressTimeout = configuration.longPressTimeoutMillis
    val dragScope = rememberCoroutineScope()
    return pointerInput(enabled, pager, fling, horizontalSlop, verticalSlop, minimumSwipe, longPressTimeout, systemGestures, density, layoutDirection) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Read at touch-down so rotation/inset updates do not leave stale edge regions.
            if (!PageSwipeEdgePolicy.canStart(down.position.x, size.width.toFloat(),
                    systemGestures.getLeft(density, layoutDirection).toFloat(), systemGestures.getRight(density, layoutDirection).toFloat(), edgeFallback))
                return@awaitEachGesture
            val intent = PageSwipeIntent(horizontalSlop, verticalSlop,
                maxOf(minimumSwipe, size.width * 0.20f, horizontalSlop))
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            var dragEvents: Channel<Float>? = null
            var releaseVelocity = 0f
            try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // Do not turn pinching or another component's claimed gesture into navigation.
                if (event.changes.size != 1 || event.changes.any { it.isConsumed }) {
                    intent.cancel()
                    return@awaitEachGesture
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (intent.axis == PageSwipeIntent.Axis.Pending &&
                    change.uptimeMillis - down.uptimeMillis >= longPressTimeout) return@awaitEachGesture
                val movement = change.position - change.previousPosition
                velocity.addPosition(change.uptimeMillis, change.position)
                val previousAxis = intent.axis
                when (intent.record(movement.x, movement.y)) {
                    PageSwipeIntent.Axis.Vertical, PageSwipeIntent.Axis.Cancelled -> return@awaitEachGesture
                    PageSwipeIntent.Axis.Horizontal -> {
                        change.consume()
                        if (dragEvents == null) {
                            val events = Channel<Float>(Channel.UNLIMITED)
                            dragEvents = events
                            dragScope.launch {
                                var dragged = 0f
                                val pageSize = pager.layoutInfo.pageSize.toFloat().coerceAtLeast(1f)
                                try {
                                    pager.scroll(MutatePriority.UserInput) {
                                        for (delta in events) {
                                            // A long drag still only spans the adjacent page.
                                            dragged += scrollBy((-delta).coerceIn(-pageSize - dragged, pageSize - dragged))
                                        }
                                    }
                                    // Use Pager's default velocity/decay/spring, not a fixed tween.
                                    pager.scroll {
                                        val pagerScroll = this
                                        val boundedScroll = object : ScrollScope {
                                            override fun scrollBy(pixels: Float): Float {
                                                val consumed = pagerScroll.scrollBy(pixels.coerceIn(-pageSize - dragged, pageSize - dragged))
                                                dragged += consumed
                                                return consumed
                                            }
                                        }
                                        with(fling) {
                                            boundedScroll.performFling(if (abs(dragged) >= pageSize - 1f) 0f else releaseVelocity)
                                        }
                                    }
                                } finally {
                                    events.cancel()
                                }
                            }
                        }
                        val delta = if (previousAxis == PageSwipeIntent.Axis.Pending)
                            intent.dragStartDelta else movement.x
                        if (dragEvents?.trySend(delta)?.isSuccess != true) return@awaitEachGesture
                        if (!change.pressed) {
                            // Tiny fast flicks should snap back rather than skip a page.
                            releaseVelocity = if (intent.pageDelta() != 0) -velocity.calculateVelocity().x else 0f
                            return@awaitEachGesture
                        }
                    }
                    PageSwipeIntent.Axis.Pending -> {
                        if (!change.pressed) return@awaitEachGesture
                        // Read the same event after children: text-selection/list drags win.
                        if (awaitPointerEvent(PointerEventPass.Main).changes.any { it.isConsumed }) {
                            intent.cancel()
                            return@awaitEachGesture
                        }
                    }
                }
            }
            } finally {
                // Cancellation/multi-touch settles the current offset with zero velocity.
                // The scope survives pointerInput restarts, so a modal cannot strand a half-page.
                dragEvents?.close()
            }
        }
    }
}

package com.example.shopradar

import kotlin.math.abs

/** A vertical or ambiguous scroll cannot become navigation later in the gesture. */
internal class PageSwipeIntent(
    private val horizontalSlop: Float,
    private val verticalSlop: Float,
    private val minimumDistance: Float,
    private val dominance: Float = 2f
) {
    enum class Axis { Pending, Horizontal, Vertical, Cancelled }

    var axis = Axis.Pending
        private set
    private var x = 0f
    private var y = 0f
    /** Only the distance beyond touch slop is applied when dragging first starts. */
    val dragStartDelta get() = when {
        axis != Axis.Horizontal -> 0f
        x < 0f -> x + horizontalSlop
        else -> x - horizontalSlop
    }

    init {
        require(horizontalSlop.isFinite() && horizontalSlop > 0f)
        require(verticalSlop.isFinite() && verticalSlop > 0f)
        require(minimumDistance.isFinite() && minimumDistance >= horizontalSlop)
        require(dominance.isFinite() && dominance > 1f)
    }

    fun record(deltaX: Float, deltaY: Float): Axis {
        if (axis == Axis.Vertical || axis == Axis.Cancelled) return axis
        if (!deltaX.isFinite() || !deltaY.isFinite()) { cancel(); return axis }
        x += deltaX
        y += deltaY
        if (axis == Axis.Pending) {
            axis = when {
                abs(x) >= horizontalSlop && abs(x) >= abs(y) * dominance -> Axis.Horizontal
                abs(y) >= verticalSlop -> Axis.Vertical
                else -> Axis.Pending
            }
        }
        return axis
    }

    fun cancel() { axis = Axis.Cancelled }

    /** One deliberate swipe changes at most one page; short flicks do not count. */
    fun pageDelta(): Int = when {
        axis != Axis.Horizontal || abs(x) < minimumDistance || abs(x) < abs(y) * dominance -> 0
        x < 0f -> 1
        else -> -1
    }
}

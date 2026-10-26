package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class PageSwipeIntentTest {
    private fun intent() = PageSwipeIntent(24f, 8f, 72f)
    @Test fun dragStartsOnlyWithDistanceBeyondSlopWithoutJumping() {
        val left = intent()
        left.record(-30f, 1f)
        assertEquals(-6f, left.dragStartDelta, 0.001f)
        val right = intent()
        right.record(30f, 1f)
        assertEquals(6f, right.dragStartDelta, 0.001f)
        val vertical = intent()
        vertical.record(30f, 20f)
        assertEquals(0f, vertical.dragStartDelta, 0.001f)
    }

    @Test fun verticalScrollNeverTurnsIntoPagingEvenWithLaterHorizontalDrift() {
        val swipe = intent()
        assertEquals(PageSwipeIntent.Axis.Vertical, swipe.record(3f, 10f))
        swipe.record(-200f, 5f)
        assertEquals(PageSwipeIntent.Axis.Vertical, swipe.axis)
        assertEquals(0, swipe.pageDelta())
    }

    @Test fun diagonalScrollBelongsToTheList() {
        val swipe = intent()
        assertEquals(PageSwipeIntent.Axis.Vertical, swipe.record(-28f, 20f))
        assertEquals(0, swipe.pageDelta())
    }

    @Test fun shortHorizontalFlickDoesNotNavigate() {
        val swipe = intent()
        assertEquals(PageSwipeIntent.Axis.Horizontal, swipe.record(-30f, 2f))
        swipe.record(-30f, 2f)
        assertEquals(0, swipe.pageDelta())
    }

    @Test fun deliberateLeftAndRightSwipesAdvanceExactlyOnePage() {
        val left = intent()
        left.record(-25f, 2f); left.record(-55f, 3f)
        assertEquals(1, left.pageDelta())
        val right = intent()
        right.record(200f, -5f)
        assertEquals(-1, right.pageDelta())
    }

    @Test fun tinyJitterDoesNotChooseAnAxis() {
        val swipe = intent()
        assertEquals(PageSwipeIntent.Axis.Pending, swipe.record(5f, 3f))
        assertEquals(PageSwipeIntent.Axis.Pending, swipe.record(-4f, -2f))
        assertEquals(0, swipe.pageDelta())
    }

    @Test fun reversingBackToTheStartDoesNotNavigate() {
        val swipe = intent()
        swipe.record(-90f, 0f); swipe.record(80f, 0f)
        assertEquals(0, swipe.pageDelta())
    }

    @Test fun endingAsMostlyVerticalDoesNotNavigate() {
        val swipe = intent()
        swipe.record(-25f, 0f); swipe.record(-60f, 80f)
        assertEquals(0, swipe.pageDelta())
    }

    @Test fun cancelledOrInvalidMotionDoesNotNavigate() {
        val swipe = intent()
        swipe.record(-100f, 0f); swipe.cancel()
        assertEquals(0, swipe.pageDelta())
        val invalid = intent()
        assertEquals(PageSwipeIntent.Axis.Cancelled, invalid.record(Float.NaN, 1f))
        assertEquals(0, invalid.pageDelta())
    }

    @Test fun scaledThresholdsRespectHigherDensityAndWiderScreens() {
        val swipe = PageSwipeIntent(72f, 24f, 240f)
        swipe.record(-75f, 0f); swipe.record(-140f, 0f)
        assertEquals(0, swipe.pageDelta())
        swipe.record(-30f, 0f)
        assertEquals(1, swipe.pageDelta())
    }
}

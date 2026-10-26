package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class PageSwipeEdgePolicyTest {
    @Test fun bothSystemEdgesBelongToBackNotPaging() {
        for (x in listOf(0f, 20f, 40f, 370f, 390f, 400f))
            assertFalse(PageSwipeEdgePolicy.canStart(x, 400f, 40f, 30f, 24f))
        assertTrue(PageSwipeEdgePolicy.canStart(41f, 400f, 40f, 30f, 24f))
        assertTrue(PageSwipeEdgePolicy.canStart(369f, 400f, 40f, 30f, 24f))
    }
    @Test fun zeroOemInsetsStillReserveAnEdgeAndAllowMiddlePaging() {
        assertFalse(PageSwipeEdgePolicy.canStart(24f, 400f, 0f, 0f, 24f))
        assertFalse(PageSwipeEdgePolicy.canStart(376f, 400f, 0f, 0f, 24f))
        assertTrue(PageSwipeEdgePolicy.canStart(200f, 400f, 0f, 0f, 24f))
    }
    @Test fun narrowAndInvalidBoundsNeverConsumeAGesture() {
        assertFalse(PageSwipeEdgePolicy.canStart(20f, 40f, 0f, 0f, 24f))
        for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 401f))
            assertFalse(PageSwipeEdgePolicy.canStart(x, 400f, 0f, 0f, 24f))
        assertFalse(PageSwipeEdgePolicy.canStart(100f, 400f, -1f, 0f, 24f))
        assertFalse(PageSwipeEdgePolicy.canStart(100f, 0f, 0f, 0f, 24f))
    }
    @Test fun tabletDensityAndRotationUseCurrentBounds() {
        assertTrue(PageSwipeEdgePolicy.canStart(512f, 1024f, 64f, 64f, 48f))
        assertFalse(PageSwipeEdgePolicy.canStart(50f, 1024f, 64f, 64f, 48f))
        assertFalse(PageSwipeEdgePolicy.canStart(980f, 1024f, 64f, 64f, 48f))
        assertFalse(PageSwipeEdgePolicy.canStart(512f, 400f, 64f, 64f, 48f))
    }
}
